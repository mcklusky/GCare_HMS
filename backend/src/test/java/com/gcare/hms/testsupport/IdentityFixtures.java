package com.gcare.hms.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import com.gcare.hms.shared.tenancy.OrganizationContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Creates synthetic organizations, facilities, users and role grants directly in the database (as
 * the schema owner), and signs MockMvc requests in as those users.
 */
@Component
public class IdentityFixtures {

  public static final String ISSUER = "https://idp.test.invalid/realms/gcare";

  /** A GCare user linked to a (fake) identity-provider account. */
  public record TestUser(UUID userId, UUID organizationId, String issuer, String subject) {

    /** Signs a MockMvc request in as this user, as Keycloak would after a successful login. */
    public RequestPostProcessor signedIn() {
      return oidcLogin()
          .clientRegistration(OfflineIdentityProviderConfiguration.registration())
          .idToken(token -> token.issuer(issuer).subject(subject));
    }
  }

  private final JdbcClient jdbc;

  public IdentityFixtures(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public UUID organization() {
    return organization("ACTIVE");
  }

  public UUID organization(String status) {
    UUID id = UUID.randomUUID();
    asOwner(
        "INSERT INTO organizations (id, code, name, country_code, status, hosting_model)"
            + " VALUES (?, ?, 'Test Org (synthetic)', 'LR', ?, 'DEDICATED')",
        id,
        "ORG-" + id.toString().substring(0, 8),
        status);
    return id;
  }

  public UUID facility(UUID organizationId, String code) {
    return facility(organizationId, code, "ACTIVE");
  }

  public UUID facility(UUID organizationId, String code, String status) {
    UUID id = UUID.randomUUID();
    asOwner(
        "INSERT INTO facilities (id, organization_id, code, name, timezone, status)"
            + " VALUES (?, ?, ?, 'Test Facility (synthetic)', 'UTC', ?)",
        id,
        organizationId,
        code,
        status);
    return id;
  }

  public UUID department(UUID organizationId, UUID facilityId, String code) {
    UUID id = UUID.randomUUID();
    asOwner(
        "INSERT INTO departments (id, organization_id, facility_id, code, name, status)"
            + " VALUES (?, ?, ?, ?, 'Test Department (synthetic)', 'ACTIVE')",
        id,
        organizationId,
        facilityId,
        code);
    return id;
  }

  /** An ACTIVE user linked to a fresh identity. */
  public TestUser user(UUID organizationId) {
    return user(organizationId, "ACTIVE");
  }

  public TestUser user(UUID organizationId, String status) {
    UUID userId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    asOwner(
        "INSERT INTO users (id, organization_id, username, display_name, status)"
            + " VALUES (?, ?, ?, 'Test User (synthetic)', ?)",
        userId,
        organizationId,
        "user-" + userId.toString().substring(0, 8),
        status);
    link(organizationId, userId, ISSUER, subject);
    return new TestUser(userId, organizationId, ISSUER, subject);
  }

  public void link(UUID organizationId, UUID userId, String issuer, String subject) {
    asOwner(
        "INSERT INTO user_identities (id, organization_id, user_id, issuer, subject)"
            + " VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        organizationId,
        userId,
        issuer,
        subject);
  }

  /** A role with exactly {@code permissions}, in the user's organization. */
  public UUID role(UUID organizationId, String... permissions) {
    UUID roleId = UUID.randomUUID();
    asOwner(
        "INSERT INTO roles (id, organization_id, code, name, status)"
            + " VALUES (?, ?, ?, 'Test Role (synthetic)', 'ACTIVE')",
        roleId,
        organizationId,
        "ROLE_" + roleId.toString().substring(0, 8).toUpperCase().replace('-', '_'));
    for (String permission : permissions) {
      asOwner(
          "INSERT INTO role_permissions (organization_id, role_id, permission_code)"
              + " VALUES (?, ?, ?)",
          organizationId,
          roleId,
          permission);
    }
    return roleId;
  }

  /** Assigns a new role with {@code permissions}; {@code facilityId} null = organization-wide. */
  public UUID grant(TestUser user, UUID facilityId, String... permissions) {
    UUID roleId = role(user.organizationId(), permissions);
    UUID assignmentId = UUID.randomUUID();
    asOwner(
        "INSERT INTO user_role_assignments (id, organization_id, user_id, role_id, facility_id)"
            + " VALUES (?, ?, ?, ?, ?)",
        assignmentId,
        user.organizationId(),
        user.userId(),
        roleId,
        facilityId);
    return assignmentId;
  }

  public void endAssignment(UUID assignmentId) {
    asOwner("UPDATE user_role_assignments SET active_until = now() WHERE id = ?", assignmentId);
  }

  public void setUserStatus(TestUser user, String status) {
    asOwner("UPDATE users SET status = ? WHERE id = ?", status, user.userId());
  }

  public void setOrganizationStatus(UUID organizationId, String status) {
    asOwner("UPDATE organizations SET status = ? WHERE id = ?", status, organizationId);
  }

  public void setRoleStatusForAssignment(UUID assignmentId, String status) {
    asOwner(
        "UPDATE roles SET status = ? WHERE id ="
            + " (SELECT role_id FROM user_role_assignments WHERE id = ?)",
        status,
        assignmentId);
  }

  /** Audit actions recorded for an actor, oldest first. */
  public java.util.List<String> auditActions(UUID actorId) {
    return OrganizationContext.callAsSchemaOwner(() ->
        jdbc.sql("SELECT action FROM audit_events WHERE actor_id = ? ORDER BY recorded_at, id")
            .param(actorId)
            .query(String.class)
            .list());
  }

  public long count(String sql, Object... params) {
    return OrganizationContext.callAsSchemaOwner(() -> {
      var spec = jdbc.sql(sql);
      for (Object p : params) {
        spec = spec.param(p);
      }
      return spec.query(Long.class).single();
    });
  }

  private void asOwner(String sql, Object... params) {
    OrganizationContext.callAsSchemaOwner(() -> {
      var spec = jdbc.sql(sql);
      for (Object p : params) {
        spec = spec.param(p);
      }
      return spec.update();
    });
  }
}
