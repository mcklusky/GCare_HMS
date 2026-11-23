package com.gcare.hms.organization;

import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.web.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to the current user's own organization. */
@Service
public class OrganizationQueryService {

  /** API view of an organization. */
  public record OrganizationSummary(UUID id, String code, String name) {}

  private final OrganizationRepository organizations;

  OrganizationQueryService(OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Transactional(readOnly = true)
  public OrganizationSummary currentOrganization() {
    UUID id = CurrentUser.require().organizationId();
    return organizations
        .findById(id)
        .map(o -> new OrganizationSummary(o.getId(), o.getCode(), o.getName()))
        .orElseThrow(() -> new ApiException.NotFound("Organization"));
  }
}
