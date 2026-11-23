package com.gcare.hms.identity;

import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.PermissionGrant;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns an identity-provider sign-in (issuer + subject) into a GCare {@link CurrentUser}.
 *
 * <p>An identity grants nothing by itself. Access needs a link to a GCare user, that user and their
 * organization must be ACTIVE, and permissions come only from currently active role assignments.
 */
@Service
public class UserDirectory {

  /** A linked identity: which user and organization it belongs to. */
  public record IdentityLink(UUID userId, UUID organizationId) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate readOnly;
  private final TransactionTemplate readWrite;

  UserDirectory(JdbcClient jdbc, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.readOnly = new TransactionTemplate(transactions);
    this.readOnly.setReadOnly(true);
    this.readWrite = new TransactionTemplate(transactions);
  }

  /** Looks up the link without any organization context (see app_find_user_by_identity). */
  public Optional<IdentityLink> findLink(String issuer, String subject) {
    if (issuer == null || subject == null) {
      return Optional.empty();
    }
    return jdbc.sql("SELECT user_id, organization_id FROM app_find_user_by_identity(:iss, :sub)")
        .param("iss", issuer)
        .param("sub", subject)
        .query(
            (rs, i) -> new IdentityLink(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
        .optional();
  }

  /** The user with current permissions, or empty if the user or organization is not active. */
  public Optional<CurrentUser> loadActive(IdentityLink link) {
    return OrganizationContext.callAs(
        link.organizationId(), () -> readOnly.execute(status -> loadActiveInContext(link)));
  }

  /** Records a successful sign-in. */
  public void recordSignIn(CurrentUser user) {
    OrganizationContext.runAs(
        user.organizationId(),
        () ->
            readWrite.executeWithoutResult(status ->
                jdbc.sql("UPDATE users SET last_login_at = now() WHERE id = :id")
                    .param("id", user.userId())
                    .update()));
  }

  private Optional<CurrentUser> loadActiveInContext(IdentityLink link) {
    Optional<UserRow> row =
        jdbc.sql(
                """
                SELECT u.username, u.display_name
                FROM users u
                JOIN organizations o ON o.id = u.organization_id
                WHERE u.id = :id AND u.status = 'ACTIVE' AND o.status = 'ACTIVE'
                """)
            .param("id", link.userId())
            .query((rs, i) -> new UserRow(rs.getString(1), rs.getString(2)))
            .optional();
    if (row.isEmpty()) {
      return Optional.empty();
    }
    List<PermissionGrant> grants =
        jdbc.sql(
                """
                SELECT DISTINCT rp.permission_code, a.facility_id, a.department_id
                FROM user_role_assignments a
                JOIN roles r ON r.id = a.role_id AND r.status = 'ACTIVE'
                JOIN role_permissions rp ON rp.role_id = r.id
                LEFT JOIN facilities f ON f.id = a.facility_id
                WHERE a.user_id = :id
                  AND a.active_from <= now()
                  AND (a.active_until IS NULL OR a.active_until > now())
                  AND (a.facility_id IS NULL OR f.status = 'ACTIVE')
                """)
            .param("id", link.userId())
            .query((rs, i) ->
                new PermissionGrant(
                    rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)))
            .list();
    Set<PermissionGrant> grantSet = new HashSet<>(grants);
    return Optional.of(
        new CurrentUser(
            link.userId(),
            link.organizationId(),
            row.get().username(),
            row.get().displayName(),
            grantSet));
  }

  private record UserRow(String username, String displayName) {}
}
