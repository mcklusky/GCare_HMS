package com.gcare.hms.identity;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Creates the {@link SystemRole}s in an organization and assigns them. Runs inside the caller's
 * transaction and organization (or schema-owner) context.
 */
@Service
public class SystemRoles {

  private final JdbcClient jdbc;

  SystemRoles(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Ensures every system role exists with its permissions; returns role IDs. Idempotent. */
  public Map<SystemRole, UUID> ensure(UUID organizationId) {
    Map<SystemRole, UUID> ids = new EnumMap<>(SystemRole.class);
    for (SystemRole role : SystemRole.values()) {
      jdbc.sql(
              """
              INSERT INTO roles (id, organization_id, code, name, description, is_system_role, status)
              VALUES (:id, :org, :code, :name, :description, true, 'ACTIVE')
              ON CONFLICT (organization_id, code) DO NOTHING
              """)
          .param("id", UUID.randomUUID())
          .param("org", organizationId)
          .param("code", role.name())
          .param("name", role.displayName())
          .param("description", role.description())
          .update();
      UUID roleId =
          jdbc.sql("SELECT id FROM roles WHERE organization_id = :org AND code = :code")
              .param("org", organizationId)
              .param("code", role.name())
              .query(UUID.class)
              .single();
      for (String permission : role.permissions()) {
        jdbc.sql(
                """
                INSERT INTO role_permissions (organization_id, role_id, permission_code)
                VALUES (:org, :role, :permission)
                ON CONFLICT DO NOTHING
                """)
            .param("org", organizationId)
            .param("role", roleId)
            .param("permission", permission)
            .update();
      }
      ids.put(role, roleId);
    }
    return ids;
  }

  /** Assigns a role across the whole organization. */
  public UUID assignOrganizationWide(
      UUID organizationId, UUID userId, UUID roleId, UUID assignedBy) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO user_role_assignments (id, organization_id, user_id, role_id, assigned_by)
            VALUES (:id, :org, :user, :role, :by)
            """)
        .param("id", id)
        .param("org", organizationId)
        .param("user", userId)
        .param("role", roleId)
        .param("by", assignedBy)
        .update();
    return id;
  }
}
