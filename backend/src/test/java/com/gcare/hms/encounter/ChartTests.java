package com.gcare.hms.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.testsupport.IdentityFixtures;
import com.gcare.hms.testsupport.IdentityFixtures.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Patient chart, patient orders and clinician workspace (task 3.4). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChartTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private UUID other;
  private UUID opd;
  private UUID otherOpd;
  private TestUser clerk;
  private TestUser doctor;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    other = fixtures.facility(orgId, "OTHER");
    opd = fixtures.department(orgId, facility, "OPD");
    otherOpd = fixtures.department(orgId, other, "OPD");
    String[] front = {
      Permissions.PATIENT_READ,
      Permissions.PATIENT_REGISTER,
      Permissions.ENCOUNTER_READ,
      Permissions.ENCOUNTER_OPEN
    };
    clerk = fixtures.user(orgId);
    fixtures.grant(clerk, facility, front);
    fixtures.grant(clerk, other, front);
    doctor = fixtures.user(orgId);
    fixtures.grant(
        doctor,
        facility,
        Permissions.PATIENT_READ,
        Permissions.ENCOUNTER_READ,
        Permissions.ENCOUNTER_MANAGE,
        Permissions.OBSERVATION_READ,
        Permissions.OBSERVATION_RECORD,
        Permissions.CLINICAL_NOTE_READ,
        Permissions.CLINICAL_NOTE_WRITE,
        Permissions.ORDER_READ);
  }

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

  private String patient() throws Exception {
    return read(
        send(
                post("/api/v1/patients"),
                clerk,
                "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"Chart%s\", \"given_names\": \"Case\", \"birth_date\": \"1968-08-08\", \"sex\": \"MALE\"}"
                    .formatted(facility, UUID.randomUUID().toString().substring(0, 8)))
            .andExpect(status().isCreated()),
        "$.id");
  }

  private String open(String patient, UUID atFacility, UUID department) throws Exception {
    return read(
        send(
                post("/api/v1/encounters"),
                clerk,
                "{\"patient_id\": \"%s\", \"facility_id\": \"%s\", \"department_id\": \"%s\", \"encounter_type\": \"NEW_VISIT\"}"
                    .formatted(patient, atFacility, department))
            .andExpect(status().isCreated()),
        "$.encounter.id");
  }

  @Test
  void chartBringsVisitsDiagnosesAndTriageTogetherWithinPermissionAndScope() throws Exception {
    String p = patient();
    String first = open(p, facility, opd);
    send(post("/api/v1/encounters/{id}/call", first), doctor, "{\"version\": 0}")
        .andExpect(status().isOk());
    send(
            post("/api/v1/encounters/{id}/observations", first),
            doctor,
            "{\"pulse_bpm\": 80, \"allergy_note\": \"Reports penicillin rash\"}")
        .andExpect(status().isCreated());
    send(
            post("/api/v1/encounters/{id}/diagnoses", first),
            doctor,
            "{\"description\": \"Test diagnosis one\", \"certainty\": \"CONFIRMED\"}")
        .andExpect(status().isCreated());
    send(post("/api/v1/encounters/{id}/close", first), doctor, "{\"version\": 1}")
        .andExpect(status().isOk());

    String second = open(p, facility, opd);
    String wrong =
        read(
            send(
                    post("/api/v1/encounters/{id}/observations", second),
                    doctor,
                    "{\"pulse_bpm\": 300}")
                .andExpect(status().isCreated()),
            "$.id");
    send(
            post("/api/v1/observations/{id}/entered-in-error", wrong),
            doctor,
            "{\"reason\": \"Typo\"}")
        .andExpect(status().isOk());
    String elsewhere = open(p, other, otherOpd);

    send(get("/api/v1/patients/{id}/chart", p), doctor, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.visits.length()").value(2))
        .andExpect(jsonPath("$.visits[0].id").value(second))
        .andExpect(jsonPath("$.visits[1].id").value(first))
        .andExpect(jsonPath("$.visits[?(@.id == '%s')]".formatted(elsewhere)).isEmpty())
        .andExpect(jsonPath("$.diagnoses[0].description").value("Test diagnosis one"))
        .andExpect(jsonPath("$.latest_observation.pulse_bpm").value(80))
        .andExpect(jsonPath("$.reported_allergies[0].note").value("Reports penicillin rash"));

    // Front desk: visits at both facilities, no clinical sections.
    send(get("/api/v1/patients/{id}/chart", p), clerk, "")
        .andExpect(jsonPath("$.visits.length()").value(3))
        .andExpect(jsonPath("$.diagnoses").doesNotExist())
        .andExpect(jsonPath("$.latest_observation").doesNotExist())
        .andExpect(jsonPath("$.reported_allergies").doesNotExist())
        .andExpect(jsonPath("$.visits[0].triaged").doesNotExist());

    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE action = 'PATIENT_CHART_VIEWED' AND resource_id = ?",
                p))
        .isEqualTo(2);
    send(get("/api/v1/patients/{id}/chart", UUID.randomUUID()), doctor, "")
        .andExpect(status().isNotFound());
  }

  @Test
  void workspaceShowsPatientsWithMeAndMyDrafts() throws Exception {
    String p = patient();
    String e = open(p, facility, opd);
    send(get("/api/v1/workspace"), doctor, "")
        .andExpect(jsonPath("$.in_consultation").isEmpty())
        .andExpect(jsonPath("$.drafts").isEmpty());
    send(post("/api/v1/encounters/{id}/call", e), doctor, "{\"version\": 0}")
        .andExpect(status().isOk());
    String note =
        read(
            send(post("/api/v1/encounters/{id}/notes", e), doctor, "{\"history\": \"x\"}")
                .andExpect(status().isCreated()),
            "$.id");

    send(get("/api/v1/workspace"), doctor, "")
        .andExpect(jsonPath("$.in_consultation[0].id").value(e))
        .andExpect(jsonPath("$.drafts[0].note_id").value(note))
        .andExpect(jsonPath("$.drafts[0].patient.id").value(p))
        .andExpect(jsonPath("$.drafts[0].amendment").value(false));

    send(post("/api/v1/notes/{id}/sign", note), doctor, "{\"version\": 0}")
        .andExpect(status().isOk());
    send(get("/api/v1/workspace"), doctor, "").andExpect(jsonPath("$.drafts").isEmpty());
    send(get("/api/v1/workspace"), clerk, "").andExpect(status().isForbidden());
  }

  @Test
  void patientOrdersAreListedAcrossVisitsInScope() throws Exception {
    TestUser admin = fixtures.user(orgId);
    fixtures.grant(admin, facility, Permissions.ORDER_CATALOG_MANAGE);
    TestUser orderer = fixtures.user(orgId);
    fixtures.grant(
        orderer,
        facility,
        Permissions.ENCOUNTER_READ,
        Permissions.ENCOUNTER_MANAGE,
        Permissions.ORDER_READ,
        Permissions.ORDER_CREATE);
    String item =
        read(
            send(
                    post("/api/v1/order-catalog"),
                    admin,
                    "{\"code\": \"T-CHART\", \"name\": \"Test item\", \"category\": \"REFERRAL\"}")
                .andExpect(status().isCreated()),
            "$.id");
    String p = patient();
    String e = open(p, facility, opd);
    send(
            post("/api/v1/encounters/{id}/orders", e),
            orderer,
            "{\"catalog_item_id\": \"%s\", \"priority\": \"ROUTINE\"}".formatted(item))
        .andExpect(status().isCreated());

    send(get("/api/v1/patients/{id}/orders", p), doctor, "")
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].item.code").value("T-CHART"));
    TestUser outsider = fixtures.user(orgId);
    fixtures.grant(outsider, other, Permissions.ORDER_READ);
    send(get("/api/v1/patients/{id}/orders", p), outsider, "")
        .andExpect(jsonPath("$.length()").value(0));
    send(get("/api/v1/patients/{id}/orders", p), clerk, "").andExpect(status().isForbidden());
  }
}
