package com.gcare.hms.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.identity.SystemRole;
import com.gcare.hms.identity.UserDirectory;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** The local profile's synthetic seed applies cleanly on top of the real migrations. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("local")
class LocalSeedTests {

  private static final UUID DEMO_ORG = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID DEMO_FACILITY = UUID.fromString("00000000-0000-4000-8000-000000000101");

  @Autowired private OrganizationRepository organizations;
  @Autowired private DepartmentRepository departments;
  @Autowired private UserDirectory directory;
  @Autowired private JdbcClient jdbc;

  private static final String LOCAL_ISSUER = "http://localhost:8180/realms/gcare";

  @Test
  void seedCreatesDemoOrganizationFacilityAndDepartments() {
    OrganizationContext.runAs(
        DEMO_ORG,
        () -> {
          assertThat(organizations.findById(DEMO_ORG))
              .hasValueSatisfying(org -> assertThat(org.getCode()).isEqualTo("DEMO-ORG"));
          assertThat(departments.findByFacilityIdOrderByCode(DEMO_FACILITY))
              .extracting(Department::getCode)
              .containsExactly("BILL", "OPD", "RAD", "REG");
        });
  }

  @Test
  void seedSystemRolesMatchTheCode() {
    for (SystemRole role : SystemRole.values()) {
      Set<String> seeded =
          OrganizationContext.callAs(
              DEMO_ORG,
              () ->
                  jdbc
                      .sql(
                          "SELECT rp.permission_code FROM role_permissions rp"
                              + " JOIN roles r ON r.id = rp.role_id"
                              + " WHERE r.code = :code AND r.is_system_role")
                      .param("code", role.name())
                      .query(String.class)
                      .list()
                      .stream()
                      .collect(Collectors.toSet()));
      assertThat(seeded).as(role.name()).isEqualTo(role.permissions());
    }
  }

  @Test
  void demoUsersMatchTheLocalKeycloakRealm() {
    CurrentUser admin = load("00000000-0000-4000-8000-000000001001");
    assertThat(admin.username()).isEqualTo("demo.admin");
    assertThat(admin.permissions()).containsAll(SystemRole.ORGANIZATION_ADMIN.permissions());

    assertThat(load("00000000-0000-4000-8000-000000001002").permissions())
        .containsExactly(Permissions.AUDIT_READ);
    assertThat(load("00000000-0000-4000-8000-000000001003").permissions()).isEmpty();
    assertThat(directory.findLink(LOCAL_ISSUER, "00000000-0000-4000-8000-000000001004")).isEmpty();
  }

  private CurrentUser load(String subject) {
    return directory.findLink(LOCAL_ISSUER, subject).flatMap(directory::loadActive).orElseThrow();
  }
}
