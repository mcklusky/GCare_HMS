package com.gcare.hms.encounter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Clinical notes and visit diagnoses (task 3.2; FR-008). Fixed sections (OD-10) and free-text
 * diagnoses with codes added later (OD-09); see docs/18 §7.
 */
public final class ClinicalApi {

  private ClinicalApi() {}

  public enum Certainty {
    PROVISIONAL,
    CONFIRMED
  }

  /** Starts a draft. With {@code amends_note_id} it is an amendment and needs a reason. */
  public record CreateNoteRequest(
      @Size(max = 20000) String history,
      @Size(max = 20000) String examination,
      @Size(max = 20000) String assessment,
      @Size(max = 20000) String plan,
      @JsonProperty("amends_note_id") UUID amendsNoteId,
      @Size(max = 500) @JsonProperty("amendment_reason") String amendmentReason) {}

  /** Replaces the draft's sections. */
  public record UpdateNoteRequest(
      long version,
      @Size(max = 20000) String history,
      @Size(max = 20000) String examination,
      @Size(max = 20000) String assessment,
      @Size(max = 20000) String plan) {}

  public record NoteView(
      UUID id,
      @JsonProperty("encounter_id") UUID encounterId,
      String status,
      String history,
      String examination,
      String assessment,
      String plan,
      UserRef author,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("updated_at") Instant updatedAt,
      @JsonProperty("signed_at") Instant signedAt,
      @JsonProperty("amends_note_id") UUID amendsNoteId,
      @JsonProperty("amendment_reason") String amendmentReason,
      /** Signed amendments of this note, oldest first. */
      @JsonProperty("amended_by") List<UUID> amendedBy,
      long version) {}

  public record DiagnosisRequest(
      @NotBlank @Size(max = 500) String description, @NotNull Certainty certainty) {}

  public record DiagnosisView(
      UUID id,
      @JsonProperty("encounter_id") UUID encounterId,
      String description,
      Certainty certainty,
      /** Empty until a coding system is decided (OD-09). */
      String code,
      @JsonProperty("code_system") String codeSystem,
      String status,
      @JsonProperty("recorded_by") UserRef recordedBy,
      @JsonProperty("recorded_at") Instant recordedAt,
      @JsonProperty("error_reason") String errorReason,
      @JsonProperty("error_by") UserRef errorBy,
      @JsonProperty("error_at") Instant errorAt) {}

  public record ErrorRequest(@NotBlank @Size(max = 500) String reason) {}
}
