package com.gcare.hms.patient;

import com.gcare.hms.patient.PatientApi.LinkDecisionRequest;
import com.gcare.hms.patient.PatientApi.LinkRequest;
import com.gcare.hms.patient.PatientApi.Page;
import com.gcare.hms.patient.PatientApi.PatientLinkView;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import com.gcare.hms.patient.PatientApi.UnlinkRequest;
import com.gcare.hms.patient.PatientLinkStore.LinkRow;
import com.gcare.hms.patient.PatientStore.FlagRow;
import com.gcare.hms.patient.PatientStore.IdentifierRow;
import com.gcare.hms.patient.PatientStore.PatientRow;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Controlled linking of duplicate records (task 2.3; FR-003, FR-005). Rules: docs/17 §5,
 * PROVISIONAL. Links, never merges: the retired record keeps all its data and only changes status.
 * One person requests, a different person approves; every step needs a reason and is audited.
 */
@Service
public class PatientLinkService {

  private static final Set<String> STATUSES =
      Set.of("REQUESTED", "APPROVED", "REJECTED", "UNLINKED");

  private final PatientLinkStore links;
  private final PatientStore patients;
  private final PatientAudit audit;

  PatientLinkService(PatientLinkStore links, PatientStore patients, PatientAudit audit) {
    this.links = links;
    this.patients = patients;
    this.audit = audit;
  }

  /**
   * Requests a link. Both records must be in use, the retired record must not already have a link
   * waiting or in place, and no other record may point to it (no chains). From the review queue,
   * the request also closes the review item; the person who raised that item cannot.
   */
  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_LINK_REQUEST + "')")
  @Transactional
  public PatientLinkView request(LinkRequest request) {
    CurrentUser user = CurrentUser.require();
    UUID retiredId = request.retiredPatientId();
    UUID survivingId = request.survivingPatientId();
    if (retiredId.equals(survivingId)) {
      throw new ApiException.Invalid("surviving_patient_id", "must be a different patient");
    }
    links.lockPatients(List.of(retiredId, survivingId));
    PatientRow retired = patient(retiredId);
    PatientRow surviving = patient(survivingId);
    requireLinkable(retired, surviving);
    if (links.liveLinkAsRetired(retiredId)) {
      throw new ApiException.Conflict(
          "A link for this record is already waiting for approval or in place");
    }

    String reason = request.reason().trim();
    if (request.flagId() != null) {
      closeFlag(user, request, reason);
    }
    UUID id = UUID.randomUUID();
    try {
      links.insert(
          id,
          user.organizationId(),
          retiredId,
          survivingId,
          request.flagId(),
          reason,
          user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict(
          "A link for this record is already waiting for approval or in place");
    }
    audit.afterCommit(
        user,
        null,
        "PATIENT_LINK_REQUESTED",
        retiredId,
        Map.of("link", id.toString(), "surviving", survivingId.toString()));
    return view(links.find(id).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_LINK_APPROVE + "')")
  @Transactional(readOnly = true)
  public Page<PatientLinkView> list(String status, int page, int size) {
    String wanted = status == null ? "REQUESTED" : status;
    if (!STATUSES.contains(wanted)) {
      throw new ApiException.Invalid("status", "must be REQUESTED, APPROVED, REJECTED or UNLINKED");
    }
    if (page < 0) {
      throw new ApiException.Invalid("page", "must be 0 or more");
    }
    int pageSize = Math.max(1, Math.min(size, PatientService.MAX_PAGE_SIZE));
    return new Page<>(
        views(links.byStatus(wanted, page, pageSize)), page, pageSize, links.count(wanted));
  }

  /** Link history of one patient, on either side, newest first. */
  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_READ + "')")
  @Transactional(readOnly = true)
  public List<PatientLinkView> forPatient(UUID patientId) {
    CurrentUser user = CurrentUser.require();
    patient(patientId);
    List<PatientLinkView> result = views(links.forPatient(patientId));
    audit.afterCommit(user, null, "PATIENT_LINKS_VIEWED", patientId, Map.of());
    return result;
  }

  /**
   * Approves a link: the retired record becomes LINKED and points to the surviving record. Not by
   * the requester. If both records hold verified identifiers of the same type with different
   * values, an override reason is required.
   */
  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_LINK_APPROVE + "')")
  @Transactional
  public PatientLinkView approve(UUID linkId, LinkDecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    LinkRow link = pendingDecidableBy(linkId, user);
    links.lockPatients(List.of(link.retiredId(), link.survivingId()));
    requireLinkable(patient(link.retiredId()), patient(link.survivingId()));

    List<IdentifierType> conflicts = identifierConflicts(link.retiredId(), link.survivingId());
    String override =
        StringUtils.hasText(request.overrideReason()) ? request.overrideReason().trim() : null;
    if (!conflicts.isEmpty() && override == null) {
      throw new ApiException.BadRequest(
          "IDENTIFIER_CONFLICT",
          "Both records have a verified "
              + conflicts.stream().map(Enum::name).collect(Collectors.joining(", "))
              + " with different values. Approve only with an override reason.");
    }
    if (conflicts.isEmpty()) {
      override = null;
    }
    if (links.decide(
            linkId, request.version(), "APPROVED", request.note().trim(), override, user.userId())
        == 0) {
      throw new ApiException.Conflict("This link request was changed by someone else. Reload it.");
    }
    if (links.retire(link.retiredId(), link.survivingId()) == 0) {
      throw new ApiException.Conflict("The record changed while approving. Reload it.");
    }
    audit.afterCommit(
        user,
        null,
        "PATIENT_LINK_APPROVED",
        link.retiredId(),
        Map.of(
            "link", linkId.toString(),
            "surviving", link.survivingId().toString(),
            "override", Boolean.toString(override != null)));
    return view(links.find(linkId).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_LINK_APPROVE + "')")
  @Transactional
  public PatientLinkView reject(UUID linkId, LinkDecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    LinkRow link = pendingDecidableBy(linkId, user);
    if (links.decide(
            linkId, request.version(), "REJECTED", request.note().trim(), null, user.userId())
        == 0) {
      throw new ApiException.Conflict("This link request was changed by someone else. Reload it.");
    }
    audit.afterCommit(
        user,
        null,
        "PATIENT_LINK_REJECTED",
        link.retiredId(),
        Map.of("link", linkId.toString(), "surviving", link.survivingId().toString()));
    return view(links.find(linkId).orElseThrow());
  }

  /** Reverses an approved link with a reason. Both records are in use again; history is kept. */
  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_LINK_APPROVE + "')")
  @Transactional
  public PatientLinkView unlink(UUID linkId, UnlinkRequest request) {
    CurrentUser user = CurrentUser.require();
    LinkRow link = links.find(linkId).orElseThrow(() -> new ApiException.NotFound("Link"));
    if (!"APPROVED".equals(link.status())) {
      throw new ApiException.InvalidTransition("Only an approved link can be reversed");
    }
    links.lockPatients(List.of(link.retiredId(), link.survivingId()));
    if (links.unlink(linkId, request.version(), request.reason().trim(), user.userId()) == 0) {
      throw new ApiException.Conflict("This link was changed by someone else. Reload it.");
    }
    if (links.restore(link.retiredId(), link.survivingId()) == 0) {
      throw new ApiException.Conflict("The record changed while reversing the link. Reload it.");
    }
    audit.afterCommit(
        user,
        null,
        "PATIENT_UNLINKED",
        link.retiredId(),
        Map.of("link", linkId.toString(), "surviving", link.survivingId().toString()));
    return view(links.find(linkId).orElseThrow());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private PatientRow patient(UUID id) {
    return patients.find(id).orElseThrow(() -> new ApiException.NotFound("Patient"));
  }

  /** Both in use; the retired record has nothing pointing to it (no chains). */
  private void requireLinkable(PatientRow retired, PatientRow surviving) {
    if (retired.status() != PatientStatus.ACTIVE) {
      throw new ApiException.InvalidTransition("The duplicate record is already linked");
    }
    if (surviving.status() != PatientStatus.ACTIVE) {
      throw new ApiException.InvalidTransition(
          "The record to keep is itself linked to another record. Use the record still in use.");
    }
    if (links.hasRecordsLinkedTo(retired.id())) {
      throw new ApiException.InvalidTransition(
          "Other records are linked to the duplicate record. Reverse those links first.");
    }
  }

  private LinkRow pendingDecidableBy(UUID linkId, CurrentUser user) {
    LinkRow link = links.find(linkId).orElseThrow(() -> new ApiException.NotFound("Link"));
    if (!"REQUESTED".equals(link.status())) {
      throw new ApiException.InvalidTransition("This link request has already been decided");
    }
    if (link.requestedBy().equals(user.userId())) {
      throw new ApiException.InvalidTransition(
          "You requested this link, so another person must decide it");
    }
    return link;
  }

  private void closeFlag(CurrentUser user, LinkRequest request, String reason) {
    if (!user.hasPermission(Permissions.PATIENT_DUPLICATE_REVIEW)) {
      throw new AccessDeniedException("Closing a review item needs duplicate review permission");
    }
    if (request.flagVersion() == null) {
      throw new ApiException.Invalid("flag_version", "is required with flag_id");
    }
    FlagRow flag =
        patients.flag(request.flagId()).orElseThrow(() -> new ApiException.NotFound("Review item"));
    Set<UUID> pair = Set.of(request.retiredPatientId(), request.survivingPatientId());
    if (!pair.equals(Set.of(flag.patientId(), flag.candidateId()))) {
      throw new ApiException.Invalid("flag_id", "is about a different pair of records");
    }
    if (!"OPEN".equals(flag.status())) {
      throw new ApiException.InvalidTransition("This review item has already been decided");
    }
    if (flag.createdBy().equals(user.userId())) {
      throw new ApiException.InvalidTransition(
          "You raised this review item, so another reviewer must decide it");
    }
    if (patients.resolveFlag(
            flag.id(), request.flagVersion(), "LINK_REQUESTED", reason, user.userId())
        == 0) {
      throw new ApiException.Conflict("This review item was changed by someone else. Reload it.");
    }
  }

  /** Types where both records hold verified identifiers and none of the verified values match. */
  private List<IdentifierType> identifierConflicts(UUID a, UUID b) {
    Map<UUID, List<IdentifierRow>> ids = patients.identifiers(List.of(a, b));
    Map<IdentifierType, Set<String>> left = verified(ids.getOrDefault(a, List.of()));
    Map<IdentifierType, Set<String>> right = verified(ids.getOrDefault(b, List.of()));
    List<IdentifierType> conflicts = new ArrayList<>();
    for (IdentifierType type : EnumSet.allOf(IdentifierType.class)) {
      Set<String> l = left.get(type);
      Set<String> r = right.get(type);
      if (l != null && r != null && l.stream().noneMatch(r::contains)) {
        conflicts.add(type);
      }
    }
    return conflicts;
  }

  private static Map<IdentifierType, Set<String>> verified(List<IdentifierRow> rows) {
    return rows.stream()
        .filter(IdentifierRow::verified)
        .collect(
            Collectors.groupingBy(
                IdentifierRow::type,
                Collectors.mapping(IdentifierRow::valueNorm, Collectors.toSet())));
  }

  private PatientLinkView view(LinkRow row) {
    return views(List.of(row)).getFirst();
  }

  private List<PatientLinkView> views(List<LinkRow> rows) {
    Set<UUID> ids = new HashSet<>();
    rows.forEach(r -> {
      ids.add(r.retiredId());
      ids.add(r.survivingId());
    });
    Map<UUID, PatientSummary> summaries = new HashMap<>();
    patients.findAll(ids).forEach(p -> summaries.put(p.id(), PatientService.summary(p)));
    return rows.stream()
        .map(r ->
            new PatientLinkView(
                r.id(),
                r.status(),
                summaries.get(r.retiredId()),
                summaries.get(r.survivingId()),
                r.flagId(),
                r.requestReason(),
                r.requestedBy(),
                r.requestedAt(),
                r.decidedBy(),
                r.decidedAt(),
                r.decisionNote(),
                r.overrideReason(),
                r.unlinkedBy(),
                r.unlinkedAt(),
                r.unlinkReason(),
                "REQUESTED".equals(r.status())
                    ? identifierConflicts(r.retiredId(), r.survivingId())
                    : List.of(),
                r.version()))
        .toList();
  }
}
