package com.gcare.hms.organization;

import java.util.UUID;

/** API view of a facility. */
public record FacilitySummary(
    UUID id, String code, String name, String timezone, UnitStatus status) {

  static FacilitySummary of(Facility facility) {
    return new FacilitySummary(
        facility.getId(),
        facility.getCode(),
        facility.getName(),
        facility.getTimezone().getId(),
        facility.getStatus());
  }
}
