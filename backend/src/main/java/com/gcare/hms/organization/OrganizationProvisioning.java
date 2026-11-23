package com.gcare.hms.organization;

import com.gcare.hms.shared.tenancy.OrganizationContext;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Platform operation: creates an organization and its first facility. Only callable as the schema
 * owner ({@link OrganizationContext#callAsSchemaOwner}), inside the caller's transaction, and only
 * from audited platform tooling such as the admin bootstrap. Idempotent by code.
 */
@Service
public class OrganizationProvisioning {

  /** Result of an "ensure" call. */
  public record Provisioned(UUID id, boolean created) {}

  private final JdbcClient jdbc;

  OrganizationProvisioning(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Provisioned ensureOrganization(
      String code, String name, String countryCode, HostingModel hostingModel) {
    requireSchemaOwner();
    Optional<UUID> existing =
        jdbc.sql("SELECT id FROM organizations WHERE code = :code")
            .param("code", code)
            .query(UUID.class)
            .optional();
    if (existing.isPresent()) {
      return new Provisioned(existing.get(), false);
    }
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO organizations (id, code, name, country_code, status, hosting_model)
            VALUES (:id, :code, :name, :country, 'ACTIVE', :hosting)
            """)
        .param("id", id)
        .param("code", code)
        .param("name", name)
        .param("country", countryCode)
        .param("hosting", hostingModel.name())
        .update();
    return new Provisioned(id, true);
  }

  public Provisioned ensureFacility(
      UUID organizationId, String code, String name, ZoneId timezone) {
    requireSchemaOwner();
    Optional<UUID> existing =
        jdbc.sql("SELECT id FROM facilities WHERE organization_id = :org AND code = :code")
            .param("org", organizationId)
            .param("code", code)
            .query(UUID.class)
            .optional();
    if (existing.isPresent()) {
      return new Provisioned(existing.get(), false);
    }
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO facilities (id, organization_id, code, name, timezone, status)
            VALUES (:id, :org, :code, :name, :tz, 'ACTIVE')
            """)
        .param("id", id)
        .param("org", organizationId)
        .param("code", code)
        .param("name", name)
        .param("tz", timezone.getId())
        .update();
    return new Provisioned(id, true);
  }

  private static void requireSchemaOwner() {
    if (!OrganizationContext.isSchemaOwner()) {
      throw new IllegalStateException("Organization provisioning is a platform operation");
    }
  }
}
