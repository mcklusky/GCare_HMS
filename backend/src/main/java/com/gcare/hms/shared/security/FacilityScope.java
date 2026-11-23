package com.gcare.hms.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * Where one permission applies: every facility of the user's organization, or a fixed set.
 * Organization isolation itself is enforced by row-level security; this narrows it to facilities.
 */
public record FacilityScope(boolean allFacilities, Set<UUID> facilityIds) {

  public static final FacilityScope NONE = new FacilityScope(false, Set.of());
  public static final FacilityScope ALL = new FacilityScope(true, Set.of());

  public FacilityScope {
    facilityIds = Set.copyOf(facilityIds);
  }

  public boolean includes(UUID facilityId) {
    return allFacilities || facilityIds.contains(facilityId);
  }

  public boolean isEmpty() {
    return !allFacilities && facilityIds.isEmpty();
  }
}
