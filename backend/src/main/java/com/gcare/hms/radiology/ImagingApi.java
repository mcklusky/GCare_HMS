package com.gcare.hms.radiology;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Imaging archive status and study look-up for evaluation and support (task 6.1; docs/24). */
public final class ImagingApi {

  private ImagingApi() {}

  public record ArchiveStatusView(
      boolean configured,
      boolean reachable,
      @JsonProperty("latency_ms") long latencyMs,
      String message,
      @JsonProperty("viewer_configured") boolean viewerConfigured,
      @JsonProperty("checked_at") Instant checkedAt) {}

  public record StudyView(
      @JsonProperty("study_instance_uid") String studyInstanceUid,
      @JsonProperty("accession_number") String accessionNumber,
      @JsonProperty("patient_id") String patientId,
      List<String> modalities,
      @JsonProperty("study_date") LocalDate studyDate,
      String description,
      @JsonProperty("series_count") Integer seriesCount,
      @JsonProperty("instance_count") Integer instanceCount,
      @JsonProperty("viewer_url") String viewerUrl) {}
}
