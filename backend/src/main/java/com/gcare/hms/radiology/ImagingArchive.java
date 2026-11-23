package com.gcare.hms.radiology;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The external DICOM archive, seen only through study metadata (ADR-004; docs/24). Vendor neutral:
 * the pilot archive is not selected yet (OD-14), so the implementation speaks standard DICOMweb
 * (QIDO-RS). Images never pass through or are stored by the HMS; viewing opens the archive's viewer
 * by link.
 */
public interface ImagingArchive {

  /** False until {@code gcare.imaging.archive.base-url} is set. */
  boolean configured();

  /** Whether the archive answered a minimal query, and how fast. Never throws. */
  Status status();

  /** Studies with exactly this accession number. */
  List<Study> studiesByAccession(String accessionNumber);

  /** Studies dated within the range (inclusive), for reconciliation sweeps. */
  List<Study> studiesBetween(LocalDate from, LocalDate to);

  /** Link that opens the configured viewer on the study, if a viewer is configured. */
  Optional<URI> viewerUrl(String studyInstanceUid);

  record Status(boolean configured, boolean reachable, Duration latency, String message) {}

  /**
   * Study metadata needed to match a study to an order: accession number and the patient ID the
   * modality wrote (DICOM 0010,0020). Names and other demographics are not read.
   */
  record Study(
      String studyInstanceUid,
      String accessionNumber,
      String patientId,
      List<String> modalities,
      LocalDate studyDate,
      String description,
      Integer seriesCount,
      Integer instanceCount) {}

  /** The archive did not answer or answered with an error. */
  class UnavailableException extends RuntimeException {
    public UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
