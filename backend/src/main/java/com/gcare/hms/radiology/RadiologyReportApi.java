package com.gcare.hms.radiology;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import com.gcare.hms.radiology.ImagingWorkApi.StudyView;
import com.gcare.hms.radiology.ImagingWorkApi.UserRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Radiology reports and critical results (task 6.4; docs/26). */
public final class RadiologyReportApi {

  private RadiologyReportApi() {}

  public enum ReportStatus {
    DRAFT,
    SIGNED,
    DISCARDED
  }

  public enum CommunicationMethod {
    PHONE,
    IN_PERSON,
    OTHER
  }

  /** Report sections (free text). Findings and impression are required to sign. */
  public record Sections(
      @Size(max = 20000) String technique,
      @Size(max = 20000) String comparison,
      @Size(max = 20000) String findings,
      @Size(max = 20000) String impression,
      Boolean critical) {}

  /** New draft; {@code amendment_reason} when amending the order's latest signed report. */
  public record CreateReportRequest(
      @Valid Sections sections,
      @Size(max = 1000) @JsonProperty("amendment_reason") String amendmentReason) {}

  public record UpdateReportRequest(long version, @NotNull @Valid Sections sections) {}

  /** Documented direct contact; required to sign a report marked critical. */
  public record Communication(
      @NotBlank @Size(max = 200) @JsonProperty("communicated_to") String communicatedTo,
      @NotNull CommunicationMethod method,
      @NotNull @JsonProperty("communicated_at") Instant communicatedAt,
      @Size(max = 1000) String note) {}

  public record SignRequest(long version, @Valid Communication communication) {}

  public record VersionRequest(long version) {}

  public record AcknowledgeRequest(long version, @Size(max = 1000) String note) {}

  public record CriticalView(
      @JsonProperty("report_id") UUID reportId,
      @JsonProperty("order_id") UUID orderId,
      @JsonProperty("facility_id") UUID facilityId,
      @JsonProperty("encounter_id") UUID encounterId,
      @JsonProperty("accession_number") String accessionNumber,
      @JsonProperty("item_name") String itemName,
      PatientSummary patient,
      @JsonProperty("flagged_by") UserRef flaggedBy,
      @JsonProperty("flagged_at") Instant flaggedAt,
      @JsonProperty("communicated_to") String communicatedTo,
      @JsonProperty("communication_method") CommunicationMethod method,
      @JsonProperty("communicated_at") Instant communicatedAt,
      @JsonProperty("communication_note") String communicationNote,
      @JsonProperty("acknowledged_by") UserRef acknowledgedBy,
      @JsonProperty("acknowledged_at") Instant acknowledgedAt,
      @JsonProperty("acknowledgement_note") String acknowledgementNote,
      long version) {}

  public record ReportView(
      UUID id,
      @JsonProperty("order_id") UUID orderId,
      ReportStatus status,
      String technique,
      String comparison,
      String findings,
      String impression,
      boolean critical,
      UserRef author,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("updated_at") Instant updatedAt,
      @JsonProperty("signed_at") Instant signedAt,
      @JsonProperty("amends_report_id") UUID amendsReportId,
      @JsonProperty("amendment_reason") String amendmentReason,
      @JsonProperty("critical_result") CriticalView criticalResult,
      long version) {}

  /** An imaging order as clinicians see it: step, studies (viewer links) and the signed report. */
  public record ImagingResult(
      @JsonProperty("order_id") UUID orderId,
      @JsonProperty("encounter_id") UUID encounterId,
      @JsonProperty("accession_number") String accessionNumber,
      @JsonProperty("item_name") String itemName,
      @JsonProperty("requested_at") Instant requestedAt,
      String step,
      /** NOT_YET_REPORTED, REPORTED or AMENDED. */
      @JsonProperty("report_state") String reportState,
      List<StudyView> studies,
      /** The current signed report (the latest amendment, if any). */
      ReportView report,
      /** Earlier signed versions, newest first. */
      @JsonProperty("earlier_reports") List<ReportView> earlierReports) {}

  /** An examined imaging order waiting for its report, for radiologists. */
  public record ReportingItem(
      @JsonProperty("order_id") UUID orderId,
      @JsonProperty("accession_number") String accessionNumber,
      PatientSummary patient,
      @JsonProperty("item_name") String itemName,
      String priority,
      String indication,
      @JsonProperty("examined_at") Instant examinedAt,
      List<StudyView> studies,
      @JsonProperty("draft_id") UUID draftId,
      @JsonProperty("draft_author") UserRef draftAuthor,
      @JsonProperty("signed_report_id") UUID signedReportId) {}
}
