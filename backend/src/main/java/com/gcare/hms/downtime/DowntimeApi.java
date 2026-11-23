package com.gcare.hms.downtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.encounter.EncounterApi.EncounterType;
import com.gcare.hms.encounter.EncounterApi.ObservationRequest;
import com.gcare.hms.orders.OrderApi.Priority;
import com.gcare.hms.patient.PatientApi.DuplicateCandidate;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import com.gcare.hms.patient.RegistrationSource;
import com.gcare.hms.patient.Sex;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Downtime episodes, numbered paper forms and the reconciliation queue (task 5.3; docs/22). */
public final class DowntimeApi {

  private DowntimeApi() {}

  public enum FormType {
    REGISTRATION("REG"),
    TRIAGE("TRI"),
    ORDER("ORD"),
    PAYMENT("PAY");

    private final String prefix;

    FormType(String prefix) {
      this.prefix = prefix;
    }

    /** Printed form number, e.g. {@code REG-000012}. */
    public String label(int number) {
      return String.format("%s-%06d", prefix, number);
    }

    /** Registrations (patient matching), orders and payments need a second person. */
    public boolean needsSecondPerson() {
      return this != TRIAGE;
    }
  }

  public enum EpisodeStatus {
    ACTIVE,
    ENDED,
    CLOSED
  }

  public enum EntryStatus {
    PENDING,
    PROPOSED,
    RECONCILED,
    VOIDED
  }

  public enum ProposalAction {
    APPLY,
    VOID
  }

  public record UserRef(UUID id, @JsonProperty("display_name") String displayName) {}

  // --- episodes --------------------------------------------------------------------------------

  public record DeclareRequest(
      @NotNull @JsonProperty("facility_id") UUID facilityId,
      @NotNull @JsonProperty("started_at") Instant startedAt,
      @JsonProperty("ended_at") Instant endedAt,
      @NotBlank @Size(max = 500) @JsonProperty("affected_areas") String affectedAreas,
      @NotBlank @Size(max = 500) String reason) {}

  public record EndRequest(long version, @NotNull @JsonProperty("ended_at") Instant endedAt) {}

  public record CloseRequest(long version, @NotBlank @Size(max = 1000) String note) {}

  public record EpisodeView(
      UUID id,
      @JsonProperty("facility_id") UUID facilityId,
      @JsonProperty("episode_number") int episodeNumber,
      @JsonProperty("started_at") Instant startedAt,
      @JsonProperty("ended_at") Instant endedAt,
      @JsonProperty("affected_areas") String affectedAreas,
      String reason,
      EpisodeStatus status,
      @JsonProperty("declared_by") UserRef declaredBy,
      @JsonProperty("declared_at") Instant declaredAt,
      @JsonProperty("closed_by") UserRef closedBy,
      @JsonProperty("closed_at") Instant closedAt,
      @JsonProperty("closing_note") String closingNote,
      /** Number of keyed forms by entry status. */
      Map<EntryStatus, Integer> counts,
      long version) {}

  // --- forms -----------------------------------------------------------------------------------

  public record IssueFormsRequest(
      @NotNull @JsonProperty("facility_id") UUID facilityId,
      @NotNull @JsonProperty("form_type") FormType formType,
      @Min(1) @Max(500) int count) {}

  public record FormBatchView(
      UUID id,
      @JsonProperty("facility_id") UUID facilityId,
      @JsonProperty("facility_code") String facilityCode,
      @JsonProperty("facility_name") String facilityName,
      @JsonProperty("form_type") FormType formType,
      @JsonProperty("first_number") int firstNumber,
      @JsonProperty("last_number") int lastNumber,
      @JsonProperty("first_label") String firstLabel,
      @JsonProperty("last_label") String lastLabel,
      @JsonProperty("issued_by") UserRef issuedBy,
      @JsonProperty("issued_at") Instant issuedAt) {}

  public record Option(UUID id, String code, String name) {}

  /** Choices for keying forms at a facility: active departments, order items, payment methods. */
  public record FormOptions(
      List<Option> departments,
      @JsonProperty("catalog_items") List<Option> catalogItems,
      @JsonProperty("payment_methods") List<Option> paymentMethods) {}

  // --- keyed forms -----------------------------------------------------------------------------

  /** What the registration + visit paper form holds. */
  public record RegistrationForm(
      @Size(max = 100) @JsonProperty("family_name") String familyName,
      @Size(max = 150) @JsonProperty("given_names") String givenNames,
      @JsonProperty("birth_date") LocalDate birthDate,
      @JsonProperty("birth_date_estimated") Boolean birthDateEstimated,
      Sex sex,
      @Size(max = 30) String phone,
      /** A patient number written on the form when the patient already had one. */
      @Size(max = 30) @JsonProperty("written_patient_number") String writtenPatientNumber,
      @NotNull @JsonProperty("registration_source") RegistrationSource registrationSource,
      @NotNull @JsonProperty("department_id") UUID departmentId,
      @NotNull @JsonProperty("encounter_type") EncounterType encounterType) {

    public RegistrationForm {
      birthDateEstimated = Boolean.TRUE.equals(birthDateEstimated);
      sex = sex == null ? Sex.UNKNOWN : sex;
    }
  }

  public record OrderForm(
      @NotNull @JsonProperty("catalog_item_id") UUID catalogItemId,
      @NotNull Priority priority,
      @Size(max = 1000) String indication,
      /** The clinician's name as written on the paper order. */
      @NotBlank @Size(max = 150) @JsonProperty("ordered_by_name") String orderedByName) {}

  public record PaymentForm(
      @NotNull @JsonProperty("method_id") UUID methodId,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
      @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

  /** A keyed paper form. Exactly the part matching {@code form_type} is given. */
  public record EntryRequest(
      @NotNull @JsonProperty("form_type") FormType formType,
      @Min(1) @JsonProperty("form_number") int formNumber,
      /** For triage, order and payment forms: the patient's registration form number. */
      @Min(1) @JsonProperty("registration_form_number") Integer registrationFormNumber,
      @NotNull @JsonProperty("occurred_at") Instant occurredAt,
      @Valid RegistrationForm registration,
      @Valid ObservationRequest triage,
      @Valid OrderForm order,
      @Valid PaymentForm payment) {}

  public record EntryEvent(
      @JsonProperty("event_type") String eventType,
      String note,
      UserRef by,
      @JsonProperty("occurred_at") Instant occurredAt) {}

  public record EntryView(
      UUID id,
      @JsonProperty("episode_id") UUID episodeId,
      @JsonProperty("facility_id") UUID facilityId,
      @JsonProperty("form_type") FormType formType,
      @JsonProperty("form_number") int formNumber,
      @JsonProperty("form_label") String formLabel,
      @JsonProperty("registration_form_number") Integer registrationFormNumber,
      @JsonProperty("registration_form_label") String registrationFormLabel,
      @JsonProperty("occurred_at") Instant occurredAt,
      JsonNode data,
      EntryStatus status,
      @JsonProperty("needs_second_person") boolean needsSecondPerson,
      @JsonProperty("entered_by") UserRef enteredBy,
      @JsonProperty("entered_at") Instant enteredAt,
      JsonNode proposal,
      @JsonProperty("proposed_by") UserRef proposedBy,
      @JsonProperty("proposed_at") Instant proposedAt,
      @JsonProperty("proposal_note") String proposalNote,
      @JsonProperty("decided_by") UserRef decidedBy,
      @JsonProperty("decided_at") Instant decidedAt,
      @JsonProperty("decision_note") String decisionNote,
      @JsonProperty("patient_id") UUID patientId,
      @JsonProperty("encounter_id") UUID encounterId,
      @JsonProperty("record_id") UUID recordId,
      /** Only on single-entry reads. */
      List<EntryEvent> events,
      long version) {}

  // --- reconciliation --------------------------------------------------------------------------

  /**
   * A proposal for a registration, order or payment form; a different person approves it.
   * Registration: {@code patient_id} of an existing patient, or none to register a new one (then
   * every possible duplicate must be listed as reviewed, and likely duplicates need {@code
   * duplicate_reason}); optionally {@code encounter_id} of the patient's existing visit instead of
   * opening a new one. Payment: the issued {@code invoice_id} of the patient's visit.
   */
  public record ProposeRequest(
      long version,
      @NotNull ProposalAction action,
      @NotBlank @Size(max = 500) String note,
      @JsonProperty("patient_id") UUID patientId,
      @JsonProperty("reviewed_candidate_ids") List<UUID> reviewedCandidateIds,
      @Size(max = 500) @JsonProperty("duplicate_reason") String duplicateReason,
      @JsonProperty("encounter_id") UUID encounterId,
      @JsonProperty("invoice_id") UUID invoiceId) {

    public ProposeRequest {
      reviewedCandidateIds = reviewedCandidateIds == null ? List.of() : reviewedCandidateIds;
    }
  }

  public record DecisionRequest(long version, @NotBlank @Size(max = 500) String note) {}

  /** Possible matches for a keyed registration form. */
  public record CandidatesView(
      @JsonProperty("written_number_match") PatientSummary writtenNumberMatch,
      List<DuplicateCandidate> candidates) {}

  /** A reconciled paper form behind part of a visit, shown on the visit (paper time kept here). */
  public record DowntimeSource(
      @JsonProperty("entry_id") UUID entryId,
      @JsonProperty("form_type") FormType formType,
      @JsonProperty("form_label") String formLabel,
      @JsonProperty("episode_number") int episodeNumber,
      @JsonProperty("occurred_at") Instant occurredAt,
      @JsonProperty("reconciled_at") Instant reconciledAt,
      @JsonProperty("record_id") UUID recordId) {}
}
