package com.gcare.hms.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.organization.FacilityQueryService;
import com.gcare.hms.organization.FacilitySummary;
import com.gcare.hms.organization.OrganizationQueryService;
import com.gcare.hms.organization.OrganizationQueryService.OrganizationSummary;
import com.gcare.hms.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.SortedSet;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user and what they may do (docs/05-API.md, {@code GET /me}). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Session")
class MeController {

  /** Response of {@code GET /api/v1/me}. Contains no secrets or tokens. */
  record MeResponse(
      UUID id,
      String username,
      @JsonProperty("display_name") String displayName,
      OrganizationSummary organization,
      SortedSet<String> permissions,
      List<FacilitySummary> facilities) {}

  private final OrganizationQueryService organizations;
  private final FacilityQueryService facilities;

  MeController(OrganizationQueryService organizations, FacilityQueryService facilities) {
    this.organizations = organizations;
    this.facilities = facilities;
  }

  @GetMapping("/me")
  @Operation(
      summary = "Current user",
      description =
          "The signed-in user, their organization, permission codes, and the active facilities"
              + " where they hold any permission.")
  MeResponse me() {
    CurrentUser user = CurrentUser.require();
    return new MeResponse(
        user.userId(),
        user.username(),
        user.displayName(),
        organizations.currentOrganization(),
        user.permissions(),
        facilities.listWorkingFacilities());
  }
}
