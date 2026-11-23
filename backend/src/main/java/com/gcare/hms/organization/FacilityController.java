package com.gcare.hms.organization;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Facility and department reads (FR-032). Authorization is enforced in {@link
 * FacilityQueryService}.
 */
@RestController
@RequestMapping("/api/v1/facilities")
@Tag(name = "Facilities")
class FacilityController {

  private final FacilityQueryService service;

  FacilityController(FacilityQueryService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List facilities in scope", description = "Requires FACILITY_READ.")
  List<FacilitySummary> list() {
    return service.listFacilities();
  }

  @GetMapping("/{facilityId}/departments")
  @Operation(
      summary = "List departments of a facility",
      description = "Requires FACILITY_READ at that facility; otherwise 404.")
  List<DepartmentSummary> departments(@PathVariable UUID facilityId) {
    return service.listDepartments(facilityId);
  }
}
