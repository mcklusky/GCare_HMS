package com.gcare.hms.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** Encounters, queues and triage with the default (empty) triage configuration (task 3.1). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EncounterTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private JdbcClient jdbc;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private UUID opd;
  private UUID rad;
  private TestUser clerk;
  private TestUser nurse;
  private TestUser clinician;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    opd = fixtures.department(orgId, facility, "OPD");
    rad = fixtures.department(orgId, facility, "RAD");
    clerk = fixtures.user(orgId);
    fixtures.grant(
        clerk,
        facility,
        Permissions.PATIENT_READ,
        Permissions.PATIENT_REGISTER,
        Permissions.ENCOUNTER_READ,
        Permissions.ENCOUNTER_OPEN);
    nurse = fixtures.user(orgId);
    fixtures.grant(
        nurse,
        facility,
        Permissions.PATIENT_READ,
        Permissions.ENCOUNTER_READ,
        Permissions.OBSERVATION_READ,
        Permissions.OBSERVATION_RECORD);
    clinician = fixtures.user(orgId);
    fixtures.grant(
        clinician,
        facility,
        Permissions.PATIENT_READ,
        Permissions.ENCOUNTER_READ,
        Permissions.ENCOUNTER_MANAGE,
        Permissions.ENCOUNTER_REOPEN,
        Permissions.OBSERVATION_READ,
        Permissions.OBSERVATION_RECORD);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private ResultActions send(MockHttpServletRequestBuilder req, TestUser user, String json)
      throws Exception {
    return mvc.perform(
        req.with(user.signedIn())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private static String read(ResultActions r, String path) throws Exception {
    return String.valueOf(
        (Object) JsonPath.read(r.andReturn().getResponse().getContentAsString(), path));
  }

  private int counter = 0;

  private String patient() throws Exception {
    counter++;
    String json =
        ("{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\","
                + " \"family_name\": \"Visitor%s\", \"given_names\": \"Case%d\", \"birth_date\": \"19%02d-05-05\","
                + " \"sex\": \"MALE\"}")
            .formatted(
                facility, UUID.randomUUID().toString().substring(0, 8), counter, 50 + counter);
    return read(
        send(post("/api/v1/patients"), clerk, json).andExpect(status().isCreated()), "$.id");
  }

  private String open(String patientId) throws Exception {
    return read(
        send(
                post("/api/v1/encounters"),
                clerk,
                "{\"patient_id\": \"%s\", \"facility_id\": \"%s\", \"department_id\": \"%s\", \"encounter_type\": \"NEW_VISIT\"}"
                    .formatted(patientId, facility, opd))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.encounter.status").value("WAITING")),
        "$.encounter.id");
  }

  private ResultActions act(TestUser user, String id, String action, String body) throws Exception {
    return send(post("/api/v1/encounters/{id}/" + action, id), user, body);
  }

  // --- lifecycle ---------------------------------------------------------------------------------

  @Test
  void visitLifecycleIsRecordedStepByStep() throws Exception {
    String p = patient();
    String id = open(p);

    // One open visit per patient and facility.
    send(
            post("/api/v1/encounters"),
            clerk,
            "{\"patient_id\": \"%s\", \"facility_id\": \"%s\", \"department_id\": \"%s\", \"encounter_type\": \"FOLLOW_UP\"}"
                .formatted(p, facility, opd))
        .andExpect(status().isConflict());

    act(clinician, id, "call", "{\"version\": 0}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.encounter.status").value("IN_CONSULTATION"))
        .andExpect(jsonPath("$.encounter.clinician.id").value(clinician.userId().toString()));
    act(
            clinician,
            id,
            "transfer",
            "{\"version\": 1, \"department_id\": \"%s\", \"reason\": \"Needs imaging\"}"
                .formatted(rad))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.encounter.status").value("WAITING"))
        .andExpect(jsonPath("$.encounter.department.code").value("RAD"))
        .andExpect(jsonPath("$.encounter.clinician").doesNotExist());
    act(clinician, id, "call", "{\"version\": 2}").andExpect(status().isOk());
    act(clinician, id, "close", "{\"version\": 3}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.encounter.status").value("CLOSED"))
        .andExpect(jsonPath("$.encounter.ended_at").exists());
    act(clinician, id, "reopen", "{\"version\": 4, \"reason\": \"Result came back\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.encounter.status").value("IN_CONSULTATION"))
        .andExpect(jsonPath("$.encounter.ended_at").doesNotExist())
        .andExpect(
            jsonPath("$.events[*].event_type")
                .value(
                    org.hamcrest.Matchers.contains(
                        "OPENED", "CALLED", "TRANSFERRED", "CALLED", "CLOSED", "REOPENED")))
        .andExpect(jsonPath("$.events[2].reason").value("Needs imaging"))
        .andExpect(jsonPath("$.events[2].department.code").value("RAD"));

    assertThat(fixtures.auditActions(clinician.userId()))
        .contains(
            "ENCOUNTER_CALLED", "ENCOUNTER_TRANSFERRED", "ENCOUNTER_CLOSED", "ENCOUNTER_REOPENED");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE metadata::text LIKE '%imaging%'"))
        .isZero();
  }

  @Test
  void transitionsFollowTheWorkflow() throws Exception {
    String id = open(patient());
    act(clinician, id, "close", "{\"version\": 0}").andExpect(status().isUnprocessableContent());
    act(clinician, id, "reopen", "{\"version\": 0, \"reason\": \"x\"}")
        .andExpect(status().isUnprocessableContent());
    act(
            clinician,
            id,
            "transfer",
            "{\"version\": 0, \"department_id\": \"%s\", \"reason\": \"x\"}".formatted(opd))
        .andExpect(status().isBadRequest());
    act(clinician, id, "call", "{\"version\": 7}").andExpect(status().isConflict());
    act(clerk, id, "cancel", "{\"version\": 0, \"reason\": \"\"}")
        .andExpect(status().isBadRequest());
    act(clerk, id, "cancel", "{\"version\": 0, \"reason\": \"Left before being seen\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.encounter.status").value("CANCELLED"));
    act(clinician, id, "call", "{\"version\": 1}").andExpect(status().isUnprocessableContent());
  }

  @Test
  void permissionsAndFacilityScopeAreEnforced() throws Exception {
    String id = open(patient());
    act(clerk, id, "call", "{\"version\": 0}").andExpect(status().isForbidden());
    act(nurse, id, "call", "{\"version\": 0}").andExpect(status().isForbidden());

    // A clinician at another facility does not see the visit at all.
    UUID other = fixtures.facility(orgId, "OTHER");
    TestUser elsewhere = fixtures.user(orgId);
    fixtures.grant(elsewhere, other, Permissions.ENCOUNTER_READ, Permissions.ENCOUNTER_MANAGE);
    act(elsewhere, id, "call", "{\"version\": 0}").andExpect(status().isNotFound());
    send(get("/api/v1/encounters/{id}", id), elsewhere, "").andExpect(status().isNotFound());
    send(get("/api/v1/encounters"), elsewhere, "").andExpect(jsonPath("$.total").value(0));
    send(get("/api/v1/encounters").param("facility_id", facility.toString()), elsewhere, "")
        .andExpect(status().isNotFound());

    // A department of another facility is refused.
    UUID otherDept = fixtures.department(orgId, other, "OPD");
    send(
            post("/api/v1/encounters"),
            clerk,
            "{\"patient_id\": \"%s\", \"facility_id\": \"%s\", \"department_id\": \"%s\", \"encounter_type\": \"NEW_VISIT\"}"
                .formatted(patient(), facility, otherDept))
        .andExpect(status().isBadRequest());
  }

  @Test
  void departmentsAreListedForEncounterStaffInScope() throws Exception {
    send(get("/api/v1/encounter-departments").param("facility_id", facility.toString()), nurse, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].code").value(org.hamcrest.Matchers.contains("OPD", "RAD")));
    UUID other = fixtures.facility(orgId, "ELSEWHERE");
    send(get("/api/v1/encounter-departments").param("facility_id", other.toString()), nurse, "")
        .andExpect(status().isNotFound());
  }

  @Test
  void linkedPatientRecordCannotStartAVisit() throws Exception {
    String keep = patient();
    String retired = patient();
    OrganizationContext.callAsSchemaOwner(() ->
        jdbc.sql("UPDATE patients SET status = 'LINKED', linked_to_patient_id = ? WHERE id = ?")
            .params(UUID.fromString(keep), UUID.fromString(retired))
            .update());
    send(
            post("/api/v1/encounters"),
            clerk,
            "{\"patient_id\": \"%s\", \"facility_id\": \"%s\", \"department_id\": \"%s\", \"encounter_type\": \"NEW_VISIT\"}"
                .formatted(retired, facility, opd))
        .andExpect(status().isUnprocessableContent());
  }

  // --- triage ------------------------------------------------------------------------------------

  @Test
  void triageRecordsValuesWithUnitsAuthorAndNoInventedRules() throws Exception {
    String id = open(patient());
    send(
            post("/api/v1/encounters/{id}/observations", id),
            nurse,
            "{\"temperature_c\": 37.2, \"pulse_bpm\": 88, \"systolic_mmhg\": 121, \"diastolic_mmhg\": 79, \"pain_score\": 3, \"notes\": \"Cough for 3 days\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.temperature_c").value(37.2))
        .andExpect(jsonPath("$.recorded_by.id").value(nurse.userId().toString()))
        .andExpect(jsonPath("$.flags").isEmpty());

    // No urgency scale is configured: urgency cannot be recorded, and nothing is flagged.
    send(post("/api/v1/encounters/{id}/observations", id), nurse, "{\"urgency\": \"RED\"}")
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.field_errors.urgency[0]")
                .value(org.hamcrest.Matchers.containsString("No urgency scale")));
    send(get("/api/v1/triage/settings"), nurse, "")
        .andExpect(jsonPath("$.urgency_levels").isEmpty())
        .andExpect(jsonPath("$.alarm_limits_configured").value(false));

    // Data-entry limits catch typing errors; an empty set is refused.
    send(post("/api/v1/encounters/{id}/observations", id), nurse, "{\"temperature_c\": 372}")
        .andExpect(status().isBadRequest());
    send(post("/api/v1/encounters/{id}/observations", id), nurse, "{}")
        .andExpect(status().isBadRequest());
    send(post("/api/v1/encounters/{id}/observations", id), clerk, "{\"pulse_bpm\": 80}")
        .andExpect(status().isForbidden());

    // The queue shows triage status to clinical staff only.
    send(get("/api/v1/encounters").param("facility_id", facility.toString()), clinician, "")
        .andExpect(
            jsonPath("$.items[?(@.id == '%s')].triaged".formatted(id))
                .value(org.hamcrest.Matchers.contains(true)));
    send(get("/api/v1/encounters").param("facility_id", facility.toString()), clerk, "")
        .andExpect(
            jsonPath("$.items[?(@.id == '%s')].triaged".formatted(id))
                .value(org.hamcrest.Matchers.contains((Object) null)));
    send(get("/api/v1/encounters/{id}/observations", id), clerk, "")
        .andExpect(status().isForbidden());

    // Audit names the fields, never the values or the notes.
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE action = 'OBSERVATIONS_RECORDED' AND resource_id = ?"
                    + " AND metadata->>'fields' = 'temperature_c,pulse_bpm,systolic_mmhg,diastolic_mmhg,pain_score,notes'",
                id))
        .isEqualTo(1);
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE metadata::text LIKE '%Cough%' OR metadata::text LIKE '%37.2%'"))
        .isZero();
  }

  @Test
  void wrongEntryIsMarkedNotChanged() throws Exception {
    String id = open(patient());
    String obs =
        read(
            send(post("/api/v1/encounters/{id}/observations", id), nurse, "{\"pulse_bpm\": 180}")
                .andExpect(status().isCreated()),
            "$.id");
    send(
            post("/api/v1/observations/{id}/entered-in-error", obs),
            clinician,
            "{\"reason\": \"Wrong patient\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ENTERED_IN_ERROR"))
        .andExpect(jsonPath("$.pulse_bpm").value(180))
        .andExpect(jsonPath("$.error_by.id").value(clinician.userId().toString()));
    send(
            post("/api/v1/observations/{id}/entered-in-error", obs),
            clinician,
            "{\"reason\": \"again\"}")
        .andExpect(status().isUnprocessableContent());
    send(get("/api/v1/encounters/{id}", id), clinician, "")
        .andExpect(jsonPath("$.encounter.triaged").value(false));

    // The database refuses to change recorded values, even for the application role.
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE triage_observations SET pulse_bpm = 80 WHERE id = ?")
                        .params(UUID.fromString(obs))
                        .update()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void encounterTablesAreIsolatedAndCannotBeDeleted() throws Exception {
    String id = open(patient());
    send(post("/api/v1/encounters/{id}/observations", id), nurse, "{\"pulse_bpm\": 70}")
        .andExpect(status().isCreated());
    UUID otherOrg = fixtures.organization();
    for (String table : new String[] {"encounters", "encounter_events", "triage_observations"}) {
      long visible =
          OrganizationContext.callAs(
              otherOrg,
              () ->
                  jdbc.sql("SELECT count(*) FROM " + table + " WHERE organization_id = ?")
                      .params(orgId)
                      .query(Long.class)
                      .single());
      assertThat(visible).as(table).isZero();
      assertThatThrownBy(() ->
              OrganizationContext.callAs(orgId, () -> jdbc.sql("DELETE FROM " + table).update()))
          .as(table)
          .isInstanceOf(DataAccessException.class);
    }
  }
}
