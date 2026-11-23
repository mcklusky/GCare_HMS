package com.gcare.hms.reporting;

import com.gcare.hms.reporting.ReportApi.Dashboard;
import com.gcare.hms.reporting.ReportApi.ReportInfo;
import com.gcare.hms.reporting.ReportApi.ReportView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operational reports, dashboard and CSV export (task 5.1; docs/20). */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
class ReportController {

  private final ReportService service;

  ReportController(ReportService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "Reports you can open")
  List<ReportInfo> available() {
    return service.available();
  }

  @GetMapping("/dashboard")
  @Operation(
      summary = "Today's numbers at a facility",
      description = "Revenue tiles need REPORT_REVENUE_READ.")
  Dashboard dashboard(@RequestParam(name = "facility_id") UUID facilityId) {
    return service.dashboard(facilityId);
  }

  @GetMapping("/{report}")
  @Operation(
      summary = "Run a report",
      description = "One facility; facility-local days; at most 366 days.")
  ReportView report(
      @PathVariable String report,
      @RequestParam(name = "facility_id", required = false) UUID facilityId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return service.report(report, facilityId, from, to);
  }

  @GetMapping("/{report}/export")
  @Operation(summary = "Download a report as CSV", description = "Needs REPORT_EXPORT; audited.")
  ResponseEntity<byte[]> export(
      @PathVariable String report,
      @RequestParam(name = "facility_id", required = false) UUID facilityId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    String csv = service.export(report, facilityId, from, to);
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("gcare-" + report + "-" + from + "-to-" + to + ".csv")
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(csv.getBytes(StandardCharsets.UTF_8));
  }
}
