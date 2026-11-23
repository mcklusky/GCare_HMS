package com.gcare.hms.patient;

import com.gcare.hms.organization.FacilityQueryService;
import com.gcare.hms.organization.FacilitySummary;
import com.gcare.hms.patient.DuplicateScorer.Band;
import com.gcare.hms.patient.DuplicateScorer.Facts;
import com.gcare.hms.patient.DuplicateScorer.IdentifierFact;
import com.gcare.hms.patient.PatientApi.CorrectionRequest;
import com.gcare.hms.patient.PatientApi.DuplicateCandidate;
import com.gcare.hms.patient.PatientApi.DuplicateFlagView;
import com.gcare.hms.patient.PatientApi.DuplicateReportRequest;
import com.gcare.hms.patient.PatientApi.FieldChange;
import com.gcare.hms.patient.PatientApi.IdentifierInput;
import com.gcare.hms.patient.PatientApi.IdentifierView;
import com.gcare.hms.patient.PatientApi.Page;
import com.gcare.hms.patient.PatientApi.PatientChangeView;
import com.gcare.hms.patient.PatientApi.PatientDetail;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import com.gcare.hms.patient.PatientApi.RegisterPatientRequest;
import com.gcare.hms.patient.PatientApi.ResolveFlagRequest;
import com.gcare.hms.patient.PatientStore.FlagRow;
import com.gcare.hms.patient.PatientStore.IdentifierRow;
import com.gcare.hms.patient.PatientStore.PatientRow;
import com.gcare.hms.patient.PatientStore.SearchFilter;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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
  private final PatientAudit audit;
  private final JsonMapper json;

  PatientService(
      PatientStore store,
      DuplicateScorer scorer,
      MatchingProperties matching,
      FacilityQueryService facilities,
      PatientAudit audit,
      JsonMapper json) {
    this.store = store;
    this.scorer = scorer;
    this.matching = matching;
    this.facilities = facilities;
    this.audit = audit;
    this.json = json;
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
            0,
            null));
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

  // --- corrections (policy §6) -------------------------------------------------------------------

  private static final Set<String> CORRECTABLE =
      Set.of("family_name", "given_names", "birth_date", "birth_date_estimated", "sex", "phone");

  /**
   * Corrects demographic details. Keeps the previous values, the user, time and reason in the
   * patient's history. Fails with 409 if someone else changed the record since {@code version}.
   */
  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_UPDATE + "')")
  @Transactional
  public PatientDetail correct(UUID id, CorrectionRequest request) {
    CurrentUser user = CurrentUser.require();
    PatientRow current = store.find(id).orElseThrow(() -> new ApiException.NotFound("Patient"));
    if (current.status() != PatientStatus.ACTIVE) {
      throw new ApiException.InvalidTransition(
          "This record is linked to another record. Correct the record that is still in use.");
    }
    if (current.version() != request.version()) {
      throw new ApiException.Conflict(
          "Someone else changed this patient since you opened it. Reload and try again.");
    }

    String family = current.familyName();
    String given = current.givenNames();
    LocalDate birthDate = current.birthDate();
    boolean estimated = current.birthDateEstimated();
    Sex sex = current.sex();
    String phone = current.phone();
    for (Map.Entry<String, Object> e : request.changes().entrySet()) {
      String field = e.getKey();
      Object value = e.getValue();
      if (!CORRECTABLE.contains(field)) {
        throw new ApiException.Invalid("changes." + field, "cannot be corrected here");
      }
      switch (field) {
        case "family_name" -> family = text(field, value, 100);
        case "given_names" -> given = text(field, value, 150);
        case "phone" -> phone = text(field, value, 30);
        case "birth_date" -> birthDate = date(field, value);
        case "birth_date_estimated" -> estimated = Boolean.TRUE.equals(bool(field, value));
        case "sex" -> sex = value == null ? Sex.UNKNOWN : sexOf(field, value);
        default -> throw new IllegalStateException(field);
      }
    }
    if (birthDate != null && birthDate.isAfter(today(current.registrationFacilityId()))) {
      throw new ApiException.Invalid("changes.birth_date", "must not be in the future");
    }
    if (estimated && birthDate == null) {
      throw new ApiException.Invalid("changes.birth_date", "is required when marked as estimated");
    }

    Map<String, FieldChange> diff = new TreeMap<>();
    addIfChanged(diff, "family_name", current.familyName(), family);
    addIfChanged(diff, "given_names", current.givenNames(), given);
    addIfChanged(diff, "birth_date", current.birthDate(), birthDate);
    addIfChanged(diff, "birth_date_estimated", current.birthDateEstimated(), estimated);
    addIfChanged(diff, "sex", current.sex(), sex);
    addIfChanged(diff, "phone", current.phone(), phone);
    if (diff.isEmpty()) {
      throw new ApiException.Invalid("changes", "No detail was changed");
    }

    String familyNorm = PatientNormalizer.name(family);
    String givenNorm = PatientNormalizer.name(given);
    PatientRow updated =
        new PatientRow(
            id,
            current.organizationId(),
            current.patientNumber(),
            current.registrationFacilityId(),
            current.status(),
            family,
            given,
            familyNorm,
            givenNorm,
            birthDate,
            estimated,
            sex,
            phone,
            PatientNormalizer.phone(phone, matching.phoneSignificantDigits()),
            familyNorm == null || givenNorm == null || birthDate == null || sex == Sex.UNKNOWN,
            current.registrationSource(),
            current.createdAt(),
            current.createdBy(),
            current.version(),
            current.linkedToId());
    if (store.update(updated, request.version()) == 0) {
      throw new ApiException.Conflict(
          "Someone else changed this patient since you opened it. Reload and try again.");
    }
    store.insertChange(
        user.organizationId(),
        id,
        request.version() + 1,
        json.writeValueAsString(diff),
        request.reason().trim(),
        user.userId());
    auditAfterCommit(
        user, null, "PATIENT_CORRECTED", id, Map.of("fields", String.join(",", diff.keySet())));
    return detail(id);
  }

  /** Correction history, newest first. */
  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_READ + "')")
  @Transactional(readOnly = true)
  public List<PatientChangeView> history(UUID id) {
    CurrentUser user = CurrentUser.require();
    store.find(id).orElseThrow(() -> new ApiException.NotFound("Patient"));
    TypeReference<Map<String, FieldChange>> type = new TypeReference<>() {};
    List<PatientChangeView> result =
        store.changes(id).stream()
            .map(c ->
                new PatientChangeView(
                    c.id(),
                    c.patientVersion(),
                    json.readValue(c.changesJson(), type),
                    c.reason(),
                    c.changedBy(),
                    c.changedAt()))
            .toList();
    auditAfterCommit(user, null, "PATIENT_HISTORY_VIEWED", id, Map.of());
    return result;
  }

  // --- duplicate review (policy §3) --------------------------------------------------------------

  /** Any reader can report that two records may be the same person; it goes to the queue. */
  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_READ + "')")
  @Transactional
  public void reportDuplicate(UUID id, DuplicateReportRequest request) {
    CurrentUser user = CurrentUser.require();
    UUID other = request.candidatePatientId();
    if (id.equals(other)) {
      throw new ApiException.Invalid("candidate_patient_id", "must be a different patient");
    }
    PatientRow a = store.find(id).orElseThrow(() -> new ApiException.NotFound("Patient"));
    PatientRow b = store.find(other).orElseThrow(() -> new ApiException.NotFound("Patient"));
    if (a.status() != PatientStatus.ACTIVE || b.status() != PatientStatus.ACTIVE) {
      throw new ApiException.InvalidTransition("Only records in use can be reported");
    }
    if (store.openFlagExists(id, other)) {
      throw new ApiException.Conflict("These two records are already waiting for review");
    }
    Map<UUID, List<IdentifierRow>> ids = store.identifiers(List.of(id, other));
    int score =
        scorer
            .score(
                factsOf(a, ids.getOrDefault(id, List.of())),
                factsOf(b, ids.getOrDefault(other, List.of())))
            .score();
    store.insertStaffReport(
        user.organizationId(), id, other, score, request.reason().trim(), user.userId());
    auditAfterCommit(
        user, null, "PATIENT_DUPLICATE_REPORTED", id, Map.of("other", other.toString()));
  }

  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_DUPLICATE_REVIEW + "')")
  @Transactional(readOnly = true)
  public Page<DuplicateFlagView> duplicateFlags(String status, int page, int size) {
    String wanted = status == null ? "OPEN" : status;
    if (!Set.of("OPEN", "NOT_DUPLICATE", "LINK_REQUESTED").contains(wanted)) {
      throw new ApiException.Invalid("status", "must be OPEN, NOT_DUPLICATE or LINK_REQUESTED");
    }
    if (page < 0) {
      throw new ApiException.Invalid("page", "must be 0 or more");
    }
    int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    List<FlagRow> rows = store.flags(wanted, page, pageSize);
    return new Page<>(flagViews(rows), page, pageSize, store.countFlags(wanted));
  }

  /**
   * Reviewer decision "these are different people". The reviewer must not be the person who raised
   * the flag (two-person principle); a note is required. The pair is not suggested again.
   */
  @PreAuthorize("hasAuthority('" + Permissions.PATIENT_DUPLICATE_REVIEW + "')")
  @Transactional
  public DuplicateFlagView markNotDuplicate(UUID flagId, ResolveFlagRequest request) {
    CurrentUser user = CurrentUser.require();
    FlagRow flag = store.flag(flagId).orElseThrow(() -> new ApiException.NotFound("Review item"));
    if (!"OPEN".equals(flag.status())) {
      throw new ApiException.InvalidTransition("This review item has already been decided");
    }
    if (flag.createdBy().equals(user.userId())) {
      throw new ApiException.InvalidTransition(
          "You raised this review item, so another reviewer must decide it");
    }
    int updated =
        store.resolveFlag(
            flagId, request.version(), "NOT_DUPLICATE", request.note().trim(), user.userId());
    if (updated == 0) {
      throw new ApiException.Conflict("This review item was changed by someone else. Reload it.");
    }
    auditAfterCommit(
        user,
        null,
        "PATIENT_DUPLICATE_RESOLVED",
        flag.patientId(),
        Map.of("decision", "NOT_DUPLICATE", "flag", flagId.toString()));
    return flagViews(List.of(store.flag(flagId).orElseThrow())).getFirst();
  }

  private List<DuplicateFlagView> flagViews(List<FlagRow> rows) {
    Set<UUID> ids = new HashSet<>();
    rows.forEach(r -> {
      ids.add(r.patientId());
      ids.add(r.candidateId());
    });
    Map<UUID, PatientSummary> summaries = new HashMap<>();
    store.findAll(ids).forEach(p -> summaries.put(p.id(), summary(p)));
    return rows.stream()
        .map(r ->
            new DuplicateFlagView(
                r.id(),
                r.status(),
                r.origin(),
                r.score(),
                r.reason(),
                summaries.get(r.patientId()),
                summaries.get(r.candidateId()),
                r.createdBy(),
                r.createdAt(),
                r.resolvedBy(),
                r.resolvedAt(),
                r.resolutionNote(),
                r.version()))
        .toList();
  }

  private LocalDate today(UUID facilityId) {
    return facilities
        .findActive(facilityId)
        .map(f -> LocalDate.now(ZoneId.of(f.timezone())))
        .orElse(LocalDate.now(ZoneId.of("UTC")).plusDays(1)); // lenient if the facility closed
  }

  private static void addIfChanged(
      Map<String, FieldChange> diff, String field, Object from, Object to) {
    if (!Objects.equals(from, to)) {
      diff.put(field, new FieldChange(asText(from), asText(to)));
    }
  }

  private static Object asText(Object v) {
    if (v == null || v instanceof Boolean) {
      return v;
    }
    return v instanceof Enum<?> e ? e.name() : v.toString();
  }

  private static String text(String field, Object value, int max) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof String s)) {
      throw new ApiException.Invalid("changes." + field, "must be text");
    }
    String trimmed = s.trim();
    if (trimmed.length() > max) {
      throw new ApiException.Invalid("changes." + field, "must be at most " + max + " characters");
    }
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static LocalDate date(String field, Object value) {
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(value.toString());
    } catch (DateTimeParseException e) {
      throw new ApiException.Invalid("changes." + field, "must be a date (YYYY-MM-DD)");
    }
  }

  private static Boolean bool(String field, Object value) {
    if (value == null || value instanceof Boolean) {
      return (Boolean) value;
    }
    throw new ApiException.Invalid("changes." + field, "must be true or false");
  }

  private static Sex sexOf(String field, Object value) {
    try {
      return Sex.valueOf(value.toString());
    } catch (IllegalArgumentException e) {
      throw new ApiException.Invalid("changes." + field, "must be FEMALE, MALE, OTHER or UNKNOWN");
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  private List<DuplicateCandidate> candidates(Facts facts, UUID excludeId) {
    Set<UUID> ids = store.candidateIds(facts, excludeId);
    if (excludeId != null) {
      ids.removeAll(store.notDuplicateOf(excludeId));
    }
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
    PatientApi.LinkedPatient linkedTo =
        p.linkedToId() == null
            ? null
            : store
                .find(p.linkedToId())
                .map(s -> new PatientApi.LinkedPatient(s.id(), s.patientNumber()))
                .orElse(null);
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
        p.version(),
        linkedTo);
  }

  static PatientSummary summary(PatientRow p) {
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

  private void auditAfterCommit(
      CurrentUser user,
      UUID facilityId,
      String action,
      UUID patientId,
      Map<String, String> metadata) {
    audit.afterCommit(user, facilityId, action, patientId, metadata);
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
