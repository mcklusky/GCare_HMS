package com.gcare.hms.radiology;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.radiology.ImagingApi.ArchiveStatusView;
import com.gcare.hms.radiology.ImagingApi.StudyView;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Archive connection check and study look-up by accession number, for the archive evaluation and
 * for support (task 6.1; docs/24). Results include the patient ID the modality wrote, so every
 * look-up is audited (criteria type and result count, never values).
 */
@Service
public class ImagingArchiveService {

  private static final Pattern ACCESSION = Pattern.compile("^[A-Za-z0-9._-]{1,16}$");

  private final ImagingArchive archive;
  private final AuditService audit;
  private final UUID archiveOrganization;

  ImagingArchiveService(
      ImagingArchive archive,
      AuditService audit,
      @Value("${gcare.imaging.archive.organization-id:#{null}}") UUID archiveOrganization) {
    this.archive = archive;
    this.audit = audit;
    this.archiveOrganization = archiveOrganization;
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_ARCHIVE_ADMIN + "')")
  public ArchiveStatusView status() {
    ImagingService.requireArchiveFor(archiveOrganization, CurrentUser.require().organizationId());
    ImagingArchive.Status s = archive.status();
    return new ArchiveStatusView(
        s.configured(),
        s.reachable(),
        s.latency().toMillis(),
        s.message(),
        archive.viewerUrl("1.2.3").isPresent(),
        Instant.now());
  }

  @PreAuthorize("hasAuthority('" + Permissions.IMAGING_ARCHIVE_ADMIN + "')")
  public List<StudyView> studiesByAccession(String accessionNumber) {
    CurrentUser user = CurrentUser.require();
    ImagingService.requireArchiveFor(archiveOrganization, user.organizationId());
    // DICOM AccessionNumber is SH: at most 16 characters.
    if (accessionNumber == null || !ACCESSION.matcher(accessionNumber.trim()).matches()) {
      throw new ApiException.Invalid(
          "accession_number", "must be 1-16 letters, digits, dots, dashes or underscores");
    }
    List<ImagingArchive.Study> studies;
    try {
      studies = archive.studiesByAccession(accessionNumber.trim());
    } catch (ImagingArchive.UnavailableException e) {
      throw new ApiException.Unavailable("ARCHIVE_UNAVAILABLE", e.getMessage());
    }
    audit.record(
        new AuditEvent(
            user.organizationId(),
            null,
            user.userId(),
            "IMAGING_ARCHIVE_QUERIED",
            "ImagingArchive",
            "studies",
            AuditOutcome.SUCCESS,
            null,
            Instant.now(),
            null,
            Map.of("criteria", "accession_number", "results", Integer.toString(studies.size()))));
    return studies.stream()
        .map(s ->
            new StudyView(
                s.studyInstanceUid(),
                s.accessionNumber(),
                s.patientId(),
                s.modalities(),
                s.studyDate(),
                s.description(),
                s.seriesCount(),
                s.instanceCount(),
                archive.viewerUrl(s.studyInstanceUid()).map(URI::toString).orElse(null)))
        .toList();
  }
}
