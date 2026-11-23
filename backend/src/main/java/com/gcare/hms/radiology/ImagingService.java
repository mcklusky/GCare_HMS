package com.gcare.hms.radiology;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.orders.OrderApi.VersionRequest;
import com.gcare.hms.orders.OrderService;
import com.gcare.hms.radiology.ImagingStore.OrderRow;
import com.gcare.hms.radiology.ImagingStore.StudyRow;
import com.gcare.hms.radiology.ImagingWorkApi.DecisionRequest;
import com.gcare.hms.radiology.ImagingWorkApi.ProposeLinkRequest;
import com.gcare.hms.radiology.ImagingWorkApi.StepRequest;
import com.gcare.hms.radiology.ImagingWorkApi.StudyEvent;
import com.gcare.hms.radiology.ImagingWorkApi.StudyStatus;
import com.gcare.hms.radiology.ImagingWorkApi.StudyView;
import com.gcare.hms.radiology.ImagingWorkApi.SyncResult;
import com.gcare.hms.radiology.ImagingWorkApi.WorklistItem;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.FacilityScope;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imaging worklist, radiographer steps and study linking (task 6.3; docs/25).
 *
 * <ul>
 *   <li>Every imaging order gets an HMS accession number (ACC + 9 digits) the first time the
 *       worklist or the archive check sees it.
 *   <li>Arrived and examination done move the order through OrderService (start, complete), so the
 *       order's own rules and history apply; "images available" follows from a linked study.
 *   <li>A study links automatically only when its accession number AND patient ID equal the order's
 *       accession number and patient number. Everything else waits in the queue: one person
 *       proposes a link, a different person approves it; unlinking needs a reason. A pairing a
 *       person unlinked is never linked again automatically, and a study whose accession number or
 *       patient ID changes in the archive after a decision returns to the queue.
 *   <li>The archive is asked outside any database transaction, so a slow archive never holds locks.
 *       When the archive is bound to an organization ({@code
 *       gcare.imaging.archive.organization-id}) other organizations cannot query it.
 * </ul>
 */
@Service
public class ImagingService {

  private final ImagingStore store;
  private final ImagingArchive archive;
  private final ImagingViews views;
  private final OrderService orders;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final UUID archiveOrganization;

  ImagingService(
      ImagingStore store,
      ImagingArchive archive,
      ImagingViews views,
      OrderService orders,
      AuditService audit,
      TransactionTemplate tx,
      @Value("${gcare.imaging.archive.organization-id:#{null}}") UUID archiveOrganization) {
    this.store = store;
    this.archive = archive;
    this.views = views;
    this.orders = orders;
    this.audit = audit;
    this.tx = tx;
    this.archiveOrganization = archiveOrganization;
  }

  /** Refuses organizations other than the one the archive is bound to (when it is bound). */
  static void requireArchiveFor(UUID archiveOrganization, UUID organization) {
    if (archiveOrganization != null && !archiveOrganization.equals(organization)) {
      throw new ApiException.Unavailable(
          "ARCHIVE_NOT_AVAILABLE", "No imaging archive is connected for your organization");
    }
  }

  // --- worklist and steps ----------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_WORKLIST + "')")
  @Transactional
  public List<WorklistItem> worklist(boolean includeFinished) {
    CurrentUser user = CurrentUser.require();
    assignAccessions(user.organizationId());
    FacilityScope scope = user.scopeOf(Permissions.IMAGING_WORKLIST);
    // Finished = examined with images linked, or cancelled; hidden unless asked for (in SQL).
    List<WorklistItem> items =
        scope.isEmpty()
            ? List.of()
            : store.worklist(scope, includeFinished, 300).stream().map(views::item).toList();
    audit(
        user,
        null,
        "IMAGING_WORKLIST_VIEWED",
        "ImagingWorklist",
        null,
        Map.of("count", Integer.toString(items.size())));
    return items;
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_WORKLIST + "')")
  @Transactional
  public WorklistItem arrived(UUID orderId, StepRequest request) {
    CurrentUser user = CurrentUser.require();
    OrderRow o = lockedOrder(user, orderId, Permissions.IMAGING_WORKLIST);
    if (o.arrivedAt() != null) {
      throw new ApiException.InvalidTransition("Arrival is already recorded");
    }
    checkVersion(o, request.version());
    if ("CANCELLED".equals(o.orderStatus())) {
      throw new ApiException.InvalidTransition("The order was cancelled");
    }
    if ("REQUESTED".equals(o.orderStatus())) {
      // Otherwise the department already started (or completed) it from the orders worklist.
      orders.start(orderId, new VersionRequest(o.orderVersion()));
    }
    if (store.markArrived(orderId, request.version(), user.userId()) == 0) {
      throw new ApiException.Conflict("This order was changed. Reload it.");
    }
    audit(user, o.facilityId(), "IMAGING_PATIENT_ARRIVED", "ImagingOrder", orderId, Map.of());
    return views.item(store.order(orderId).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_WORKLIST + "')")
  @Transactional
  public WorklistItem examined(UUID orderId, StepRequest request) {
    CurrentUser user = CurrentUser.require();
    OrderRow o = lockedOrder(user, orderId, Permissions.IMAGING_WORKLIST);
    if (o.arrivedAt() == null) {
      throw new ApiException.InvalidTransition("Record the patient's arrival first");
    }
    if (o.examinedAt() != null) {
      throw new ApiException.InvalidTransition("The examination is already recorded as done");
    }
    checkVersion(o, request.version());
    if ("CANCELLED".equals(o.orderStatus())) {
      throw new ApiException.InvalidTransition("The order was cancelled");
    }
    if ("IN_PROGRESS".equals(o.orderStatus())) {
      // Otherwise the department already completed it from the orders worklist.
      orders.complete(orderId, new VersionRequest(o.orderVersion()));
    }
    if (store.markExamined(orderId, request.version(), user.userId()) == 0) {
      throw new ApiException.Conflict("This order was changed. Reload it.");
    }
    audit(user, o.facilityId(), "IMAGING_EXAMINATION_DONE", "ImagingOrder", orderId, Map.of());
    return views.item(store.order(orderId).orElseThrow());
  }

  // --- archive checks --------------------------------------------------------------------------

  /** Asks the archive for studies with this order's accession number and links what matches. */
  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  public WorklistItem checkArchive(UUID orderId) {
    CurrentUser user = CurrentUser.require();
    requireArchiveFor(archiveOrganization, user.organizationId());
    // Three steps, so no database lock is held while the archive answers.
    OrderRow o = tx.execute(t -> visibleOrder(user, orderId, Permissions.IMAGING_LINK));
    List<ImagingArchive.Study> found;
    try {
      found = archive.studiesByAccession(o.accessionNumber());
    } catch (ImagingArchive.UnavailableException e) {
      throw new ApiException.Unavailable("ARCHIVE_UNAVAILABLE", e.getMessage());
    }
    return tx.execute(t -> {
      SyncResult r = absorb(user.organizationId(), found);
      audit(user, o.facilityId(), "IMAGING_ARCHIVE_CHECKED", "ImagingOrder", orderId, counts(r));
      return views.item(store.order(orderId).orElseThrow());
    });
  }

  /**
   * Scheduled sweep (no signed-in user): assigns missing accession numbers, reads studies dated
   * within the look-back window and links what matches. Runs inside the organization's context.
   */
  public SyncResult sweep(UUID organizationId, int lookbackDays) {
    tx.executeWithoutResult(t -> assignAccessions(organizationId));
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    List<ImagingArchive.Study> found =
        archive.studiesBetween(today.minusDays(lookbackDays), today.plusDays(1));
    return tx.execute(t -> absorbSwept(organizationId, found));
  }

  private SyncResult absorbSwept(UUID organizationId, List<ImagingArchive.Study> found) {
    SyncResult r = absorb(organizationId, found);
    afterCommit(
        new AuditEvent(
            organizationId,
            null,
            null,
            "IMAGING_ARCHIVE_SWEPT",
            "ImagingArchive",
            "studies",
            AuditOutcome.SUCCESS,
            null,
            Instant.now(),
            null,
            counts(r)));
    return r;
  }

  // --- mismatch queue --------------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional(readOnly = true)
  public List<StudyView> queue() {
    CurrentUser user = CurrentUser.require();
    // Unmatched studies belong to no facility yet; a proposal shows only where its order is in
    // scope.
    List<StudyView> list =
        store.studiesNotLinked().stream()
            .filter(s -> s.proposedOrderId() == null || inScope(user, s.proposedOrderId()))
            .map(views::study)
            .toList();
    audit(
        user,
        null,
        "IMAGING_QUEUE_VIEWED",
        "ImagingStudy",
        null,
        Map.of("count", Integer.toString(list.size())));
    return list;
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional(readOnly = true)
  public List<StudyEvent> history(UUID studyId) {
    CurrentUser user = CurrentUser.require();
    StudyRow s = store.study(studyId).orElseThrow(() -> new ApiException.NotFound("Study"));
    UUID order = s.orderId() != null ? s.orderId() : s.proposedOrderId();
    if (order != null && !inScope(user, order)) {
      throw new ApiException.NotFound("Study");
    }
    List<StudyEvent> events = store.events(studyId);
    audit(
        user,
        null,
        "IMAGING_STUDY_HISTORY_VIEWED",
        "ImagingStudy",
        studyId,
        Map.of("count", Integer.toString(events.size())));
    return events;
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional
  public StudyView propose(UUID studyId, ProposeLinkRequest request) {
    CurrentUser user = CurrentUser.require();
    store.lockStudy(studyId);
    StudyRow s = store.study(studyId).orElseThrow(() -> new ApiException.NotFound("Study"));
    if (s.status() != StudyStatus.UNMATCHED) {
      throw new ApiException.InvalidTransition(
          s.status() == StudyStatus.PROPOSED
              ? "A link is already proposed for this study"
              : "This study is already linked");
    }
    OrderRow o =
        store
            .orderByAccession(request.accessionNumber().trim().toUpperCase())
            .filter(x -> user.scopeOf(Permissions.IMAGING_LINK).includes(x.facilityId()))
            .orElseThrow(() ->
                new ApiException.Invalid(
                    "accession_number", "is not an imaging order at a facility you work at"));
    if ("CANCELLED".equals(o.orderStatus())) {
      throw new ApiException.Invalid("accession_number", "belongs to a cancelled order");
    }
    String note = request.note().trim();
    if (store.propose(studyId, request.version(), o.orderId(), note, user.userId()) == 0) {
      throw new ApiException.Conflict("This study was changed. Reload it.");
    }
    store.insertEvent(user.organizationId(), studyId, "PROPOSED", o.orderId(), note, user.userId());
    audit(
        user,
        o.facilityId(),
        "IMAGING_LINK_PROPOSED",
        "ImagingStudy",
        studyId,
        Map.of("order", o.orderId().toString()));
    return views.study(store.study(studyId).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional
  public StudyView approve(UUID studyId, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    store.lockStudy(studyId);
    StudyRow s = store.study(studyId).orElseThrow(() -> new ApiException.NotFound("Study"));
    if (s.status() != StudyStatus.PROPOSED) {
      throw new ApiException.InvalidTransition("This study has no proposed link");
    }
    if (s.proposedBy().id().equals(user.userId())) {
      throw new ApiException.InvalidTransition(
          "You proposed this link, so another person must approve it");
    }
    OrderRow o = visibleOrder(user, s.proposedOrderId(), Permissions.IMAGING_LINK);
    if ("CANCELLED".equals(o.orderStatus())) {
      throw new ApiException.InvalidTransition("The order was cancelled; send the proposal back");
    }
    if (store.approve(studyId, request.version(), user.userId()) == 0) {
      throw new ApiException.Conflict("This study was changed. Reload it.");
    }
    store.insertEvent(
        user.organizationId(),
        studyId,
        "LINKED",
        o.orderId(),
        request.note().trim(),
        user.userId());
    audit(
        user,
        o.facilityId(),
        "IMAGING_STUDY_LINKED",
        "ImagingStudy",
        studyId,
        Map.of("order", o.orderId().toString(), "method", "REVIEWED"));
    return views.study(store.study(studyId).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional
  public StudyView sendBack(UUID studyId, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    store.lockStudy(studyId);
    StudyRow s = store.study(studyId).orElseThrow(() -> new ApiException.NotFound("Study"));
    if (s.status() != StudyStatus.PROPOSED) {
      throw new ApiException.InvalidTransition("This study has no proposed link");
    }
    OrderRow proposed = visibleOrder(user, s.proposedOrderId(), Permissions.IMAGING_LINK);
    if (store.reset(studyId, request.version(), StudyStatus.PROPOSED) == 0) {
      throw new ApiException.Conflict("This study was changed. Reload it.");
    }
    store.insertEvent(
        user.organizationId(),
        studyId,
        "SENT_BACK",
        s.proposedOrderId(),
        request.note().trim(),
        user.userId());
    audit(user, proposed.facilityId(), "IMAGING_LINK_SENT_BACK", "ImagingStudy", studyId, Map.of());
    return views.study(store.study(studyId).orElseThrow());
  }

  /** Undoes a link (automatic or reviewed), with a reason; the study returns to the queue. */
  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_LINK + "')")
  @Transactional
  public StudyView unlink(UUID studyId, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    store.lockStudy(studyId);
    StudyRow s = store.study(studyId).orElseThrow(() -> new ApiException.NotFound("Study"));
    if (s.status() != StudyStatus.LINKED) {
      throw new ApiException.InvalidTransition("This study is not linked");
    }
    OrderRow o = visibleOrder(user, s.orderId(), Permissions.IMAGING_LINK);
    if (store.reset(studyId, request.version(), StudyStatus.LINKED) == 0) {
      throw new ApiException.Conflict("This study was changed. Reload it.");
    }
    store.insertEvent(
        user.organizationId(),
        studyId,
        "UNLINKED",
        o.orderId(),
        request.note().trim(),
        user.userId());
    audit(
        user,
        o.facilityId(),
        "IMAGING_STUDY_UNLINKED",
        "ImagingStudy",
        studyId,
        Map.of("order", o.orderId().toString()));
    return views.study(store.study(studyId).orElseThrow());
  }

  // --- matching --------------------------------------------------------------------------------

  private SyncResult absorb(UUID org, List<ImagingArchive.Study> found) {
    int created = 0;
    int linked = 0;
    int unmatched = 0;
    // A fixed order (by accession) so two concurrent checks lock orders in the same sequence.
    List<ImagingArchive.Study> sorted =
        found.stream()
            .sorted(
                Comparator.comparing((ImagingArchive.Study s) ->
                    s.accessionNumber() == null ? "" : s.accessionNumber()))
            .toList();
    for (ImagingArchive.Study s : sorted) {
      Optional<StudyRow> before = store.studyByUid(s.studyInstanceUid());
      Object[] up = store.upsertStudy(org, s);
      UUID id = (UUID) up[0];
      if ((Boolean) up[1]) {
        created++;
        store.insertEvent(org, id, "DISCOVERED", null, null, null);
      }
      StudyRow row = store.study(id).orElseThrow();
      if (before.isPresent()
          && before.get().status() != StudyStatus.UNMATCHED
          && identityChanged(before.get(), row)) {
        StudyRow was = before.get();
        store.lockStudy(id);
        if (store.detach(id) == 1) {
          store.insertEvent(
              org,
              id,
              "ARCHIVE_CHANGED",
              was.orderId() != null ? was.orderId() : was.proposedOrderId(),
              "The archive changed the accession number or patient ID after the link decision",
              null);
        }
        row = store.study(id).orElseThrow();
      }
      if (row.status() != StudyStatus.UNMATCHED) {
        continue;
      }
      Optional<OrderRow> order = exactMatch(row);
      if (order.isPresent() && store.autoLink(id, order.get().orderId()) == 1) {
        store.insertEvent(org, id, "AUTO_LINKED", order.get().orderId(), null, null);
        linked++;
      } else {
        unmatched++;
      }
    }
    return new SyncResult(found.size(), created, linked, unmatched);
  }

  /** The order this study belongs to, only on an exact accession AND patient number match. */
  private Optional<OrderRow> exactMatch(StudyRow s) {
    if (s.accessionNumber() == null || s.dicomPatientId() == null) {
      return Optional.empty();
    }
    Optional<OrderRow> candidate =
        store
            .orderByAccession(s.accessionNumber().trim())
            .filter(o -> !"CANCELLED".equals(o.orderStatus()))
            .filter(o -> o.patient().patientNumber().equals(s.dicomPatientId().trim()))
            .filter(o -> !store.wasUnlinkedFrom(s.id(), o.orderId()));
    if (candidate.isEmpty()) {
      return candidate;
    }
    // Lock the order so two concurrent checks cannot both see "no linked study" and link two.
    store.lockOrder(candidate.get().orderId());
    return candidate.filter(o -> !store.orderHasLinkedStudy(o.orderId()));
  }

  private static boolean identityChanged(StudyRow before, StudyRow now) {
    return !Objects.equals(before.accessionNumber(), now.accessionNumber())
        || !Objects.equals(before.dicomPatientId(), now.dicomPatientId());
  }

  private boolean inScope(CurrentUser user, UUID orderId) {
    return store
        .order(orderId)
        .map(o -> user.scopeOf(Permissions.IMAGING_LINK).includes(o.facilityId()))
        .orElse(false);
  }

  private void assignAccessions(UUID org) {
    for (UUID[] o : store.ordersWithoutAccession()) {
      String accession = String.format("ACC%09d", store.nextAccession(org));
      store.insertImagingOrder(org, o[0], o[1], accession);
    }
  }

  // --- access ----------------------------------------------------------------------------------

  private OrderRow visibleOrder(CurrentUser user, UUID orderId, String permission) {
    assignAccessions(user.organizationId());
    return store
        .order(orderId)
        .filter(o -> user.scopeOf(permission).includes(o.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Imaging order"));
  }

  private OrderRow lockedOrder(CurrentUser user, UUID orderId, String permission) {
    visibleOrder(user, orderId, permission);
    store.lockOrder(orderId);
    return store.order(orderId).orElseThrow();
  }

  private static void checkVersion(OrderRow o, long version) {
    if (o.version() != version) {
      throw new ApiException.Conflict("This order was changed. Reload it.");
    }
  }

  private static Map<String, String> counts(SyncResult r) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("studies_seen", Integer.toString(r.studiesSeen()));
    m.put("new_studies", Integer.toString(r.newStudies()));
    m.put("linked", Integer.toString(r.linked()));
    m.put("unmatched", Integer.toString(r.unmatched()));
    return m;
  }

  /** Audited after commit; metadata holds counts and ids, never patient or study values. */
  private void audit(
      CurrentUser user,
      UUID facilityId,
      String action,
      String type,
      UUID id,
      Map<String, String> metadata) {
    AuditEvent event =
        new AuditEvent(
            user.organizationId(),
            facilityId,
            user.userId(),
            action,
            type,
            id == null ? null : id.toString(),
            AuditOutcome.SUCCESS,
            null,
            Instant.now(),
            null,
            Map.copyOf(metadata));
    afterCommit(event);
  }

  private void afterCommit(AuditEvent event) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              audit.record(event);
            }
          });
    } else {
      audit.record(event);
    }
  }
}
