package com.gcare.hms.patient;

import com.gcare.hms.patient.PatientApi.LinkDecisionRequest;
import com.gcare.hms.patient.PatientApi.LinkRequest;
import com.gcare.hms.patient.PatientApi.Page;
import com.gcare.hms.patient.PatientApi.PatientLinkView;
import com.gcare.hms.patient.PatientApi.UnlinkRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Linking duplicate records (docs/17-PATIENT-IDENTITY-POLICY.md §5). Never merges. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Patient links")
class PatientLinkController {

  private final PatientLinkService service;

  PatientLinkController(PatientLinkService service) {
    this.service = service;
  }

  @PostMapping("/patient-links")
  @Operation(
      summary = "Request a link",
      description =
          "Needs PATIENT_LINK_REQUEST and a reason. With flag_id and flag_version it also closes"
              + " that review item (needs PATIENT_DUPLICATE_REVIEW; not by whoever raised it).")
  ResponseEntity<PatientLinkView> request(@Valid @RequestBody LinkRequest request) {
    PatientLinkView created = service.request(request);
    return ResponseEntity.created(URI.create("/api/v1/patient-links/" + created.id()))
        .body(created);
  }

  @GetMapping("/patient-links")
  @Operation(
      summary = "List links",
      description =
          "Needs PATIENT_LINK_APPROVE. status: REQUESTED (default), APPROVED, REJECTED,"
              + " UNLINKED.")
  Page<PatientLinkView> list(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(status, page, size);
  }

  @GetMapping("/patients/{id}/links")
  @Operation(summary = "Link history of a patient", description = "Either side, newest first.")
  List<PatientLinkView> forPatient(@PathVariable UUID id) {
    return service.forPatient(id);
  }

  @PostMapping("/patient-links/{id}/approve")
  @Operation(
      summary = "Approve a link",
      description =
          "Not by the requester. Needs a note; override_reason when verified identifiers differ.")
  PatientLinkView approve(@PathVariable UUID id, @Valid @RequestBody LinkDecisionRequest request) {
    return service.approve(id, request);
  }

  @PostMapping("/patient-links/{id}/reject")
  @Operation(summary = "Reject a link", description = "Not by the requester. Needs a note.")
  PatientLinkView reject(@PathVariable UUID id, @Valid @RequestBody LinkDecisionRequest request) {
    return service.reject(id, request);
  }

  @PostMapping("/patient-links/{id}/unlink")
  @Operation(
      summary = "Reverse a link",
      description = "Needs PATIENT_LINK_APPROVE and a reason. Both records are in use again.")
  PatientLinkView unlink(@PathVariable UUID id, @Valid @RequestBody UnlinkRequest request) {
    return service.unlink(id, request);
  }
}
