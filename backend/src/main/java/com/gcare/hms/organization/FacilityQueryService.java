package com.gcare.hms.organization;

import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.FacilityScope;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to facilities and departments. Row-level security limits data to the user's
 * organization; these methods narrow it further to the facilities where the user's permission
 * applies.
 */
@Service
public class FacilityQueryService {

  private final FacilityRepository facilities;
  private final DepartmentRepository departments;

  FacilityQueryService(FacilityRepository facilities, DepartmentRepository departments) {
    this.facilities = facilities;
    this.departments = departments;
  }

  /** Facilities the user may view (FACILITY_READ), ordered by code. */
  @PreAuthorize("hasAuthority('" + Permissions.FACILITY_READ + "')")
  @Transactional(readOnly = true)
  public List<FacilitySummary> listFacilities() {
    return inScope(CurrentUser.require().scopeOf(Permissions.FACILITY_READ));
  }

  /**
   * Departments of one facility (FACILITY_READ at that facility). A facility outside the user's
   * scope answers 404, so its existence is not disclosed.
   */
  @PreAuthorize("hasAuthority('" + Permissions.FACILITY_READ + "')")
  @Transactional(readOnly = true)
  public List<DepartmentSummary> listDepartments(UUID facilityId) {
    FacilityScope scope = CurrentUser.require().scopeOf(Permissions.FACILITY_READ);
    if (!scope.includes(facilityId) || facilities.findById(facilityId).isEmpty()) {
      throw new ApiException.NotFound("Facility");
    }
    return departments.findByFacilityIdOrderByCode(facilityId).stream()
        .map(DepartmentSummary::of)
        .toList();
  }

  /**
   * Active facilities where the current user holds any permission: the facilities they can choose
   * to work in. Needs no specific permission; it describes the user's own access.
   */
  @Transactional(readOnly = true)
  public List<FacilitySummary> listWorkingFacilities() {
    List<FacilitySummary> all = inScope(CurrentUser.require().anyScope());
    return all.stream().filter(f -> f.status() == UnitStatus.ACTIVE).toList();
  }

  /**
   * For other modules: the facility if it exists in the current organization and is active. Does
   * not check the caller's permissions; the calling service must do that.
   */
  @Transactional(readOnly = true)
  public java.util.Optional<FacilitySummary> findActive(UUID facilityId) {
    return facilities
        .findById(facilityId)
        .filter(f -> f.getStatus() == UnitStatus.ACTIVE)
        .map(FacilitySummary::of);
  }

  private List<FacilitySummary> inScope(FacilityScope scope) {
    if (scope.isEmpty()) {
      return List.of();
    }
    return facilities.findAll(Sort.by("code")).stream()
        .filter(f -> scope.includes(f.getId()))
        .map(FacilitySummary::of)
        .toList();
  }
}
