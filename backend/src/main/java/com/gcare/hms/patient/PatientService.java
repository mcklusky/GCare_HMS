package com.gcare.hms.patient;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.organization.FacilityQueryService;
import com.gcare.hms.organization.FacilitySummary;
import com.gcare.hms.patient.DuplicateScorer.Band;
import com.gcare.hms.patient.DuplicateScorer.Facts;
import com.gcare.hms.patient.DuplicateScorer.IdentifierFact;
import com.gcare.hms.patient.PatientApi.DuplicateCandidate;
import com.gcare.hms.patient.PatientApi.IdentifierInput;
import com.gcare.hms.patient.PatientApi.IdentifierView;
import com.gcare.hms.patient.PatientApi.Page;
import com.gcare.hms.patient.PatientApi.PatientDetail;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import com.gcare.hms.patient.PatientApi.RegisterPatientRequest;
import com.gcare.hms.patient.PatientStore.IdentifierRow;
import com.gcare.hms.patient.PatientStore.PatientRow;
import com.gcare.hms.patient.PatientStore.SearchFilter;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * Patient registration, search and duplicate candidates (task 2.1; FR-003, FR-004, FR-005). Rules:
 * docs/17-PATIENT-IDENTITY-POLICY.md (PROVISIONAL). Patients are organization-wide records:
 * PATIENT_READ at any facility allows searching the organization's patient index; registering needs
 * PATIENT_REGISTER at the registration facility.
 */
@Service
public class PatientService {

  public static final int MAX_PAGE_SIZE = 50;

  private final PatientStore store;
  private final DuplicateScorer scorer;
  private final MatchingProperties matching;
  private final FacilityQueryService facilities;
  private final AuditService audit;

  PatientService(
      PatientStore store,
      DuplicateScorer scorer,
      MatchingProperties matching,
      FacilityQueryService facilities,
      AuditService audit) {
    this.store = store;
    this.scorer = scorer;
    this.matching = matching;
    this.facilities = facilities;
    this.audit = audit;
  }

  // --- registration ----------------------------------------------------------------------------

  /**
   * Registers a patient. If likely or possible duplicates exist, the request must name every one of
   * them in {@code duplicate_review.reviewed_candidate_ids} (otherwise 409 with the candidates),
   * and likely duplicates also need a reason. Never links or merges anything.
   */
  @PreAuthorize(
      "hasAuthority('"
          + Permissions.PATIENT_REGISTER
          + "') and hasAuthority('"
          + Permissions.PATIENT_READ
          + "')")
  @Transactional
  public PatientDetail register(RegisterPatientRequest request) {
    CurrentUser user = CurrentUser.require();
    UUID facilityId = request.registrationFacilityId();
    if (!user.scopeOf(Permissions.PATIENT_REGISTER).includes(facilityId)) {
      throw new ApiException.NotFound("Facility");
    }
    FacilitySummary facility =
        facilities.findActive(facilityId).orElseThrow(() -> new ApiException.NotFound("Facility"));

    if (request.birthDate() != null
        && request.birthDate().isAfter(LocalDate.now(ZoneId.of(facility.timezone())))) {
      throw new ApiException.Invalid("birth_date", "must not be in the future");
    }
    if (request.birthDateEstimated() && request.birthDate() == null) {
      throw new ApiException.Invalid("birth_date", "is required when marked as estimated");
    }

    Facts facts = factsOf(request);
    List<DuplicateCandidate> candidates = candidates(facts, null);
    String overrideReason = null;
    if (!candidates.isEmpty()) {
      Set<UUID> reviewed =
          request.duplicateReview() == null
              ? Set.of()
              : new HashSet<>(request.duplicateReview().reviewedCandidateIds());
      boolean allReviewed = candidates.stream().allMatch(c -> reviewed.contains(c.patient().id()));
      if (!allReviewed) {
        throw new DuplicateCandidatesException(candidates);
      }
      boolean anyLikely = candidates.stream().anyMatch(c -> c.band() == Band.LIKELY);
      if (anyLikely) {
        String reason = request.duplicateReview().reason();
        if (!StringUtils.hasText(reason)) {
          throw new ApiException.Invalid(
              "duplicate_review.reason",
              "A reason is required to register a new record when a likely duplicate exists");
        }
        overrideReason = reason.trim();
      }
    }

    UUID id = UUID.randomUUID();
    String number = PatientNumber.format(store.nextNumber(user.organizationId()));
    boolean incomplete =
        facts.familyNorm() == null
            || facts.givenNorm() == null
            || request.birthDate() == null
            || request.sex() == Sex.UNKNOWN;
    store.insert(
        new PatientRow(
            id,
            user.organizationId(),
            number,
            facilityId,
            PatientStatus.ACTIVE,
            trimToNull(request.familyName()),
            trimToNull(request.givenNames()),
            facts.familyNorm(),
            facts.givenNorm(),
            request.birthDate(),
            request.birthDateEstimated(),
            request.sex(),
            trimToNull(request.phone()),
            facts.phoneNorm(),
            incomplete,
            request.registrationSource(),
            null,
            user.userId(),
            0));
    for (IdentifierInput in : request.identifiers()) {
      store.insertIdentifier(
          user.organizationId(),
          id,
          new IdentifierRow(
              UUID.randomUUID(),
              id,
              in.type(),
              in.value().trim(),
              PatientNormalizer.identifier(in.value()),
              trimToNull(in.issuer()),
              in.verified(),
              "ACTIVE",
              "REGISTRATION",
              null),
          user.userId());
    }
    int flagged = 0;
    if (overrideReason != null) {
      for (DuplicateCandidate c : candidates) {
        if (c.band() == Band.LIKELY) {
          store.insertDuplicateFlag(
              user.organizationId(),
              id,
              c.patient().id(),
              c.score(),
              overrideReason,
              user.userId());
          flagged++;
        }
      }
    }

    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("candidates_reviewed", Integer.toString(candidates.size()));
    metadata.put("registered_despite_likely_duplicate", Boolean.toString(flagged > 0));
    auditAfterCommit(user, facilityId, "PATIENT_REGISTERED", id, metadata);
    return detail(id);
  }

  // --- search ----------------------------------------------------------------------------------

  /** Search parameters as received; normalized and checked by {@link #search}. */
  public record SearchCriteria(
      String number,
      IdentifierType identifierType,
      String identifierValue,
      String phone,
      String name,
      LocalDate birthDate,
      Integer birthYear,
      Sex sex) {}

  /**
   * Searches the organization's patient index. Needs one strong criterion (number, identifier,
   * phone) or a name plus birth date/year or sex; name alone is refused (privacy).
   */
  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_READ + "')")
  @Transactional(readOnly = true)
  public Page<PatientSummary> search(SearchCriteria c, int page, int size) {
    CurrentUser user = CurrentUser.require();
    if (page < 0) {
      throw new ApiException.Invalid("page", "must be 0 or more");
    }
    int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

    String number = null;
    if (StringUtils.hasText(c.number())) {
      number =
          PatientNumber.parse(c.number())
              .orElseThrow(() ->
                  new ApiException.Invalid(
                      "number", "Not a valid patient number. Check the digits and try again."));
    }
    String identifierNorm = null;
    if (StringUtils.hasText(c.identifierValue())) {
      if (c.identifierType() == null) {
        throw new ApiException.Invalid("identifier_type", "is required with identifier_value");
      }
      identifierNorm = PatientNormalizer.identifier(c.identifierValue());
    }
    String phoneNorm = null;
    if (StringUtils.hasText(c.phone())) {
      phoneNorm = PatientNormalizer.phone(c.phone(), matching.phoneSignificantDigits());
      if (phoneNorm == null) {
        throw new ApiException.Invalid("phone", "needs at least 7 digits");
      }
    }
    String nameNorm = PatientNormalizer.name(c.name());

    List<String> used = new ArrayList<>();
    if (number != null) used.add("number");
    if (identifierNorm != null) used.add("identifier");
    if (phoneNorm != null) used.add("phone");
    if (nameNorm != null) used.add("name");
    if (c.birthDate() != null) used.add("birth_date");
    if (c.birthYear() != null) used.add("birth_year");
    if (c.sex() != null) used.add("sex");

    boolean strong = number != null || identifierNorm != null || phoneNorm != null;
    boolean nameWithDetail =
        nameNorm != null && (c.birthDate() != null || c.birthYear() != null || c.sex() != null);
    if (!strong && !nameWithDetail) {
      throw new ApiException.BadRequest(
          "SEARCH_TOO_BROAD",
          "Search with a patient number, an ID document, a phone number, or a name together with"
              + " a birth date, birth year or sex.");
    }

    PatientStore.SearchPage result =
        store.search(
            new SearchFilter(
                number,
                c.identifierType(),
                identifierNorm,
                phoneNorm,
                nameNorm,
                c.birthDate(),
                c.birthYear(),
                c.sex()),
            page,
            pageSize);

    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("criteria", String.join("+", used));
    metadata.put("results", Long.toString(result.total()));
    auditAfterCommit(user, null, "PATIENT_SEARCHED", null, metadata);
    return new Page<>(
        result.rows().stream().map(PatientService::summary).toList(),
        page,
        pageSize,
        result.total());
  }

  // --- read ------------------------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_READ + "')")
  @Transactional(readOnly = true)
  public PatientDetail get(UUID id) {
    CurrentUser user = CurrentUser.require();
    PatientDetail detail = detail(id);
    auditAfterCommit(user, null, "PATIENT_VIEWED", id, Map.of());
    return detail;
  }

  /** Likely and possible duplicates of an existing record, best first. */
  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_READ + "')")
  @Transactional(readOnly = true)
  public List<DuplicateCandidate> possibleDuplicates(UUID id) {
    CurrentUser user = CurrentUser.require();
    PatientRow row = store.find(id).orElseThrow(() -> new ApiException.NotFound("Patient"));
    List<IdentifierRow> ids = store.identifiers(List.of(id)).getOrDefault(id, List.of());
    List<DuplicateCandidate> result = candidates(factsOf(row, ids), id);
    auditAfterCommit(user, null, "PATIENT_DUPLICATES_VIEWED", id, Map.of());
    return result;
  }

  // --- helpers ---------------------------------------------------------------------------------

  private List<DuplicateCandidate> candidates(Facts facts, UUID excludeId) {
    Set<UUID> ids = store.candidateIds(facts, excludeId);
    if (ids.isEmpty()) {
      return List.of();
    }
    List<PatientRow> rows = store.findAll(ids);
    Map<UUID, List<IdentifierRow>> identifiers = store.identifiers(ids);
    List<DuplicateCandidate> result = new ArrayList<>();
    for (PatientRow row : rows) {
      DuplicateScorer.Result r =
          scorer.score(facts, factsOf(row, identifiers.getOrDefault(row.id(), List.of())));
      if (r.band() != Band.NONE) {
        result.add(new DuplicateCandidate(summary(row), r.score(), r.band(), r.evidence()));
      }
    }
    result.sort(
        Comparator.comparingInt(DuplicateCandidate::score)
            .reversed()
            .thenComparing(c -> c.patient().patientNumber()));
    return result;
  }

  private Facts factsOf(RegisterPatientRequest r) {
    return new Facts(
        PatientNormalizer.name(r.familyName()),
        PatientNormalizer.name(r.givenNames()),
        r.birthDate(),
        r.birthDateEstimated(),
        r.sex(),
        PatientNormalizer.phone(r.phone(), matching.phoneSignificantDigits()),
        r.identifiers().stream()
            .map(i ->
                new IdentifierFact(i.type(), PatientNormalizer.identifier(i.value()), i.verified()))
            .filter(i -> i.valueNorm() != null)
            .toList());
  }

  private static Facts factsOf(PatientRow row, List<IdentifierRow> identifiers) {
    return new Facts(
        row.familyNorm(),
        row.givenNorm(),
        row.birthDate(),
        row.birthDateEstimated(),
        row.sex(),
        row.phoneNorm(),
        identifiers.stream()
            .map(i -> new IdentifierFact(i.type(), i.valueNorm(), i.verified()))
            .toList());
  }

  private PatientDetail detail(UUID id) {
    PatientRow p = store.find(id).orElseThrow(() -> new ApiException.NotFound("Patient"));
    List<IdentifierView> ids =
        store.identifiers(List.of(id)).getOrDefault(id, List.of()).stream()
            .map(i ->
                new IdentifierView(
                    i.id(),
                    i.type(),
                    i.value(),
                    i.issuer(),
                    i.verified(),
                    i.source(),
                    i.createdAt()))
            .toList();
    return new PatientDetail(
        p.id(),
        p.patientNumber(),
        p.status(),
        p.familyName(),
        p.givenNames(),
        p.birthDate(),
        p.birthDateEstimated(),
        p.sex(),
        p.phone(),
        p.detailsIncomplete(),
        p.registrationFacilityId(),
        p.registrationSource(),
        ids,
        p.createdAt(),
        p.version());
  }

  private static PatientSummary summary(PatientRow p) {
    return new PatientSummary(
        p.id(),
        p.patientNumber(),
        p.status(),
        p.familyName(),
        p.givenNames(),
        p.birthDate(),
        p.birthDateEstimated(),
        p.sex(),
        p.detailsIncomplete());
  }

  /**
   * Records the audit event only once the transaction has committed, so a rolled-back registration
   * is never audited as done. Metadata never contains names or other patient values.
   */
  private void auditAfterCommit(
      CurrentUser user,
      UUID facilityId,
      String action,
      UUID patientId,
      Map<String, String> metadata) {
    AuditEvent event =
        new AuditEvent(
            user.organizationId(),
            facilityId,
            user.userId(),
            action,
            "Patient",
            patientId != null ? patientId.toString() : null,
            AuditOutcome.SUCCESS,
            null,
            Instant.now(),
            null,
            metadata);
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

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
