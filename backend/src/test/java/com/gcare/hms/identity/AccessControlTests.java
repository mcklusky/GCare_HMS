package com.gcare.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.CorrelationIdFilter;
import com.gcare.hms.testsupport.IdentityFixtures;
import com.gcare.hms.testsupport.IdentityFixtures.TestUser;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Sign-in session to GCare user, permissions, facility scope and organization isolation, through
 * the real security filter chain and database (task 1.3; FR-001, FR-032).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AccessControlTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;

  private MockMvc mvc;
  private UUID org;
  private UUID facilityA;
  private UUID facilityB;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(new CorrelationIdFilter())
            .apply(springSecurity())
            .build();
    org = fixtures.organization();
    facilityA = fixtures.facility(org, "FAC-A");
    facilityB = fixtures.facility(org, "FAC-B");
  }

  // --- who is signed in ---------------------------------------------------------------------

  @Test
  void meReturnsUserOrganizationPermissionsAndFacilities() throws Exception {
    TestUser admin = fixtures.user(org);
    fixtures.grant(admin, null, Permissions.FACILITY_READ, Permissions.USER_READ);

    mvc.perform(get("/api/v1/me").with(admin.signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(admin.userId().toString()))
        .andExpect(jsonPath("$.display_name").value("Test User (synthetic)"))
        .andExpect(jsonPath("$.organization.id").value(org.toString()))
        .andExpect(jsonPath("$.permissions", contains("FACILITY_READ", "USER_READ")))
        .andExpect(jsonPath("$.facilities[*].code", contains("FAC-A", "FAC-B")));
  }

  @Test
  void userWithoutRolesCanSeeThemselvesButNothingElse() throws Exception {
    TestUser user = fixtures.user(org);

    mvc.perform(get("/api/v1/me").with(user.signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions", hasSize(0)))
        .andExpect(jsonPath("$.facilities", hasSize(0)));
    mvc.perform(get("/api/v1/facilities").with(user.signedIn()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void anonymousRequestIsUnauthenticated() throws Exception {
    mvc.perform(get("/api/v1/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  // --- the identity alone grants nothing -----------------------------------------------------

  @Test
  void identityNotLinkedToAGcareUserIsRejectedAndAudited() throws Exception {
    TestUser ghost = new TestUser(UUID.randomUUID(), org, IdentityFixtures.ISSUER, "not-linked");
    long before =
        fixtures.count(
            "SELECT count(*) FROM audit_events WHERE action = 'SESSION_ENDED'"
                + " AND metadata->>'reason' = 'IDENTITY_NOT_LINKED'");

    mvc.perform(get("/api/v1/me").with(ghost.signedIn())).andExpect(status().isUnauthorized());

    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE action = 'SESSION_ENDED'"
                    + " AND metadata->>'reason' = 'IDENTITY_NOT_LINKED'"))
        .isEqualTo(before + 1);
  }

  @Test
  void sameSubjectFromAnotherIssuerIsNotTheSameUser() throws Exception {
    TestUser user = fixtures.user(org);
    fixtures.grant(user, null, Permissions.FACILITY_READ);
    TestUser impostor =
        new TestUser(
            user.userId(), org, "https://other-idp.test.invalid/realms/gcare", user.subject());

    mvc.perform(get("/api/v1/me").with(impostor.signedIn())).andExpect(status().isUnauthorized());
  }

  @Test
  void disabledOrLockedUserLosesAccessOnTheNextRequest() throws Exception {
    TestUser user = fixtures.user(org);
    fixtures.grant(user, null, Permissions.FACILITY_READ);
    mvc.perform(get("/api/v1/facilities").with(user.signedIn())).andExpect(status().isOk());

    fixtures.setUserStatus(user, "DISABLED");
    mvc.perform(get("/api/v1/facilities").with(user.signedIn()))
        .andExpect(status().isUnauthorized());
    assertThat(fixtures.auditActions(user.userId())).contains("SESSION_ENDED");

    fixtures.setUserStatus(user, "LOCKED");
    mvc.perform(get("/api/v1/me").with(user.signedIn())).andExpect(status().isUnauthorized());
  }

  @Test
  void suspendedOrganizationLosesAccess() throws Exception {
    TestUser user = fixtures.user(org);
    fixtures.grant(user, null, Permissions.FACILITY_READ);

    fixtures.setOrganizationStatus(org, "SUSPENDED");

    mvc.perform(get("/api/v1/me").with(user.signedIn())).andExpect(status().isUnauthorized());
  }

  // --- permissions come from active role assignments ------------------------------------------

  @Test
  void missingPermissionIsForbiddenAndAudited() throws Exception {
    TestUser auditor = fixtures.user(org);
    fixtures.grant(auditor, null, Permissions.AUDIT_READ);

    mvc.perform(get("/api/v1/facilities").with(auditor.signedIn()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.correlation_id").exists());

    assertThat(fixtures.auditActions(auditor.userId())).containsExactly("ACCESS_DENIED");
  }

  @Test
  void endedRoleAssignmentTakesEffectImmediately() throws Exception {
    TestUser user = fixtures.user(org);
    UUID assignment = fixtures.grant(user, null, Permissions.FACILITY_READ);
    mvc.perform(get("/api/v1/facilities").with(user.signedIn())).andExpect(status().isOk());

    fixtures.endAssignment(assignment);

    mvc.perform(get("/api/v1/facilities").with(user.signedIn())).andExpect(status().isForbidden());
  }

  @Test
  void inactiveRoleGrantsNothing() throws Exception {
    TestUser user = fixtures.user(org);
    UUID assignment = fixtures.grant(user, null, Permissions.FACILITY_READ);

    fixtures.setRoleStatusForAssignment(assignment, "INACTIVE");

    mvc.perform(get("/api/v1/facilities").with(user.signedIn())).andExpect(status().isForbidden());
  }

  // --- facility scope -------------------------------------------------------------------------

  @Test
  void facilityScopedUserSeesOnlyTheirFacility() throws Exception {
    TestUser user = fixtures.user(org);
    fixtures.grant(user, facilityA, Permissions.FACILITY_READ);
    fixtures.department(org, facilityA, "OPD");
    fixtures.department(org, facilityB, "RAD");

    mvc.perform(get("/api/v1/facilities").with(user.signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", contains(facilityA.toString())));
    mvc.perform(get("/api/v1/facilities/{id}/departments", facilityA).with(user.signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].code", contains("OPD")));
    mvc.perform(get("/api/v1/facilities/{id}/departments", facilityB).with(user.signedIn()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get("/api/v1/me").with(user.signedIn()))
        .andExpect(jsonPath("$.facilities[*].code", contains("FAC-A")));
  }

  @Test
  void organizationWideGrantCoversEveryFacility() throws Exception {
    TestUser user = fixtures.user(org);
    fixtures.grant(user, null, Permissions.FACILITY_READ);

    mvc.perform(get("/api/v1/facilities").with(user.signedIn()))
        .andExpect(
            jsonPath("$[*].id", containsInAnyOrder(facilityA.toString(), facilityB.toString())));
  }

  @Test
  void grantAtAnInactiveFacilityGivesNoAccess() throws Exception {
    UUID closed = fixtures.facility(org, "FAC-CLOSED", "INACTIVE");
    TestUser user = fixtures.user(org);
    fixtures.grant(user, closed, Permissions.FACILITY_READ);

    mvc.perform(get("/api/v1/facilities").with(user.signedIn())).andExpect(status().isForbidden());
  }

  // --- organization isolation -----------------------------------------------------------------

  @Test
  void usersNeverSeeAnotherOrganizationsFacilities() throws Exception {
    UUID otherOrg = fixtures.organization();
    UUID otherFacility = fixtures.facility(otherOrg, "OTHER");
    TestUser otherAdmin = fixtures.user(otherOrg);
    fixtures.grant(otherAdmin, null, Permissions.FACILITY_READ);

    mvc.perform(get("/api/v1/facilities").with(otherAdmin.signedIn()))
        .andExpect(jsonPath("$[*].id", contains(otherFacility.toString())));
    mvc.perform(get("/api/v1/facilities/{id}/departments", facilityA).with(otherAdmin.signedIn()))
        .andExpect(status().isNotFound());
  }

  // --- deny by default ------------------------------------------------------------------------

  @Test
  void signedInUsersStillCannotReachUnlistedPaths() throws Exception {
    TestUser user = fixtures.user(org);
    fixtures.grant(user, null, Permissions.FACILITY_READ);

    mvc.perform(get("/actuator/env").with(user.signedIn())).andExpect(status().isForbidden());
    mvc.perform(get("/internal/anything").with(user.signedIn())).andExpect(status().isForbidden());
  }

  // --- sign-out -------------------------------------------------------------------------------

  @Test
  void signOutNeedsCsrfToken() throws Exception {
    TestUser user = fixtures.user(org);

    mvc.perform(post("/api/v1/auth/logout").with(user.signedIn()))
        .andExpect(status().isForbidden());
  }

  @Test
  void signOutIsAuditedAndReturnsJson() throws Exception {
    TestUser user = fixtures.user(org);

    mvc.perform(post("/api/v1/auth/logout").with(user.signedIn()).with(csrf()))
        .andExpect(status().isOk())
        // Test client registration has no end_session_endpoint, so no provider URL.
        .andExpect(jsonPath("$.logout_url").value(nullValue()));

    assertThat(fixtures.auditActions(user.userId())).contains("SIGNED_OUT");
  }
}
