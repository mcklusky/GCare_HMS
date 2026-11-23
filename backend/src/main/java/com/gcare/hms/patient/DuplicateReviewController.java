package com.gcare.hms.patient;

import com.gcare.hms.patient.PatientApi.DuplicateFlagView;
import com.gcare.hms.patient.PatientApi.Page;
import com.gcare.hms.patient.PatientApi.ResolveFlagRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Duplicate review queue (docs/17-PATIENT-IDENTITY-POLICY.md §3). Needs PATIENT_DUPLICATE_REVIEW.
 */
@RestController
@RequestMapping("/api/v1/patient-duplicate-flags")
@Tag(name = "Duplicate review")
class DuplicateReviewController {

  private final PatientService service;

  DuplicateReviewController(PatientService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(
      summary = "List review items",
      description = "status: OPEN (default), NOT_DUPLICATE, LINK_REQUESTED.")
  Page<DuplicateFlagView> list(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.duplicateFlags(status, page, size);
  }

  @PostMapping("/{id}/not-duplicate")
  @Operation(
      summary = "Decide: different people",
      description = "Needs a note. The person who raised the item cannot decide it.")
  DuplicateFlagView notDuplicate(
      @PathVariable UUID id, @Valid @RequestBody ResolveFlagRequest request) {
    return service.markNotDuplicate(id, request);
  }
}
