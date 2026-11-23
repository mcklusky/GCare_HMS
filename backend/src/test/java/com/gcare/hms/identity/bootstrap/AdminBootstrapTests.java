package com.gcare.hms.identity.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.identity.UserDirectory;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.testsupport.IdentityFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** First-administrator bootstrap: creates the organization and an admin linked to Keycloak. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "gcare.bootstrap.enabled=true",
      "gcare.bootstrap.organization.code=BOOT-ORG",
      "gcare.bootstrap.organization.name=Bootstrap Test Org (synthetic)",
      "gcare.bootstrap.organization.country-code=LR",
      "gcare.bootstrap.facility.code=BOOT-FAC",
      "gcare.bootstrap.facility.name=Bootstrap Test Facility (synthetic)",
      "gcare.bootstrap.facility.timezone=Africa/Monrovia",
      "gcare.bootstrap.admin.username=boot.admin",
      "gcare.bootstrap.admin.display-name=Bootstrap Admin (synthetic)",
      "gcare.bootstrap.admin.issuer=https://idp.test.invalid/realms/gcare",
      "gcare.bootstrap.admin.subject=boot-admin-subject"
    })
class AdminBootstrapTests {

  @Autowired private AdminBootstrap bootstrap;
  @Autowired private UserDirectory directory;
  @Autowired private IdentityFixtures fixtures;

  @Test
  void startupCreatedAnAdministratorWhoCanSignIn() {
    CurrentUser admin =
        directory
            .findLink("https://idp.test.invalid/realms/gcare", "boot-admin-subject")
            .flatMap(directory::loadActive)
            .orElseThrow();

    assertThat(admin.username()).isEqualTo("boot.admin");
    assertThat(admin.permissions())
        .containsExactlyInAnyOrder(
            Permissions.FACILITY_READ,
            Permissions.FACILITY_MANAGE,
            Permissions.USER_READ,
            Permissions.USER_MANAGE);
    assertThat(admin.scopeOf(Permissions.FACILITY_READ).allFacilities()).isTrue();
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE organization_id = ?"
                    + " AND action IN ('ORGANIZATION_CREATED', 'FACILITY_CREATED',"
                    + " 'ADMIN_BOOTSTRAPPED')",
                admin.organizationId()))
        .isEqualTo(3);
  }

  @Test
  void runningAgainChangesNothing() {
    AdminBootstrap.Result again = bootstrap.bootstrap();

    assertThat(again.organizationCreated()).isFalse();
    assertThat(again.facilityCreated()).isFalse();
    assertThat(again.adminCreated()).isFalse();
    assertThat(fixtures.count("SELECT count(*) FROM organizations WHERE code = 'BOOT-ORG'"))
        .isEqualTo(1);
    assertThat(fixtures.count("SELECT count(*) FROM users WHERE username = 'boot.admin'"))
        .isEqualTo(1);
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM roles r JOIN organizations o ON o.id = r.organization_id"
                    + " WHERE o.code = 'BOOT-ORG'"))
        .isEqualTo(com.gcare.hms.identity.SystemRole.values().length);
  }
}
