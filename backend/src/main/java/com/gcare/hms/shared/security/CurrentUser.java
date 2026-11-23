package com.gcare.hms.shared.security;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The signed-in GCare user for this request, loaded from the database on every request (so
 * disabling an account or ending a role assignment takes effect immediately).
 *
 * <p>Services enforce authorization with it: {@code @PreAuthorize("hasAuthority('X')")} for "holds
 * X somewhere", then {@link #scopeOf(String)} to limit data to the facilities where X applies.
 */
public record CurrentUser(
    UUID userId,
    UUID organizationId,
    String username,
    String displayName,
    Set<PermissionGrant> grants) {

  public CurrentUser {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(organizationId, "organizationId");
    grants = Set.copyOf(grants);
  }

  /** The current user, or an authentication error if the request is not signed in. */
  public static CurrentUser require() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
      return user;
    }
    throw new AuthenticationCredentialsNotFoundException("No signed-in user");
  }

  /** Permission codes held anywhere (organization-wide or at any facility). */
  public SortedSet<String> permissions() {
    return grants.stream()
        .map(PermissionGrant::permission)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  public boolean hasPermission(String permission) {
    return grants.stream().anyMatch(g -> g.permission().equals(permission));
  }

  /** Facilities where {@code permission} applies. */
  public FacilityScope scopeOf(String permission) {
    Set<UUID> facilities = new HashSet<>();
    for (PermissionGrant grant : grants) {
      if (!grant.permission().equals(permission)) {
        continue;
      }
      if (grant.organizationWide()) {
        return FacilityScope.ALL;
      }
      facilities.add(grant.facilityId());
    }
    return facilities.isEmpty() ? FacilityScope.NONE : new FacilityScope(false, facilities);
  }

  /** Facilities where the user holds any permission at all (their working context). */
  public FacilityScope anyScope() {
    Set<UUID> facilities = new HashSet<>();
    for (PermissionGrant grant : grants) {
      if (grant.organizationWide()) {
        return FacilityScope.ALL;
      }
      facilities.add(grant.facilityId());
    }
    return facilities.isEmpty() ? FacilityScope.NONE : new FacilityScope(false, facilities);
  }
}
