package com.gcare.hms.patient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.patient.DuplicateScorer.Band;
import com.gcare.hms.patient.DuplicateScorer.Evidence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes of /api/v1/patients. Field names are snake_case on the wire. */
public final class PatientApi {

  private PatientApi() {}

  /**
   * Registration request. Every demographic field is optional so an unknown patient can be
   * registered when care must proceed (FR-004); such records are flagged {@code
   * details_incomplete}.
   */
  public record RegisterPatientRequest(
      @NotNull @JsonProperty("registration_facility_id") UUID registrationFacilityId,
      @NotNull @JsonProperty("registration_source") RegistrationSource registrationSource,
      @Size(max = 100) @JsonProperty("family_name") String familyName,
      @Size(max = 150) @JsonProperty("given_names") String givenNames,
      @JsonProperty("birth_date") LocalDate birthDate,
      @JsonProperty("birth_date_estimated") Boolean birthDateEstimated,
      Sex sex,
      @Size(max = 30) String phone,
      @Valid @Size(max = 5) List<IdentifierInput> identifiers,
      @Valid @JsonProperty("duplicate_review") DuplicateReview duplicateReview) {

    public RegisterPatientRequest {
      identifiers = identifiers == null ? List.of() : identifiers;
      sex = sex == null ? Sex.UNKNOWN : sex;
      birthDateEstimated = Boolean.TRUE.equals(birthDateEstimated);
    }
  }

  public record IdentifierInput(
      @NotNull IdentifierType type,
      @NotBlank @Size(max = 50) String value,
      @Size(max = 100) String issuer,
      Boolean verified) {

    public IdentifierInput {
      verified = Boolean.TRUE.equals(verified);
    }
  }

  /**
   * The clerk's answer to the duplicate candidates returned by a previous 409: which candidates
   * they reviewed, and (required for likely duplicates) why this is a different person.
   */
  public record DuplicateReview(
      @JsonProperty("reviewed_candidate_ids") List<UUID> reviewedCandidateIds,
      @Size(max = 500) String reason) {

    public DuplicateReview {
      reviewedCandidateIds = reviewedCandidateIds == null ? List.of() : reviewedCandidateIds;
    }
  }

  public record IdentifierView(
      UUID id,
      IdentifierType type,
      String value,
      String issuer,
      boolean verified,
      String source,
      @JsonProperty("created_at") Instant createdAt) {}

  public record PatientDetail(
      UUID id,
      @JsonProperty("patient_number") String patientNumber,
      PatientStatus status,
      @JsonProperty("family_name") String familyName,
      @JsonProperty("given_names") String givenNames,
      @JsonProperty("birth_date") LocalDate birthDate,
      @JsonProperty("birth_date_estimated") boolean birthDateEstimated,
      Sex sex,
      String phone,
      @JsonProperty("details_incomplete") boolean detailsIncomplete,
      @JsonProperty("registration_facility_id") UUID registrationFacilityId,
      @JsonProperty("registration_source") RegistrationSource registrationSource,
      List<IdentifierView> identifiers,
      @JsonProperty("created_at") Instant createdAt,
      long version) {}

  /** Search/candidate row: enough to recognise the person, no contact or identifier values. */
  public record PatientSummary(
      UUID id,
      @JsonProperty("patient_number") String patientNumber,
      PatientStatus status,
      @JsonProperty("family_name") String familyName,
      @JsonProperty("given_names") String givenNames,
      @JsonProperty("birth_date") LocalDate birthDate,
      @JsonProperty("birth_date_estimated") boolean birthDateEstimated,
      Sex sex,
      @JsonProperty("details_incomplete") boolean detailsIncomplete) {}

  public record DuplicateCandidate(
      PatientSummary patient, int score, Band band, List<Evidence> evidence) {}

  public record Page<T>(List<T> items, int page, int size, long total) {}

  /** 409 body when possible duplicates must be reviewed first: the standard error + candidates. */
  public record DuplicateCandidatesError(
      String code,
      String message,
      @JsonProperty("field_errors") Map<String, List<String>> fieldErrors,
      @JsonProperty("correlation_id") String correlationId,
      List<DuplicateCandidate> candidates) {}
}
