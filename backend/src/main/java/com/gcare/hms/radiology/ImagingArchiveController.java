package com.gcare.hms.radiology;

import com.gcare.hms.radiology.ImagingApi.ArchiveStatusView;
import com.gcare.hms.radiology.ImagingApi.StudyView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Imaging archive evaluation and support (task 6.1; docs/24). Needs IMAGING_ARCHIVE_ADMIN. */
@RestController
@RequestMapping("/api/v1/imaging/archive")
@Tag(name = "Imaging archive")
class ImagingArchiveController {

  private final ImagingArchiveService service;

  ImagingArchiveController(ImagingArchiveService service) {
    this.service = service;
  }

  @GetMapping("/status")
  @Operation(summary = "Whether the imaging archive is configured and answering")
  ArchiveStatusView status() {
    return service.status();
  }

  @GetMapping("/studies")
  @Operation(
      summary = "Studies in the archive with an accession number",
      description = "Metadata only (no images); audited.")
  List<StudyView> studies(@RequestParam("accession_number") String accessionNumber) {
    return service.studiesByAccession(accessionNumber);
  }
}
