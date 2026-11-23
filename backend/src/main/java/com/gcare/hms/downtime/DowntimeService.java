package com.gcare.hms.downtime;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.billing.PaymentApi.RecordPaymentRequest;
import com.gcare.hms.billing.PaymentService;
import com.gcare.hms.downtime.DowntimeApi.CandidatesView;
import com.gcare.hms.downtime.DowntimeApi.CloseRequest;
import com.gcare.hms.downtime.DowntimeApi.DecisionRequest;
import com.gcare.hms.downtime.DowntimeApi.DeclareRequest;
import com.gcare.hms.downtime.DowntimeApi.DowntimeSource;
import com.gcare.hms.downtime.DowntimeApi.EndRequest;
import com.gcare.hms.downtime.DowntimeApi.EntryRequest;
import com.gcare.hms.downtime.DowntimeApi.EntryStatus;
import com.gcare.hms.downtime.DowntimeApi.EntryView;
import com.gcare.hms.downtime.DowntimeApi.EpisodeStatus;
import com.gcare.hms.downtime.DowntimeApi.EpisodeView;
import com.gcare.hms.downtime.DowntimeApi.FormBatchView;
import com.gcare.hms.downtime.DowntimeApi.FormOptions;
import com.gcare.hms.downtime.DowntimeApi.FormType;
import com.gcare.hms.downtime.DowntimeApi.IssueFormsRequest;
import com.gcare.hms.downtime.DowntimeApi.Option;
import com.gcare.hms.downtime.DowntimeApi.OrderForm;
import com.gcare.hms.downtime.DowntimeApi.PaymentForm;
import com.gcare.hms.downtime.DowntimeApi.ProposalAction;
import com.gcare.hms.downtime.DowntimeApi.ProposeRequest;
import com.gcare.hms.downtime.DowntimeApi.RegistrationForm;
import com.gcare.hms.downtime.DowntimeStore.EncounterRef;
import com.gcare.hms.downtime.DowntimeStore.InvoiceRef;
import com.gcare.hms.encounter.EncounterApi.ObservationRequest;
import com.gcare.hms.encounter.EncounterApi.OpenEncounterRequest;
import com.gcare.hms.encounter.EncounterService;
import com.gcare.hms.encounter.TriageService;
import com.gcare.hms.orders.OrderApi.CreateOrderRequest;
import com.gcare.hms.orders.OrderService;
import com.gcare.hms.patient.PatientApi.DuplicateCandidate;
import com.gcare.hms.patient.PatientApi.DuplicateReview;
import com.gcare.hms.patient.PatientApi.PatientDetail;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import com.gcare.hms.patient.PatientApi.RegisterPatientRequest;
import com.gcare.hms.patient.PatientService;
import com.gcare.hms.patient.PatientService.SearchCriteria;
import com.gcare.hms.patient.PatientStatus;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Downtime episodes, numbered paper forms and their reconciliation (task 5.3; docs/22).
 *
 * <p>Paper forms are keyed into a queue. Nothing becomes a patient, visit, observation, order or
 * payment until the form is reconciled, and reconciling goes through the normal services, so their
 * permissions, duplicate checks, state rules and audit apply unchanged. Registrations, orders and
 * payments need a proposal by one person and approval by another. Everything runs in one
 * transaction: a failure leaves the form waiting and creates nothing.
 */
@Service
public class DowntimeService {

  private static final String ANY =
      "hasAnyAuthority('"
          + Permissions.DOWNTIME_MANAGE
          + "', '"
          + Permissions.DOWNTIME_RECORD
          + "')";

  /** Result of keying a form: {@code created} is false when the same keying was repeated. */
  public record Recorded(EntryView entry, boolean created) {}

  private record Applied(UUID patientId, UUID encounterId, UUID recordId) {}

  private final DowntimeStore store;
  private final AuditService audit;
  private final JsonMapper json;
  private final PatientService patients;
  private final EncounterService encounters;
  private final TriageService triage;
  private final OrderService orders;
  private final PaymentService payments;

  DowntimeService(
      DowntimeStore store,
      AuditService audit,
      JsonMapper json,
      PatientService patients,
      EncounterService encounters,
      TriageService triage,
      OrderService orders,
      PaymentService payments) {
    this.store = store;
    this.audit = audit;
    this.json = json;
    this.patients = patients;
    this.encounters = encounters;
    this.triage = triage;
    this.orders = orders;
    this.payments = payments;
  }

  // --- episodes --------------------------------------------------------------------------------

  @PreAuthorize(ANY)
  @Transactional(readOnly = true)
  public List<EpisodeView> episodes() {
    CurrentUser user = CurrentUser.require();
    return store.episodes().stream().filter(e -> mayView(user, e.facilityId())).toList();
  }

  @PreAuthorize(ANY)
  @Transactional(readOnly = true)
  public EpisodeView episode(UUID id) {
    return visibleEpisode(CurrentUser.require(), id);
  }

  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_MANAGE + "')")
  @Transactional
  public EpisodeView declare(DeclareRequest request) {
    CurrentUser user = CurrentUser.require();
    UUID facility = request.facilityId();
    if (!user.scopeOf(Permissions.DOWNTIME_MANAGE).includes(facility)) {
      throw new ApiException.NotFound("Facility");
    }
    Instant now = Instant.now();
    if (request.startedAt().isAfter(now)) {
      throw new ApiException.Invalid("started_at", "must not be in the future");
    }
    if (request.endedAt() != null) {
      checkEnd(request.startedAt(), request.endedAt(), now);
    }
    if (store.overlaps(facility, request.startedAt(), request.endedAt())) {
      throw new ApiException.Conflict(
          "Another downtime episode of this facility overlaps this period");
    }
    UUID id = UUID.randomUUID();
    int number = store.nextEpisodeNumber(facility);
    try {
      store.insertEpisode(
          id,
          user.organizationId(),
          facility,
          number,
          request.startedAt(),
          request.endedAt(),
          request.affectedAreas().trim(),
          request.reason().trim(),
          user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict(
          "Another downtime episode was declared for this facility at the same time. Reload.");
    }
    audit(user, facility, "DOWNTIME_DECLARED", "DowntimeEpisode", id, episodeMeta(number));
    return store.episode(id).orElseThrow();
  }

  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_MANAGE + "')")
  @Transactional
  public EpisodeView end(UUID id, EndRequest request) {
    CurrentUser user = CurrentUser.require();
    EpisodeView e = managedEpisode(user, id);
    if (e.status() != EpisodeStatus.ACTIVE) {
      throw new ApiException.InvalidTransition("This episode has already ended");
    }
    checkEnd(e.startedAt(), request.endedAt(), Instant.now());
    boolean later =
        store.entries(id).stream().anyMatch(x -> x.occurredAt().isAfter(request.endedAt()));
    if (later) {
      throw new ApiException.Invalid(
          "ended_at", "is earlier than paper forms already keyed for this episode");
    }
    if (store.endEpisode(id, request.version(), request.endedAt(), user.userId()) == 0) {
      throw new ApiException.Conflict("This episode was changed. Reload it.");
    }
    audit(user, e.facilityId(), "DOWNTIME_ENDED", "DowntimeEpisode", id, episodeMeta(e));
    return store.episode(id).orElseThrow();
  }

  /** Closes an ended episode once every keyed form is reconciled or voided. */
  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_MANAGE + "')")
  @Transactional
  public EpisodeView close(UUID id, CloseRequest request) {
    CurrentUser user = CurrentUser.require();
    EpisodeView e = managedEpisode(user, id);
    store.lockEpisode(id);
    if (e.status() == EpisodeStatus.ACTIVE) {
      throw new ApiException.InvalidTransition("End the episode before closing it");
    }
    if (e.status() == EpisodeStatus.CLOSED) {
      throw new ApiException.InvalidTransition("This episode is already closed");
    }
    long open = store.openEntries(id);
    if (open > 0) {
      throw new ApiException.InvalidTransition(
          open + " paper form(s) still wait for reconciliation. Reconcile or void them first.");
    }
    if (store.closeEpisode(id, request.version(), request.note().trim(), user.userId()) == 0) {
      throw new ApiException.Conflict("This episode was changed. Reload it.");
    }
    audit(user, e.facilityId(), "DOWNTIME_CLOSED", "DowntimeEpisode", id, episodeMeta(e));
    return store.episode(id).orElseThrow();
  }

  // --- numbered forms --------------------------------------------------------------------------

  /** Reserves a block of new form numbers to print; numbers are never reused. */
  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_MANAGE + "')")
  @Transactional
  public FormBatchView issueForms(IssueFormsRequest request) {
    CurrentUser user = CurrentUser.require();
    if (!user.scopeOf(Permissions.DOWNTIME_MANAGE).includes(request.facilityId())) {
      throw new ApiException.NotFound("Facility");
    }
    int last =
        store.reserveNumbers(
            user.organizationId(), request.facilityId(), request.formType(), request.count());
    int first = last - request.count() + 1;
    UUID id = UUID.randomUUID();
    store.insertBatch(
        id,
        user.organizationId(),
        request.facilityId(),
        request.formType(),
        first,
        last,
        user.userId());
    audit(
        user,
        request.facilityId(),
        "DOWNTIME_FORMS_ISSUED",
        "DowntimeFormBatch",
        id,
        Map.of("form_type", request.formType().name(), "count", Integer.toString(request.count())));
    return store.batch(id).orElseThrow();
  }

  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_MANAGE + "')")
  @Transactional(readOnly = true)
  public List<FormBatchView> batches(UUID facilityId) {
    CurrentUser user = CurrentUser.require();
    if (!user.scopeOf(Permissions.DOWNTIME_MANAGE).includes(facilityId)) {
      throw new ApiException.NotFound("Facility");
    }
    return store.batches(facilityId);
  }

  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_MANAGE + "')")
  @Transactional(readOnly = true)
  public FormBatchView batch(UUID id) {
    CurrentUser user = CurrentUser.require();
    return store
        .batch(id)
        .filter(b -> user.scopeOf(Permissions.DOWNTIME_MANAGE).includes(b.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Form batch"));
  }

  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_RECORD + "')")
  @Transactional(readOnly = true)
  public FormOptions formOptions(UUID facilityId) {
    CurrentUser user = CurrentUser.require();
    if (!user.scopeOf(Permissions.DOWNTIME_RECORD).includes(facilityId)) {
      throw new ApiException.NotFound("Facility");
    }
    return new FormOptions(
        store.departments(facilityId), store.catalogItems(), store.paymentMethods());
  }

  // --- keyed forms -----------------------------------------------------------------------------

  @PreAuthorize(ANY)
  @Transactional(readOnly = true)
  public List<EntryView> entries(UUID episodeId) {
    CurrentUser user = CurrentUser.require();
    EpisodeView e = visibleEpisode(user, episodeId);
    List<EntryView> list = store.entries(episodeId);
    audit(
        user,
        e.facilityId(),
        "DOWNTIME_QUEUE_VIEWED",
        "DowntimeEpisode",
        episodeId,
        Map.of("count", Integer.toString(list.size())));
    return list;
  }

  @PreAuthorize(ANY)
  @Transactional(readOnly = true)
  public EntryView entry(UUID id) {
    CurrentUser user = CurrentUser.require();
    EntryView x = visibleEntry(user, id);
    audit(user, x.facilityId(), "DOWNTIME_FORM_VIEWED", "DowntimeEntry", id, entryMeta(x));
    return withEvents(x);
  }

  /**
   * Keys a paper form. Repeating exactly the same keying (an interrupted or double submission)
   * returns the existing entry; a different keying of the same form number is refused.
   */
  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_RECORD + "')")
  @Transactional
  public Recorded record(UUID episodeId, EntryRequest request) {
    CurrentUser user = CurrentUser.require();
    EpisodeView e =
        store
            .episode(episodeId)
            .filter(x -> user.scopeOf(Permissions.DOWNTIME_RECORD).includes(x.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Downtime episode"));
    if (e.status() == EpisodeStatus.CLOSED) {
      throw new ApiException.InvalidTransition("This downtime episode is closed");
    }
    UUID facility = e.facilityId();
    Instant at = request.occurredAt();
    Instant until = e.endedAt() == null ? Instant.now() : e.endedAt();
    if (at.isBefore(e.startedAt()) || at.isAfter(until)) {
      throw new ApiException.Invalid(
          "occurred_at", "must be within the downtime episode (from its start to its end)");
    }
    FormType type = request.formType();
    if (!store.issued(facility, type, request.formNumber())) {
      throw new ApiException.Invalid(
          "form_number", "was not issued for this facility and form type");
    }
    Integer registration = request.registrationFormNumber();
    if (type == FormType.REGISTRATION) {
      if (registration != null) {
        throw new ApiException.Invalid(
            "registration_form_number", "must be empty on a registration form");
      }
    } else if (registration == null) {
      throw new ApiException.Invalid(
          "registration_form_number", "is required: the patient's registration form number");
    } else if (!store.issued(facility, FormType.REGISTRATION, registration)) {
      throw new ApiException.Invalid(
          "registration_form_number", "was not issued as a registration form of this facility");
    }
    String data = json.writeValueAsString(normalized(facility, request));

    var existing = store.liveEntry(facility, type, request.formNumber());
    if (existing.isPresent()) {
      EntryView x = existing.get();
      if (store.sameKeying(x.id(), episodeId, registration, at, data, user.userId())) {
        return new Recorded(x, false);
      }
      throw alreadyKeyed(x);
    }
    UUID id = UUID.randomUUID();
    try {
      store.insertEntry(
          id,
          user.organizationId(),
          facility,
          episodeId,
          type,
          request.formNumber(),
          registration,
          at,
          data,
          user.userId());
    } catch (DuplicateKeyException ex) {
      throw new ApiException.Conflict(
          "Form " + type.label(request.formNumber()) + " was keyed at the same time. Reload.");
    }
    store.insertEvent(user.organizationId(), id, "ENTERED", null, user.userId());
    EntryView created = store.entry(id).orElseThrow();
    audit(user, facility, "DOWNTIME_FORM_KEYED", "DowntimeEntry", id, entryMeta(created));
    return new Recorded(created, true);
  }

  /** Possible existing records for a keyed registration form (needs PATIENT_READ). */
  @PreAuthorize(
      "hasAuthority('"
          + Permissions.DOWNTIME_RECORD
          + "') and hasAuthority('"
          + Permissions.PATIENT_READ
          + "')")
  @Transactional(readOnly = true)
  public CandidatesView candidates(UUID id) {
    CurrentUser user = CurrentUser.require();
    EntryView x = recordableEntry(user, id);
    if (x.formType() != FormType.REGISTRATION) {
      throw new ApiException.InvalidTransition("Only registration forms are matched to patients");
    }
    RegistrationForm form = json.treeToValue(x.data(), RegistrationForm.class);
    List<DuplicateCandidate> candidates =
        patients.candidatesFor(registerRequest(x.facilityId(), form, null));
    PatientSummary written = null;
    if (StringUtils.hasText(form.writtenPatientNumber())) {
      written =
          patients
              .search(
                  new SearchCriteria(
                      form.writtenPatientNumber(), null, null, null, null, null, null, null),
                  0,
                  1)
              .items()
              .stream()
              .findFirst()
              .orElse(null);
    }
    audit(
        user,
        x.facilityId(),
        "DOWNTIME_CANDIDATES_VIEWED",
        "DowntimeEntry",
        id,
        Map.of("candidates", Integer.toString(candidates.size())));
    return new CandidatesView(written, candidates);
  }

  /** First step for registration, order and payment forms; another person approves. */
  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_RECORD + "')")
  @Transactional
  public EntryView propose(UUID id, ProposeRequest request) {
    CurrentUser user = CurrentUser.require();
    store.lockEntry(id);
    EntryView x = recordableEntry(user, id);
    if (!x.formType().needsSecondPerson()) {
      throw new ApiException.InvalidTransition(
          "Triage forms are reconciled by one person: use Reconcile or Void");
    }
    if (x.status() != EntryStatus.PENDING) {
      throw new ApiException.InvalidTransition(
          x.status() == EntryStatus.PROPOSED
              ? "This form already has a proposal waiting for approval"
              : "This form has already been decided");
    }
    ObjectNode proposal = json.createObjectNode();
    proposal.put("action", request.action().name());
    if (request.action() == ProposalAction.APPLY) {
      switch (x.formType()) {
        case REGISTRATION -> proposeRegistration(user, x, request, proposal);
        case PAYMENT -> proposePayment(x, request, proposal);
        default -> {}
      }
    }
    if (store.propose(
            id,
            request.version(),
            json.writeValueAsString(proposal),
            request.note().trim(),
            user.userId())
        == 0) {
      throw new ApiException.Conflict("This form was changed. Reload it.");
    }
    store.insertEvent(user.organizationId(), id, "PROPOSED", request.note().trim(), user.userId());
    Map<String, String> meta = entryMeta(x);
    meta.put("action", request.action().name());
    audit(user, x.facilityId(), "DOWNTIME_RECONCILIATION_PROPOSED", "DowntimeEntry", id, meta);
    return withEvents(store.entry(id).orElseThrow());
  }

  /**
   * Approves another person's proposal: voids the form, or creates the records through the normal
   * services. A failure there (permission, duplicate, closed visit, no open shift…) creates nothing
   * and leaves the proposal waiting.
   */
  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_RECORD + "')")
  @Transactional
  public EntryView approve(UUID id, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    store.lockEntry(id);
    EntryView x = recordableEntry(user, id);
    if (x.status() != EntryStatus.PROPOSED) {
      throw new ApiException.InvalidTransition(
          x.status() == EntryStatus.PENDING
              ? "This form has no proposal to approve"
              : "This form has already been decided");
    }
    if (x.proposedBy().id().equals(user.userId())) {
      throw new ApiException.InvalidTransition(
          "You proposed this, so another person must approve it");
    }
    if (x.version() != request.version()) {
      throw new ApiException.Conflict("This form was changed. Reload it.");
    }
    boolean voiding = "VOID".equals(x.proposal().path("action").asString());
    Applied applied = voiding ? new Applied(null, null, null) : apply(x);
    finish(user, x, EntryStatus.PROPOSED, voiding, applied, request);
    return withEvents(store.entry(id).orElseThrow());
  }

  /** Returns a proposal to the queue (with a note); anyone, including the proposer. */
  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_RECORD + "')")
  @Transactional
  public EntryView sendBack(UUID id, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    store.lockEntry(id);
    EntryView x = recordableEntry(user, id);
    if (x.status() != EntryStatus.PROPOSED) {
      throw new ApiException.InvalidTransition("This form has no proposal to send back");
    }
    if (store.sendBack(id, request.version()) == 0) {
      throw new ApiException.Conflict("This form was changed. Reload it.");
    }
    store.insertEvent(user.organizationId(), id, "SENT_BACK", request.note().trim(), user.userId());
    audit(user, x.facilityId(), "DOWNTIME_PROPOSAL_SENT_BACK", "DowntimeEntry", id, entryMeta(x));
    return withEvents(store.entry(id).orElseThrow());
  }

  /** Triage and vitals: one person records the observations on the reconciled visit. */
  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_RECORD + "')")
  @Transactional
  public EntryView reconcile(UUID id, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    EntryView x = pendingTriage(user, id, request);
    finish(user, x, EntryStatus.PENDING, false, apply(x), request);
    return withEvents(store.entry(id).orElseThrow());
  }

  /** Triage and vitals: one person voids a wrongly keyed or unusable form, with a reason. */
  @PreAuthorize("hasAuthority('" + Permissions.DOWNTIME_RECORD + "')")
  @Transactional
  public EntryView voidEntry(UUID id, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    EntryView x = pendingTriage(user, id, request);
    finish(user, x, EntryStatus.PENDING, true, new Applied(null, null, null), request);
    return withEvents(store.entry(id).orElseThrow());
  }

  /** Reconciled paper forms behind a visit, with their paper times. */
  @PreAuthorize("hasAuthority('" + Permissions.ENCOUNTER_READ + "')")
  @Transactional(readOnly = true)
  public List<DowntimeSource> sources(UUID encounterId) {
    CurrentUser user = CurrentUser.require();
    store
        .encounter(encounterId)
        .filter(e -> user.scopeOf(Permissions.ENCOUNTER_READ).includes(e.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Encounter"));
    return store.sources(encounterId);
  }

  // --- reconciliation steps --------------------------------------------------------------------

  private void proposeRegistration(
      CurrentUser user, EntryView x, ProposeRequest request, ObjectNode proposal) {
    if (!user.scopeOf(Permissions.PATIENT_READ).includes(x.facilityId())) {
      throw new AccessDeniedException("Matching a registration form needs PATIENT_READ");
    }
    if (request.patientId() != null) {
      PatientDetail p = patients.get(request.patientId());
      if (p.status() != PatientStatus.ACTIVE) {
        throw new ApiException.Invalid(
            "patient_id", "is linked to another record; choose the record in use");
      }
      proposal.put("patient_id", p.id().toString());
      if (request.encounterId() != null) {
        EncounterRef enc =
            store
                .encounter(request.encounterId())
                .filter(e -> e.patientId().equals(p.id()) && e.facilityId().equals(x.facilityId()))
                .filter(e -> !"CANCELLED".equals(e.status()))
                .orElseThrow(() ->
                    new ApiException.Invalid(
                        "encounter_id", "is not a visit of this patient at this facility"));
        proposal.put("encounter_id", enc.id().toString());
      }
      return;
    }
    if (request.encounterId() != null) {
      throw new ApiException.Invalid("encounter_id", "can only be used with an existing patient");
    }
    RegistrationForm form = json.treeToValue(x.data(), RegistrationForm.class);
    List<DuplicateCandidate> candidates =
        patients.candidatesFor(registerRequest(x.facilityId(), form, null));
    Set<UUID> reviewed = new HashSet<>(request.reviewedCandidateIds());
    if (!candidates.stream().allMatch(c -> reviewed.contains(c.patient().id()))) {
      throw new ApiException.Conflict(
          "Possible existing records were found. Review every candidate before proposing a new patient.");
    }
    boolean likely = candidates.stream().anyMatch(c -> c.band().name().equals("LIKELY"));
    if (likely && !StringUtils.hasText(request.duplicateReason())) {
      throw new ApiException.Invalid(
          "duplicate_reason", "is required when a likely duplicate exists");
    }
    var ids = proposal.putArray("reviewed_candidate_ids");
    candidates.forEach(c -> ids.add(c.patient().id().toString()));
    if (StringUtils.hasText(request.duplicateReason())) {
      proposal.put("duplicate_reason", request.duplicateReason().trim());
    }
  }

  private void proposePayment(EntryView x, ProposeRequest request, ObjectNode proposal) {
    if (request.invoiceId() == null) {
      throw new ApiException.Invalid("invoice_id", "is required: the visit's issued invoice");
    }
    EntryView registration = registrationOf(x);
    PaymentForm form = json.treeToValue(x.data(), PaymentForm.class);
    checkInvoice(request.invoiceId(), registration.encounterId(), form.currency());
    proposal.put("invoice_id", request.invoiceId().toString());
  }

  private Applied apply(EntryView x) {
    return switch (x.formType()) {
      case REGISTRATION -> applyRegistration(x);
      case TRIAGE -> {
        EntryView reg = registrationOf(x);
        ObservationRequest form = json.treeToValue(x.data(), ObservationRequest.class);
        yield new Applied(
            reg.patientId(), reg.encounterId(), triage.record(reg.encounterId(), form).id());
      }
      case ORDER -> {
        EntryView reg = registrationOf(x);
        OrderForm form = json.treeToValue(x.data(), OrderForm.class);
        var order =
            orders.create(
                reg.encounterId(),
                new CreateOrderRequest(form.catalogItemId(), form.priority(), form.indication()));
        yield new Applied(reg.patientId(), reg.encounterId(), order.id());
      }
      case PAYMENT -> {
        EntryView reg = registrationOf(x);
        PaymentForm form = json.treeToValue(x.data(), PaymentForm.class);
        UUID invoice = UUID.fromString(x.proposal().path("invoice_id").asString());
        checkInvoice(invoice, reg.encounterId(), form.currency());
        // The entry id is the idempotency key: this paper payment can be recorded only once.
        var recorded =
            payments.record(
                invoice,
                new RecordPaymentRequest(
                    x.id(), form.methodId(), form.amount(), "Downtime form " + x.formLabel()));
        yield new Applied(reg.patientId(), reg.encounterId(), recorded.payment().id());
      }
    };
  }

  private Applied applyRegistration(EntryView x) {
    RegistrationForm form = json.treeToValue(x.data(), RegistrationForm.class);
    JsonNode p = x.proposal();
    UUID patientId;
    if (p.hasNonNull("patient_id")) {
      patientId = UUID.fromString(p.get("patient_id").asString());
      if (patients.get(patientId).status() != PatientStatus.ACTIVE) {
        throw new ApiException.InvalidTransition(
            "The chosen patient record has since been linked to another. Send the proposal back.");
      }
    } else {
      List<UUID> reviewed = new java.util.ArrayList<>();
      p.path("reviewed_candidate_ids").forEach(n -> reviewed.add(UUID.fromString(n.asString())));
      String reason =
          p.hasNonNull("duplicate_reason") ? p.get("duplicate_reason").asString() : null;
      RegisterPatientRequest register =
          registerRequest(x.facilityId(), form, new DuplicateReview(reviewed, reason));
      boolean unreviewed =
          patients.candidatesFor(register).stream()
              .anyMatch(c -> !reviewed.contains(c.patient().id()));
      if (unreviewed) {
        throw new ApiException.Conflict(
            "New possible existing records were found since the proposal. Send it back for review.");
      }
      patientId = patients.register(register).id();
    }
    UUID encounterId;
    if (p.hasNonNull("encounter_id")) {
      encounterId = UUID.fromString(p.get("encounter_id").asString());
    } else {
      encounterId =
          encounters
              .open(
                  new OpenEncounterRequest(
                      patientId, x.facilityId(), form.departmentId(), form.encounterType()))
              .encounter()
              .id();
    }
    return new Applied(patientId, encounterId, null);
  }

  private void finish(
      CurrentUser user,
      EntryView x,
      EntryStatus from,
      boolean voiding,
      Applied applied,
      DecisionRequest request) {
    EntryStatus to = voiding ? EntryStatus.VOIDED : EntryStatus.RECONCILED;
    String note = request.note().trim();
    if (store.decide(
            x.id(),
            request.version(),
            from,
            to,
            note,
            user.userId(),
            applied.patientId(),
            applied.encounterId(),
            applied.recordId())
        == 0) {
      throw new ApiException.Conflict("This form was changed. Reload it.");
    }
    store.insertEvent(user.organizationId(), x.id(), to.name(), note, user.userId());
    audit(
        user,
        x.facilityId(),
        voiding ? "DOWNTIME_FORM_VOIDED" : "DOWNTIME_FORM_RECONCILED",
        "DowntimeEntry",
        x.id(),
        entryMeta(x));
  }

  private EntryView pendingTriage(CurrentUser user, UUID id, DecisionRequest request) {
    store.lockEntry(id);
    EntryView x = recordableEntry(user, id);
    if (x.formType().needsSecondPerson()) {
      throw new ApiException.InvalidTransition(
          "This form needs a proposal and a second person's approval");
    }
    if (x.status() != EntryStatus.PENDING) {
      throw new ApiException.InvalidTransition("This form has already been decided");
    }
    if (x.version() != request.version()) {
      throw new ApiException.Conflict("This form was changed. Reload it.");
    }
    return x;
  }

  /** The reconciled registration form of the patient a triage, order or payment form refers to. */
  private EntryView registrationOf(EntryView x) {
    String label = x.registrationFormLabel();
    return store
        .reconciledRegistration(x.facilityId(), x.registrationFormNumber())
        .orElseThrow(() ->
            new ApiException.InvalidTransition(
                "Reconcile registration form " + label + " first: it identifies the patient"));
  }

  private void checkInvoice(UUID invoiceId, UUID encounterId, String currency) {
    InvoiceRef invoice =
        store
            .invoice(invoiceId)
            .filter(i -> i.encounterId().equals(encounterId))
            .orElseThrow(() ->
                new ApiException.Invalid(
                    "invoice_id", "is not an invoice of this patient's visit"));
    if (!"ISSUED".equals(invoice.status())) {
      throw new ApiException.Invalid("invoice_id", "must be an issued invoice");
    }
    if (!invoice.currency().equals(currency)) {
      throw new ApiException.Invalid(
          "invoice_id", "is in " + invoice.currency() + " but the paper receipt is in " + currency);
    }
  }

  // --- keying validation -----------------------------------------------------------------------

  /** The part of the request matching the form type, checked and trimmed. */
  private Object normalized(UUID facility, EntryRequest r) {
    FormType type = r.formType();
    Map<FormType, Object> parts = new LinkedHashMap<>();
    parts.put(FormType.REGISTRATION, r.registration());
    parts.put(FormType.TRIAGE, r.triage());
    parts.put(FormType.ORDER, r.order());
    parts.put(FormType.PAYMENT, r.payment());
    parts.forEach((t, part) -> {
      String field = t.name().toLowerCase(java.util.Locale.ROOT);
      if (t == type && part == null) {
        throw new ApiException.Invalid(field, "is required for this form type");
      }
      if (t != type && part != null) {
        throw new ApiException.Invalid(field, "must be empty for this form type");
      }
    });
    return switch (type) {
      case REGISTRATION -> registration(facility, r.registration());
      case TRIAGE -> triageForm(r.triage());
      case ORDER -> order(r.order());
      case PAYMENT -> payment(r.payment());
    };
  }

  private RegistrationForm registration(UUID facility, RegistrationForm f) {
    if (store.departments(facility).stream().noneMatch(d -> d.id().equals(f.departmentId()))) {
      throw new ApiException.Invalid(
          "registration.department_id", "is not an active department of this facility");
    }
    if (f.birthDate() != null && f.birthDate().isAfter(LocalDate.now(ZoneOffset.MAX))) {
      throw new ApiException.Invalid("registration.birth_date", "must not be in the future");
    }
    if (f.birthDateEstimated() && f.birthDate() == null) {
      throw new ApiException.Invalid("registration.birth_date", "is required when estimated");
    }
    return new RegistrationForm(
        trim(f.familyName()),
        trim(f.givenNames()),
        f.birthDate(),
        f.birthDateEstimated(),
        f.sex(),
        trim(f.phone()),
        trim(f.writtenPatientNumber()),
        f.registrationSource(),
        f.departmentId(),
        f.encounterType());
  }

  private ObservationRequest triageForm(ObservationRequest f) {
    JsonNode tree = json.valueToTree(f);
    boolean any =
        tree.propertyNames().stream()
            .anyMatch(n -> {
              JsonNode v = tree.get(n);
              return !v.isNull() && !(v.isString() && v.asString().isBlank());
            });
    if (!any) {
      throw new ApiException.Invalid("triage", "Key at least one value from the paper form");
    }
    return f;
  }

  private OrderForm order(OrderForm f) {
    if (store.catalogItems().stream().noneMatch(i -> i.id().equals(f.catalogItemId()))) {
      throw new ApiException.Invalid("order.catalog_item_id", "is not an active catalog item");
    }
    return new OrderForm(
        f.catalogItemId(), f.priority(), trim(f.indication()), f.orderedByName().trim());
  }

  private PaymentForm payment(PaymentForm f) {
    if (store.paymentMethods().stream().map(Option::id).noneMatch(f.methodId()::equals)) {
      throw new ApiException.Invalid("payment.method_id", "is not an active payment method");
    }
    Currency currency;
    try {
      currency = Currency.getInstance(f.currency());
    } catch (IllegalArgumentException e) {
      throw new ApiException.Invalid("payment.currency", "is not an ISO 4217 currency");
    }
    int digits = currency.getDefaultFractionDigits();
    BigDecimal amount = f.amount().stripTrailingZeros();
    if (digits >= 0 && amount.scale() > digits) {
      throw new ApiException.Invalid(
          "payment.amount", "has more decimal places than " + f.currency() + " allows");
    }
    return new PaymentForm(f.methodId(), f.amount(), f.currency());
  }

  private static RegisterPatientRequest registerRequest(
      UUID facility, RegistrationForm f, DuplicateReview review) {
    return new RegisterPatientRequest(
        facility,
        f.registrationSource(),
        f.familyName(),
        f.givenNames(),
        f.birthDate(),
        f.birthDateEstimated(),
        f.sex(),
        f.phone(),
        List.of(),
        review);
  }

  // --- access ----------------------------------------------------------------------------------

  private static boolean mayView(CurrentUser user, UUID facility) {
    return user.scopeOf(Permissions.DOWNTIME_MANAGE).includes(facility)
        || user.scopeOf(Permissions.DOWNTIME_RECORD).includes(facility);
  }

  private EpisodeView visibleEpisode(CurrentUser user, UUID id) {
    return store
        .episode(id)
        .filter(e -> mayView(user, e.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Downtime episode"));
  }

  private EpisodeView managedEpisode(CurrentUser user, UUID id) {
    return store
        .episode(id)
        .filter(e -> user.scopeOf(Permissions.DOWNTIME_MANAGE).includes(e.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Downtime episode"));
  }

  private EntryView visibleEntry(CurrentUser user, UUID id) {
    return store
        .entry(id)
        .filter(x -> mayView(user, x.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Downtime form"));
  }

  private EntryView recordableEntry(CurrentUser user, UUID id) {
    return store
        .entry(id)
        .filter(x -> user.scopeOf(Permissions.DOWNTIME_RECORD).includes(x.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Downtime form"));
  }

  private EntryView withEvents(EntryView x) {
    return new EntryView(
        x.id(),
        x.episodeId(),
        x.facilityId(),
        x.formType(),
        x.formNumber(),
        x.formLabel(),
        x.registrationFormNumber(),
        x.registrationFormLabel(),
        x.occurredAt(),
        x.data(),
        x.status(),
        x.needsSecondPerson(),
        x.enteredBy(),
        x.enteredAt(),
        x.proposal(),
        x.proposedBy(),
        x.proposedAt(),
        x.proposalNote(),
        x.decidedBy(),
        x.decidedAt(),
        x.decisionNote(),
        x.patientId(),
        x.encounterId(),
        x.recordId(),
        store.events(x.id()),
        x.version());
  }

  private ApiException alreadyKeyed(EntryView x) {
    return new ApiException.Conflict(
        "Form "
            + x.formLabel()
            + " was already keyed by "
            + x.enteredBy().displayName()
            + ". If it was keyed wrongly, void that entry first.");
  }

  private static void checkEnd(Instant start, Instant end, Instant now) {
    if (end.isAfter(now)) {
      throw new ApiException.Invalid("ended_at", "must not be in the future");
    }
    if (!end.isAfter(start)) {
      throw new ApiException.Invalid("ended_at", "must be after the start");
    }
  }

  private static String trim(String s) {
    return StringUtils.hasText(s) ? s.trim() : null;
  }

  // --- audit -----------------------------------------------------------------------------------

  private static Map<String, String> episodeMeta(int number) {
    return Map.of("episode", Integer.toString(number));
  }

  private static Map<String, String> episodeMeta(EpisodeView e) {
    return episodeMeta(e.episodeNumber());
  }

  /** Form type and printed number only; never keyed values. */
  private static Map<String, String> entryMeta(EntryView x) {
    Map<String, String> meta = new LinkedHashMap<>();
    meta.put("form_type", x.formType().name());
    meta.put("form", x.formLabel());
    return meta;
  }

  /** Audited after commit (also in read-only transactions). */
  private void audit(
      CurrentUser user,
      UUID facilityId,
      String action,
      String resourceType,
      UUID id,
      Map<String, String> metadata) {
    AuditEvent event =
        new AuditEvent(
            user.organizationId(),
            facilityId,
            user.userId(),
            action,
            resourceType,
            id.toString(),
            AuditOutcome.SUCCESS,
            null,
            Instant.now(),
            null,
            Map.copyOf(metadata));
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
