package com.gcare.hms.shared.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Applies the isolation model from migration V1 to every pooled connection on checkout.
 *
 * <ul>
 *   <li>Normal use: {@code SET ROLE gcare_app} and set {@code app.current_organization_id} from
 *       {@link OrganizationContext} (empty when none, which makes tenant rows invisible).
 *   <li>Schema-owner mode: {@code RESET ROLE} so migrations and audited platform operations run as
 *       the login user.
 * </ul>
 *
 * <p>Both values are re-applied on every checkout, so nothing leaks between pooled uses.
 */
public class RowLevelSecurityDataSource extends DelegatingDataSource {

  static final String APP_ROLE = "gcare_app";

  public RowLevelSecurityDataSource(DataSource target) {
    super(target);
  }

  @Override
  public Connection getConnection() throws SQLException {
    return prepare(super.getConnection());
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return prepare(super.getConnection(username, password));
  }

  private static Connection prepare(Connection connection) throws SQLException {
    try {
      boolean schemaOwner = OrganizationContext.isSchemaOwner();
      try (Statement statement = connection.createStatement()) {
        statement.execute(schemaOwner ? "RESET ROLE" : "SET ROLE " + APP_ROLE);
      }
      String organizationId =
          schemaOwner
              ? ""
              : OrganizationContext.currentOrganizationId().map(Object::toString).orElse("");
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT set_config('app.current_organization_id', ?, false)")) {
        statement.setString(1, organizationId);
        statement.execute();
      }
      return connection;
    } catch (SQLException | RuntimeException e) {
      connection.close();
      throw e;
    }
  }
}
