package com.gcare.hms.organization;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** API view of a department. */
public record DepartmentSummary(
    UUID id,
    @JsonProperty("facility_id") UUID facilityId,
    String code,
    String name,
    UnitStatus status) {

  static DepartmentSummary of(Department department) {
    return new DepartmentSummary(
        department.getId(),
        department.getFacilityId(),
        department.getCode(),
        department.getName(),
        department.getStatus());
  }
}
