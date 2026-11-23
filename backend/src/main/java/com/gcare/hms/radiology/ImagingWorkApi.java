package com.gcare.hms.radiology;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Imaging worklist, steps and study linking (task 6.3; docs/25). */
public final class ImagingWorkApi {

  private ImagingWorkApi() {}

  public enum StudyStatus {
    UNMATCHED,
    PROPOSED,
    LINKED
  }

  public record UserRef(UUID id, @JsonProperty("display_name") String displayName) {}

  /** A study in the archive as the HMS knows it (metadata only). */
  public record StudyView(
      UUID id,
      @JsonProperty("study_instance_uid") String studyInstanceUid,
      @JsonProperty("accession_number") String accessionNumber,
      @JsonProperty("dicom_patient_id") String dicomPatientId,
      List<String> modalities,
      @JsonProperty("study_date") LocalDate studyDate,
      String description,
      @JsonProperty("series_count") Integer seriesCount,
      @JsonProperty("instance_count") Integer instanceCount,
      @JsonProperty("first_seen_at") Instant firstSeenAt,
      StudyStatus status,
      /** Why it did not link automatically (queue only). */
      @JsonProperty("unmatched_reason") String unmatchedReason,
      @JsonProperty("order_id") UUID orderId,
      @JsonProperty("link_method") String linkMethod,
      @JsonProperty("linked_at") Instant linkedAt,
      @JsonProperty("linked_by") UserRef linkedBy,
      @JsonProperty("proposed_order_id") UUID proposedOrderId,
      @JsonProperty("proposed_accession_number") String proposedAccessionNumber,
      @JsonProperty("proposed_by") UserRef proposedBy,
      @JsonProperty("proposal_note") String proposalNote,
      @JsonProperty("viewer_url") String viewerUrl,
      long version) {}

  /** One imaging order on the radiographer's worklist. */
  public record WorklistItem(
      @JsonProperty("order_id") UUID orderId,
      @JsonProperty("encounter_id") UUID encounterId,
      @JsonProperty("facility_id") UUID facilityId,
      @JsonProperty("accession_number") String accessionNumber,
      PatientSummary patient,
      @JsonProperty("item_code") String itemCode,
      @JsonProperty("item_name") String itemName,
      String priority,
      String indication,
      @JsonProperty("order_status") String orderStatus,
      @JsonProperty("requested_at") Instant requestedAt,
      @JsonProperty("arrived_at") Instant arrivedAt,
      @JsonProperty("arrived_by") UserRef arrivedBy,
      @JsonProperty("examined_at") Instant examinedAt,
      @JsonProperty("examined_by") UserRef examinedBy,
      /** ORDERED, ARRIVED, EXAMINED, IMAGES_AVAILABLE or CANCELLED. */
      String step,
      List<StudyView> studies,
      long version) {}

  public record StepRequest(long version) {}

  public record ProposeLinkRequest(
      long version,
      @NotBlank @Size(max = 16) @JsonProperty("accession_number") String accessionNumber,
      @NotBlank @Size(max = 500) String note) {}

  public record DecisionRequest(long version, @NotBlank @Size(max = 500) String note) {}

  public record StudyEvent(
      @JsonProperty("event_type") String eventType,
      @JsonProperty("order_id") UUID orderId,
      String note,
      UserRef by,
      @JsonProperty("occurred_at") Instant occurredAt) {}

  public record SyncResult(
      @JsonProperty("studies_seen") int studiesSeen,
      @JsonProperty("new_studies") int newStudies,
      @JsonProperty("linked") int linked,
      @JsonProperty("unmatched") int unmatched) {}
}
