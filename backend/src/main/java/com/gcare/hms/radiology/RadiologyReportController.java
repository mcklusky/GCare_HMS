package com.gcare.hms.radiology;

import com.gcare.hms.radiology.RadiologyReportApi.AcknowledgeRequest;
import com.gcare.hms.radiology.RadiologyReportApi.CreateReportRequest;
import com.gcare.hms.radiology.RadiologyReportApi.CriticalView;
import com.gcare.hms.radiology.RadiologyReportApi.ImagingResult;
import com.gcare.hms.radiology.RadiologyReportApi.ReportView;
import com.gcare.hms.radiology.RadiologyReportApi.ReportingItem;
import com.gcare.hms.radiology.RadiologyReportApi.SignRequest;
import com.gcare.hms.radiology.RadiologyReportApi.UpdateReportRequest;
import com.gcare.hms.radiology.RadiologyReportApi.VersionRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Radiology reports and critical results (task 6.4; docs/26). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Radiology reports")
class RadiologyReportController {

  private final RadiologyReportService service;

  RadiologyReportController(RadiologyReportService service) {
    this.service = service;
  }

  @GetMapping("/radiology/reporting")
  @Operation(summary = "Examined imaging orders waiting for a report (radiologists)")
  List<ReportingItem> reporting() {
    return service.reportingWorklist();
  }

  @GetMapping("/imaging/orders/{id}/reports")
  @Operation(summary = "Reports of an imaging order", description = "Drafts only for radiologists.")
  List<ReportView> reports(@PathVariable UUID id) {
    return service.reportsOfOrder(id);
  }

  @PostMapping("/imaging/orders/{id}/reports")
  @Operation(summary = "Start a report draft, or an amendment of the signed report (with a reason)")
  ResponseEntity<ReportView> create(
      @PathVariable UUID id, @Valid @RequestBody CreateReportRequest request) {
    ReportView r = service.createDraft(id, request);
    return ResponseEntity.created(URI.create("/api/v1/radiology-reports/" + r.id())).body(r);
  }

  @PutMapping("/radiology-reports/{id}")
  @Operation(summary = "Save your draft")
  ReportView update(@PathVariable UUID id, @Valid @RequestBody UpdateReportRequest request) {
    return service.update(id, request);
  }

  @PostMapping("/radiology-reports/{id}/sign")
  @Operation(
      summary = "Sign your draft",
      description = "A critical report needs the documented direct contact.")
  ReportView sign(@PathVariable UUID id, @Valid @RequestBody SignRequest request) {
    return service.sign(id, request);
  }

  @PostMapping("/radiology-reports/{id}/discard")
  @Operation(summary = "Discard your draft")
  ReportView discard(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
    return service.discard(id, request);
  }

  @GetMapping("/encounters/{id}/imaging-results")
  @Operation(summary = "Imaging orders of a visit with studies and signed reports")
  List<ImagingResult> ofEncounter(@PathVariable UUID id) {
    return service.resultsOfEncounter(id);
  }

  @GetMapping("/patients/{id}/imaging-results")
  @Operation(summary = "Imaging orders of a patient with studies and signed reports")
  List<ImagingResult> ofPatient(@PathVariable UUID id) {
    return service.resultsOfPatient(id);
  }

  @GetMapping("/critical-results")
  @Operation(summary = "Critical results not yet acknowledged, at facilities in your scope")
  List<CriticalView> critical() {
    return service.openCriticalResults();
  }

  @PostMapping("/critical-results/{reportId}/acknowledge")
  @Operation(summary = "Acknowledge a critical result")
  CriticalView acknowledge(
      @PathVariable UUID reportId, @Valid @RequestBody AcknowledgeRequest request) {
    return service.acknowledge(reportId, request);
  }
}
