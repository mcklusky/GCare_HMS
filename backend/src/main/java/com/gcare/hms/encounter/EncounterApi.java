package com.gcare.hms.encounter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of /api/v1/encounters and triage. snake_case on the wire. */
public final class EncounterApi {

  private EncounterApi() {}

  public enum EncounterType {
    NEW_VISIT,
    FOLLOW_UP
  }

  public enum EncounterStatus {
    WAITING,
    IN_CONSULTATION,
    CLOSED,
    CANCELLED;

    boolean open() {
      return this == WAITING || this == IN_CONSULTATION;
    }
  }

  public record OpenEncounterRequest(
      @NotNull @JsonProperty("patient_id") UUID patientId,
      @NotNull @JsonProperty("facility_id") UUID facilityId,
      @NotNull @JsonProperty("department_id") UUID departmentId,
      @NotNull @JsonProperty("encounter_type") EncounterType encounterType) {}

  public record VersionRequest(long version) {}

  public record TransferRequest(
      long version,
      @NotNull @JsonProperty("department_id") UUID departmentId,
      @NotBlank @Size(max = 500) String reason) {}

  public record ReasonRequest(long version, @NotBlank @Size(max = 500) String reason) {}

  public record DepartmentRef(UUID id, String code, String name) {}

  public record UserRef(UUID id, @JsonProperty("display_name") String displayName) {}

  /**
   * Queue row. {@code urgency}, {@code triaged} and {@code flags} are filled only for users with
   * OBSERVATION_READ.
   */
  public record EncounterSummary(
      UUID id,
      @JsonProperty("facility_id") UUID facilityId,
      DepartmentRef department,
      PatientSummary patient,
      @JsonProperty("encounter_type") EncounterType encounterType,
      EncounterStatus status,
      UserRef clinician,
      @JsonProperty("arrived_at") Instant arrivedAt,
      @JsonProperty("consultation_started_at") Instant consultationStartedAt,
      @JsonProperty("ended_at") Instant endedAt,
      Boolean triaged,
      String urgency,
      List<String> flags,
      long version) {}

  public record EncounterEvent(
      UUID id,
      @JsonProperty("event_type") String eventType,
      @JsonProperty("from_status") EncounterStatus fromStatus,
      @JsonProperty("to_status") EncounterStatus toStatus,
      DepartmentRef department,
      String reason,
      UserRef actor,
      @JsonProperty("occurred_at") Instant occurredAt) {}

  public record EncounterDetail(EncounterSummary encounter, List<EncounterEvent> events) {}

  public record Page<T>(List<T> items, int page, int size, long total) {}

  // --- triage ----------------------------------------------------------------------------------

  /**
   * Triage observations. Units are fixed. The min/max bounds are <em>data-entry limits</em> that
   * catch typing errors (values outside what a living patient can have), not clinical alarm limits;
   * alarms come only from {@link TriageProperties}.
   */
  public record ObservationRequest(
      @DecimalMin("25.0") @DecimalMax("45.0") @JsonProperty("temperature_c")
          BigDecimal temperatureC,
      @Min(20) @Max(300) @JsonProperty("pulse_bpm") Integer pulseBpm,
      @Min(2) @Max(80) @JsonProperty("respiratory_rate") Integer respiratoryRate,
      @Min(40) @Max(300) @JsonProperty("systolic_mmhg") Integer systolicMmhg,
      @Min(20) @Max(200) @JsonProperty("diastolic_mmhg") Integer diastolicMmhg,
      @Min(40) @Max(100) @JsonProperty("spo2_pct") Integer spo2Pct,
      @DecimalMin("0.3") @DecimalMax("400.0") @JsonProperty("weight_kg") BigDecimal weightKg,
      @DecimalMin("20.0") @DecimalMax("250.0") @JsonProperty("height_cm") BigDecimal heightCm,
      @Min(0) @Max(10) @JsonProperty("pain_score") Integer painScore,
      @Size(max = 30) String urgency,
      @Size(max = 500) @JsonProperty("allergy_note") String allergyNote,
      @Size(max = 1000) String notes) {}

  public record Flag(String measure, String direction, BigDecimal value, BigDecimal limit) {}

  public record ObservationView(
      UUID id,
      @JsonProperty("encounter_id") UUID encounterId,
      @JsonProperty("temperature_c") BigDecimal temperatureC,
      @JsonProperty("pulse_bpm") Integer pulseBpm,
      @JsonProperty("respiratory_rate") Integer respiratoryRate,
      @JsonProperty("systolic_mmhg") Integer systolicMmhg,
      @JsonProperty("diastolic_mmhg") Integer diastolicMmhg,
      @JsonProperty("spo2_pct") Integer spo2Pct,
      @JsonProperty("weight_kg") BigDecimal weightKg,
      @JsonProperty("height_cm") BigDecimal heightCm,
      @JsonProperty("pain_score") Integer painScore,
      String urgency,
      @JsonProperty("allergy_note") String allergyNote,
      String notes,
      List<Flag> flags,
      String status,
      @JsonProperty("recorded_by") UserRef recordedBy,
      @JsonProperty("recorded_at") Instant recordedAt,
      @JsonProperty("error_reason") String errorReason,
      @JsonProperty("error_by") UserRef errorBy,
      @JsonProperty("error_at") Instant errorAt) {}

  public record ErrorRequest(@NotBlank @Size(max = 500) String reason) {}

  public record TriageSettings(
      @JsonProperty("urgency_levels") List<TriageProperties.UrgencyLevel> urgencyLevels,
      @JsonProperty("alarm_limits_configured") boolean alarmLimitsConfigured) {}
}
