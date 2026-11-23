package com.gcare.hms.identity.bootstrap;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.identity.SystemRole;
import com.gcare.hms.identity.SystemRoles;
import com.gcare.hms.organization.OrganizationProvisioning;
import com.gcare.hms.organization.OrganizationProvisioning.Provisioned;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Creates an organization, its first facility, the system roles and the first administrator, then
 * links that administrator to an existing Keycloak account (task 1.3, admin bootstrap).
 *
 * <p>Runs at start-up only when {@code gcare.bootstrap.enabled=true}. It is a platform operation:
 * it runs as the schema owner, in one transaction, and is audited. It is idempotent: running it
 * again with the same values changes nothing. It never creates Keycloak accounts or passwords.
 */
@Component
@ConditionalOnBooleanProperty("gcare.bootstrap.enabled")
@EnableConfigurationProperties(BootstrapProperties.class)
class AdminBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

  /** What the run did. */
  record Result(
      UUID organizationId,
      boolean organizationCreated,
      UUID facilityId,
      boolean facilityCreated,
      UUID adminUserId,
      boolean adminCreated) {}

  private final BootstrapProperties properties;
  private final OrganizationProvisioning organizations;
  private final SystemRoles systemRoles;
  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;
  private final AuditService audit;

  AdminBootstrap(
      BootstrapProperties properties,
      OrganizationProvisioning organizations,
      SystemRoles systemRoles,
      JdbcClient jdbc,
      PlatformTransactionManager transactions,
      AuditService audit) {
    this.properties = properties;
    this.organizations = organizations;
    this.systemRoles = systemRoles;
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactions);
    this.audit = audit;
  }

  @Override
  public void run(ApplicationArguments args) {
    Result result = bootstrap();
    log.info(
        "Bootstrap finished: organization {} ({}), facility {} ({}), administrator {} ({})",
        result.organizationId(),
        result.organizationCreated() ? "created" : "existing",
        result.facilityId(),
        result.facilityCreated() ? "created" : "existing",
        result.adminUserId(),
        result.adminCreated() ? "created" : "existing");
  }

  Result bootstrap() {
    validate();
    Result result =
        OrganizationContext.callAsSchemaOwner(() -> transaction.execute(status -> provision()));
    OrganizationContext.runAs(result.organizationId(), () -> audit(result));
    return result;
  }

  private Result provision() {
    var org = properties.organization();
    Provisioned organization =
        organizations.ensureOrganization(
            org.code(), org.name(), org.countryCode(), org.hostingModel());
    var fac = properties.facility();
    Provisioned facility =
        organizations.ensureFacility(
            organization.id(), fac.code(), fac.name(), ZoneId.of(fac.timezone()));
    var roles = systemRoles.ensure(organization.id());

    var admin = properties.admin();
    var existing =
        jdbc.sql(
                "SELECT user_id, organization_id FROM user_identities"
                    + " WHERE issuer = :iss AND subject = :sub")
            .param("iss", admin.issuer())
            .param("sub", admin.subject())
            .query((rs, i) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)})
            .optional();
    if (existing.isPresent()) {
      if (!existing.get()[1].equals(organization.id())) {
        throw new IllegalStateException(
            "This Keycloak account is already linked to a user in another organization");
      }
      return new Result(
          organization.id(),
          organization.created(),
          facility.id(),
          facility.created(),
          existing.get()[0],
          false);
    }

    boolean usernameTaken =
        jdbc.sql("SELECT count(*) FROM users WHERE organization_id = :org AND username = :u")
                .param("org", organization.id())
                .param("u", admin.username())
                .query(Long.class)
                .single()
            > 0;
    if (usernameTaken) {
      throw new IllegalStateException(
          "Username already exists in this organization but is not linked to this Keycloak"
              + " account; link it through user administration instead");
    }

    UUID userId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO users (id, organization_id, username, display_name, email, status)
            VALUES (:id, :org, :username, :display, :email, 'ACTIVE')
            """)
        .param("id", userId)
        .param("org", organization.id())
        .param("username", admin.username())
        .param("display", admin.displayName())
        .param("email", StringUtils.hasText(admin.email()) ? admin.email() : null)
        .update();
    jdbc.sql(
            """
            INSERT INTO user_identities (id, organization_id, user_id, issuer, subject)
            VALUES (:id, :org, :user, :iss, :sub)
            """)
        .param("id", UUID.randomUUID())
        .param("org", organization.id())
        .param("user", userId)
        .param("iss", admin.issuer())
        .param("sub", admin.subject())
        .update();
    systemRoles.assignOrganizationWide(
        organization.id(), userId, roles.get(SystemRole.ORGANIZATION_ADMIN), null);
    return new Result(
        organization.id(), organization.created(), facility.id(), facility.created(), userId, true);
  }

  private void audit(Result result) {
    Map<String, String> source = Map.of("source", "bootstrap");
    if (result.organizationCreated()) {
      audit.record(
          new AuditEvent(
              result.organizationId(),
              null,
              null,
              "ORGANIZATION_CREATED",
              "Organization",
              result.organizationId().toString(),
              AuditOutcome.SUCCESS,
              null,
              null,
              null,
              source));
    }
    if (result.facilityCreated()) {
      audit.record(
          new AuditEvent(
              result.organizationId(),
              result.facilityId(),
              null,
              "FACILITY_CREATED",
              "Facility",
              result.facilityId().toString(),
              AuditOutcome.SUCCESS,
              null,
              null,
              null,
              source));
    }
    if (result.adminCreated()) {
      audit.record(
          new AuditEvent(
              result.organizationId(),
              null,
              null,
              "ADMIN_BOOTSTRAPPED",
              "User",
              result.adminUserId().toString(),
              AuditOutcome.SUCCESS,
              null,
              null,
              null,
              source));
    }
  }

  private void validate() {
    if (properties.organization() == null
        || properties.facility() == null
        || properties.admin() == null) {
      throw new IllegalStateException(
          "gcare.bootstrap needs organization, facility and admin settings");
    }
    var org = properties.organization();
    require("gcare.bootstrap.organization.code", org.code());
    require("gcare.bootstrap.organization.name", org.name());
    require("gcare.bootstrap.organization.country-code", org.countryCode());
    var fac = properties.facility();
    require("gcare.bootstrap.facility.code", fac.code());
    require("gcare.bootstrap.facility.name", fac.name());
    require("gcare.bootstrap.facility.timezone", fac.timezone());
    ZoneId.of(fac.timezone()); // fails fast on an unknown zone
    var admin = properties.admin();
    require("gcare.bootstrap.admin.username", admin.username());
    require("gcare.bootstrap.admin.display-name", admin.displayName());
    require("gcare.bootstrap.admin.issuer", admin.issuer());
    require("gcare.bootstrap.admin.subject", admin.subject());
  }

  private static void require(String property, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalStateException(property + " is required when gcare.bootstrap.enabled=true");
    }
  }
}
