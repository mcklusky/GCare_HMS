package com.gcare.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import com.gcare.hms.testsupport.IdentityFixtures;
import com.gcare.hms.testsupport.IdentityFixtures.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Controlled linking of duplicate records (task 2.3; docs/17 §5). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PatientLinkTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private JdbcClient jdbc;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private TestUser clerk;
  private TestUser supervisor;
  private TestUser manager;
  private TestUser manager2;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "REG");
    clerk = fixtures.user(orgId);
    fixtures.grant(
        clerk,
        facility,
        Permissions.PATIENT_READ,
        Permissions.PATIENT_REGISTER,
        Permissions.PATIENT_UPDATE);
    supervisor = fixtures.user(orgId);
    fixtures.grant(
        supervisor,
        facility,
        Permissions.PATIENT_READ,
        Permissions.PATIENT_DUPLICATE_REVIEW,
        Permissions.PATIENT_LINK_REQUEST);
    manager = fixtures.user(orgId);
    fixtures.grant(
        manager,
        facility,
        Permissions.PATIENT_READ,
        Permissions.PATIENT_DUPLICATE_REVIEW,
        Permissions.PATIENT_LINK_REQUEST,
        Permissions.PATIENT_LINK_APPROVE);
    manager2 = fixtures.user(orgId);
    fixtures.grant(manager2, facility, Permissions.PATIENT_READ, Permissions.PATIENT_LINK_APPROVE);
  }

  private ResultActions send(MockHttpServletRequestBuilder req, TestUser user, String json)
      throws Exception {
    return mvc.perform(
        req.with(user.signedIn())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private static String read(ResultActions result, String path) throws Exception {
    return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path).toString();
  }

  /** Distinct people so registration finds no duplicate candidates. */
  private String register(String family, String given, String birthDate, String extra)
      throws Exception {
    String json =
        ("{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\","
                + " \"family_name\": \"%s\", \"given_names\": \"%s\", \"birth_date\": \"%s\","
                + " \"sex\": \"FEMALE\"%s}")
            .formatted(facility, family, given, birthDate, extra);
    return read(
        send(post("/api/v1/patients"), clerk, json).andExpect(status().isCreated()), "$.id");
  }

  private String requestLink(TestUser user, String retired, String surviving) throws Exception {
    return read(
        send(
                post("/api/v1/patient-links"),
                user,
                """
                {"retired_patient_id": "%s", "surviving_patient_id": "%s",
                 "reason": "Same person registered twice"}
                """
                    .formatted(retired, surviving))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("REQUESTED")),
        "$.id");
  }

  private ResultActions decide(TestUser user, String linkId, String action, String extra)
      throws Exception {
    return send(
        post("/api/v1/patient-links/{id}/" + action, linkId),
        user,
        "{\"version\": 0, \"note\": \"Checked against ID card\"%s}".formatted(extra));
  }

  private String number(String patientId) throws Exception {
    return read(
        send(get("/api/v1/patients/{id}", patientId), clerk, "").andExpect(status().isOk()),
        "$.patient_number");
  }

  // --- the link itself ---------------------------------------------------------------------------

  @Test
  void approvedLinkRetiresTheDuplicateWithoutTouchingItsData() throws Exception {
    String keep = register("Amankwah", "Abena", "1980-01-01", "");
    String dup = register("Boateng", "Belinda", "1981-02-02", ", \"phone\": \"0200000001\"");
    String keepNumber = number(keep);
    String dupNumber = number(dup);

    String link = requestLink(supervisor, dup, keep);
    decide(manager, link, "approve", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.decided_by").value(manager.userId().toString()))
        .andExpect(jsonPath("$.override_reason").doesNotExist());

    // The retired record keeps its data, is LINKED and points to the record in use.
    send(get("/api/v1/patients/{id}", dup), clerk, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LINKED"))
        .andExpect(jsonPath("$.family_name").value("Boateng"))
        .andExpect(jsonPath("$.phone").value("0200000001"))
        .andExpect(jsonPath("$.linked_to.id").value(keep))
        .andExpect(jsonPath("$.linked_to.patient_number").value(keepNumber));
    send(get("/api/v1/patients/{id}", keep), clerk, "")
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.linked_to").doesNotExist());

    // Its number still resolves (to the LINKED record, which names the record in use) ...
    send(get("/api/v1/patients").param("number", dupNumber), clerk, "")
        .andExpect(jsonPath("$.items[0].status").value("LINKED"));
    // ... but it no longer appears in other searches or as a duplicate candidate.
    send(get("/api/v1/patients").param("phone", "0200000001"), clerk, "")
        .andExpect(jsonPath("$.total").value(0));
    // It cannot be corrected; corrections go to the record in use.
    send(
            patch("/api/v1/patients/{id}", dup),
            clerk,
            "{\"version\": 1, \"reason\": \"x\", \"changes\": {\"given_names\": \"B\"}}")
        .andExpect(status().isUnprocessableContent());

    // Both records show the link in their history.
    send(get("/api/v1/patients/{id}/links", keep), clerk, "")
        .andExpect(jsonPath("$[0].id").value(link))
        .andExpect(jsonPath("$[0].retired.patient_number").value(dupNumber))
        .andExpect(jsonPath("$[0].request_reason").value("Same person registered twice"));

    assertThat(fixtures.auditActions(supervisor.userId())).contains("PATIENT_LINK_REQUESTED");
    assertThat(fixtures.auditActions(manager.userId())).contains("PATIENT_LINK_APPROVED");
    // Audit metadata names ids, never reasons or notes.
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE metadata::text LIKE '%registered twice%'"
                    + " OR metadata::text LIKE '%ID card%'"))
        .isZero();
  }

  @Test
  void requesterCannotApproveOrRejectTheirOwnRequest() throws Exception {
    String a = register("Cudjoe", "Comfort", "1982-03-03", "");
    String b = register("Dankwa", "Dora", "1983-04-04", "");
    String link = requestLink(manager, b, a);

    decide(manager, link, "approve", "").andExpect(status().isUnprocessableContent());
    decide(manager, link, "reject", "").andExpect(status().isUnprocessableContent());
    decide(manager2, link, "reject", "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
    // Rejected: nothing changed, and the decision is final.
    send(get("/api/v1/patients/{id}", b), clerk, "")
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    decide(manager2, link, "approve", "").andExpect(status().isUnprocessableContent());
  }

  @Test
  void databaseRefusesSameDeciderAsRequester() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertThatThrownBy(() ->
            OrganizationContext.callAsSchemaOwner(() -> {
              for (UUID id : new UUID[] {a, b}) {
                jdbc.sql(
                        "INSERT INTO patients (id, organization_id, patient_number,"
                            + " registration_facility_id, status, sex, details_incomplete,"
                            + " registration_source, created_by) VALUES (?, ?, ?, ?, 'ACTIVE',"
                            + " 'UNKNOWN', true, 'WALK_IN', ?)")
                    .params(
                        id,
                        orgId,
                        id.equals(a) ? "P1000001-6" : "P1000002-4",
                        facility,
                        clerk.userId())
                    .update();
              }
              return jdbc.sql(
                      "INSERT INTO patient_links (id, organization_id, retired_patient_id,"
                          + " surviving_patient_id, status, request_reason, requested_by,"
                          + " decided_by, decided_at, decision_note) VALUES (?, ?, ?, ?,"
                          + " 'APPROVED', 'r', ?, ?, now(), 'n')")
                  .params(UUID.randomUUID(), orgId, a, b, manager.userId(), manager.userId())
                  .update();
            }))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void permissionsAreEnforced() throws Exception {
    String a = register("Essien", "Efua", "1984-05-05", "");
    String b = register("Frimpong", "Fafa", "1985-06-06", "");
    String body =
        "{\"retired_patient_id\": \"%s\", \"surviving_patient_id\": \"%s\", \"reason\": \"r\"}"
            .formatted(b, a);
    send(post("/api/v1/patient-links"), clerk, body).andExpect(status().isForbidden());

    String link = requestLink(supervisor, b, a);
    send(get("/api/v1/patient-links"), supervisor, "").andExpect(status().isForbidden());
    decide(supervisor, link, "approve", "").andExpect(status().isForbidden());
    send(get("/api/v1/patient-links"), manager, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(link));
  }

  // --- blocked links -----------------------------------------------------------------------------

  @Test
  void chainsAndDoubleRequestsAreBlocked() throws Exception {
    String a = register("Gyamfi", "Grace", "1986-07-07", "");
    String b = register("Hammond", "Hilda", "1987-08-08", "");
    String c = register("Issah", "Ivy", "1988-09-09", "");

    String link = requestLink(supervisor, b, a);
    // A second request for the same retired record waits behind the first.
    send(
            post("/api/v1/patient-links"),
            supervisor,
            "{\"retired_patient_id\": \"%s\", \"surviving_patient_id\": \"%s\", \"reason\": \"r\"}"
                .formatted(b, c))
        .andExpect(status().isConflict());
    decide(manager, link, "approve", "").andExpect(status().isOk());

    String chainBody =
        "{\"retired_patient_id\": \"%s\", \"surviving_patient_id\": \"%s\", \"reason\": \"r\"}";
    // Retiring a record that others point to would make a chain.
    send(post("/api/v1/patient-links"), supervisor, chainBody.formatted(a, c))
        .andExpect(status().isUnprocessableContent());
    // Keeping a record that is itself retired would make a chain.
    send(post("/api/v1/patient-links"), supervisor, chainBody.formatted(c, b))
        .andExpect(status().isUnprocessableContent());
    // A record cannot be linked to itself.
    send(post("/api/v1/patient-links"), supervisor, chainBody.formatted(a, a))
        .andExpect(status().isBadRequest());
  }

  @Test
  void differentVerifiedIdentifiersNeedAnOverrideReason() throws Exception {
    String a =
        register(
            "Kumah",
            "Kafui",
            "1989-10-10",
            ", \"identifiers\": [{\"type\": \"NATIONAL_ID\", \"value\": \"GHA-000000001-1\","
                + " \"verified\": true}]");
    String b =
        register(
            "Larbi",
            "Lydia",
            "1990-11-11",
            ", \"identifiers\": [{\"type\": \"NATIONAL_ID\", \"value\": \"GHA-000000002-2\","
                + " \"verified\": true}]");
    String link = requestLink(supervisor, b, a);

    send(get("/api/v1/patient-links"), manager, "")
        .andExpect(jsonPath("$.items[0].identifier_conflicts[0]").value("NATIONAL_ID"));
    decide(manager, link, "approve", "")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("IDENTIFIER_CONFLICT"));
    decide(
            manager,
            link,
            "approve",
            ", \"override_reason\": \"Old card replaced; registry confirms\"")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.override_reason").value("Old card replaced; registry confirms"));
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE action = 'PATIENT_LINK_APPROVED'"
                    + " AND metadata->>'override' = 'true' AND resource_id = ?",
                b))
        .isEqualTo(1);
  }

  // --- reversal ----------------------------------------------------------------------------------

  @Test
  void unlinkRestoresBothRecordsAndKeepsHistory() throws Exception {
    String a = register("Mensah", "Mavis", "1991-12-12", "");
    String b = register("Nyarko", "Nana", "1992-01-13", "");
    String link = requestLink(supervisor, b, a);
    decide(manager, link, "approve", "").andExpect(status().isOk());

    send(
            post("/api/v1/patient-links/{id}/unlink", link),
            supervisor,
            "{\"version\": 1, \"reason\": \"r\"}")
        .andExpect(status().isForbidden());
    send(
            post("/api/v1/patient-links/{id}/unlink", link),
            manager2,
            "{\"version\": 1, \"reason\": \"Twins; linked in error\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNLINKED"))
        .andExpect(jsonPath("$.decided_by").value(manager.userId().toString()))
        .andExpect(jsonPath("$.unlinked_by").value(manager2.userId().toString()))
        .andExpect(jsonPath("$.unlink_reason").value("Twins; linked in error"));

    send(get("/api/v1/patients/{id}", b), clerk, "")
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.linked_to").doesNotExist());
    send(
            post("/api/v1/patient-links/{id}/unlink", link),
            manager2,
            "{\"version\": 2, \"reason\": \"again\"}")
        .andExpect(status().isUnprocessableContent());
    assertThat(fixtures.auditActions(manager2.userId())).contains("PATIENT_UNLINKED");

    // The pair can be linked again later; the old link stays in the history.
    requestLink(supervisor, b, a);
    send(get("/api/v1/patients/{id}/links", b), clerk, "")
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[1].status").value("UNLINKED"));
  }

  // --- from the review queue ---------------------------------------------------------------------

  @Test
  void requestFromReviewQueueClosesTheItem() throws Exception {
    String a = register("Osei", "Ophelia", "1993-02-14", "");
    String b = register("Pobee", "Priscilla", "1994-03-15", "");
    send(
            post("/api/v1/patients/{id}/duplicate-reports", b),
            supervisor,
            "{\"candidate_patient_id\": \"%s\", \"reason\": \"Same mother's name\"}".formatted(a))
        .andExpect(status().isNoContent());
    String flag =
        read(
            send(get("/api/v1/patient-duplicate-flags"), manager, "").andExpect(status().isOk()),
            "$.items[0].id");
    String body =
        ("{\"retired_patient_id\": \"%s\", \"surviving_patient_id\": \"%s\", \"reason\": \"r\","
            + " \"flag_id\": \"%s\", \"flag_version\": 0}");

    // The person who raised the item cannot decide it, and the flag must be about this pair.
    send(post("/api/v1/patient-links"), supervisor, body.formatted(b, a, flag))
        .andExpect(status().isUnprocessableContent());
    String c = register("Quaye", "Queenie", "1995-04-16", "");
    send(post("/api/v1/patient-links"), manager, body.formatted(c, a, flag))
        .andExpect(status().isBadRequest());

    send(post("/api/v1/patient-links"), manager, body.formatted(b, a, flag))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.flag_id").value(flag));
    send(get("/api/v1/patient-duplicate-flags").param("status", "LINK_REQUESTED"), manager, "")
        .andExpect(jsonPath("$.items[0].id").value(flag))
        .andExpect(jsonPath("$.items[0].resolved_by").value(manager.userId().toString()));
  }

  // --- isolation ---------------------------------------------------------------------------------

  @Test
  void linksAreIsolatedByOrganizationAndCannotBeDeleted() {
    UUID orgB = fixtures.organization();
    UUID facilityB = fixtures.facility(orgB, "B");
    TestUser userB = fixtures.user(orgB);
    TestUser userB2 = fixtures.user(orgB);
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    OrganizationContext.callAsSchemaOwner(() -> {
      for (UUID id : new UUID[] {p1, p2}) {
        jdbc.sql(
                "INSERT INTO patients (id, organization_id, patient_number,"
                    + " registration_facility_id, status, sex, details_incomplete,"
                    + " registration_source, created_by) VALUES (?, ?, ?, ?, 'ACTIVE', 'UNKNOWN',"
                    + " true, 'WALK_IN', ?)")
            .params(
                id, orgB, id.equals(p1) ? "P1000001-6" : "P1000002-4", facilityB, userB.userId())
            .update();
      }
      jdbc.sql(
              "INSERT INTO patient_links (id, organization_id, retired_patient_id,"
                  + " surviving_patient_id, status, request_reason, requested_by)"
                  + " VALUES (?, ?, ?, ?, 'REQUESTED', 'r', ?)")
          .params(UUID.randomUUID(), orgB, p1, p2, userB2.userId())
          .update();
      return null;
    });

    long visibleToA =
        OrganizationContext.callAs(
            orgId,
            () ->
                jdbc.sql("SELECT count(*) FROM patient_links WHERE organization_id = ?")
                    .params(orgB)
                    .query(Long.class)
                    .single());
    assertThat(visibleToA).isZero();
    assertThatThrownBy(() ->
            OrganizationContext.callAs(orgB, () -> jdbc.sql("DELETE FROM patient_links").update()))
        .isInstanceOf(DataAccessException.class);
  }
}
