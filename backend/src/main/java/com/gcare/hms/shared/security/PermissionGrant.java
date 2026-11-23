package com.gcare.hms.shared.security;

import java.util.Objects;
import java.util.UUID;

/**
 * One permission held by the current user, from an active role assignment.
 *
 * @param facilityId {@code null} when the assignment is organization-wide
 * @param departmentId optional department within {@code facilityId}
 */
public record PermissionGrant(String permission, UUID facilityId, UUID departmentId) {

  public PermissionGrant {
    Objects.requireNonNull(permission, "permission");
    if (departmentId != null && facilityId == null) {
      throw new IllegalArgumentException("A department grant needs its facility");
    }
  }

  public boolean organizationWide() {
    return facilityId == null;
  }
}
