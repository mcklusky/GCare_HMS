package com.gcare.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.identity.UserDirectory.IdentityLink;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import com.gcare.hms.testsupport.IdentityFixtures;
import com.gcare.hms.testsupport.IdentityFixtures.TestUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Database guarantees behind sign-in and authorization (migration V2). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdentitySchemaTests {

  @Autowired private JdbcClient jdbc;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private UserDirectory directory;

  @Test
  void permissionCatalogMatchesTheCode() {
    List<String> codes = jdbc.sql("SELECT code FROM permissions").query(String.class).list();

    assertThat(codes).containsExactlyInAnyOrderElementsOf(Permissions.ALL);
  }

  @Test
  void applicationRoleCannotReadIdentityLinksDirectly() {
    UUID org = fixtures.organization();
    fixtures.user(org);

    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                org,
                () -> jdbc.sql("SELECT count(*) FROM user_identities").query(Long.class).single()))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  void identityLookupReturnsOnlyTheExactMatch() {
    UUID org = fixtures.organization();
    TestUser user = fixtures.user(org);

    assertThat(directory.findLink(user.issuer(), user.subject()))
        .contains(new IdentityLink(user.userId(), org));
    assertThat(directory.findLink(user.issuer(), user.subject() + "x")).isEmpty();
    assertThat(directory.findLink(user.issuer() + "/", user.subject())).isEmpty();
  }

  @Test
  void usersAndRolesAreIsolatedByOrganization() {
    UUID orgA = fixtures.organization();
    UUID orgB = fixtures.organization();
    TestUser userB = fixtures.user(orgB);
    fixtures.grant(userB, null, Permissions.FACILITY_READ);

    for (String table : List.of("users", "roles", "role_permissions", "user_role_assignments")) {
      long visibleToA =
          OrganizationContext.callAs(
              orgA,
              () ->
                  jdbc.sql("SELECT count(*) FROM " + table + " WHERE organization_id = :b")
                      .param("b", orgB)
                      .query(Long.class)
                      .single());
      assertThat(visibleToA).as(table).isZero();
    }
  }

  @Test
  void cannotAssignARoleFromAnotherOrganization() {
    UUID orgA = fixtures.organization();
    UUID orgB = fixtures.organization();
    TestUser userA = fixtures.user(orgA);
    UUID roleB = fixtures.role(orgB, Permissions.FACILITY_READ);

    assertThatThrownBy(() ->
            OrganizationContext.callAsSchemaOwner(() ->
                jdbc.sql(
                        "INSERT INTO user_role_assignments (id, organization_id, user_id, role_id)"
                            + " VALUES (:id, :org, :user, :role)")
                    .param("id", UUID.randomUUID())
                    .param("org", orgA)
                    .param("user", userA.userId())
                    .param("role", roleB)
                    .update()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void cannotScopeARoleToAnotherOrganizationsFacility() {
    UUID orgA = fixtures.organization();
    UUID orgB = fixtures.organization();
    UUID facilityB = fixtures.facility(orgB, "B");

    TestUser userA = fixtures.user(orgA);

    assertThatThrownBy(() -> fixtures.grant(userA, facilityB, Permissions.FACILITY_READ))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void applicationRoleCannotDeleteUsersOrAssignments() {
    UUID org = fixtures.organization();
    TestUser user = fixtures.user(org);
    UUID assignment = fixtures.grant(user, null, Permissions.FACILITY_READ);

    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                org,
                () ->
                    jdbc.sql("DELETE FROM user_role_assignments WHERE id = :id")
                        .param("id", assignment)
                        .update()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                org,
                () ->
                    jdbc.sql("DELETE FROM users WHERE id = :id")
                        .param("id", user.userId())
                        .update()))
        .isInstanceOf(DataAccessException.class);
  }
}
