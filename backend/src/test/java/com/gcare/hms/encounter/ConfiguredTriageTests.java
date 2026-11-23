package com.gcare.hms.encounter;

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

/**
 * The mechanism with a configured scale and limits. The values here are <strong>test fixtures
 * only</strong> (deliberately unlike any real triage system); real values must come from a clinical
 * lead.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "gcare.triage.urgency-levels[0].code=TEST_FIRST",
      "gcare.triage.urgency-levels[0].label=Test first",
      "gcare.triage.urgency-levels[0].rank=1",
      "gcare.triage.urgency-levels[1].code=TEST_LATER",
      "gcare.triage.urgency-levels[1].label=Test later",
      "gcare.triage.urgency-levels[1].rank=2",
      "gcare.triage.alarm-limits.PULSE_BPM.high=150",
      "gcare.triage.alarm-limits.SPO2_PCT.low=50"
    })
class ConfiguredTriageTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;

  private ResultActions send(
      MockMvc mvc, MockHttpServletRequestBuilder req, TestUser user, String json) throws Exception {
    return mvc.perform(
        req.with(user.signedIn())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  @Test
  void configuredLevelsOrderTheQueueAndLimitsRaiseFlags() throws Exception {
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    UUID org = fixtures.organization();
    UUID facility = fixtures.facility(org, "GH");
    UUID opd = fixtures.department(org, facility, "OPD");
    TestUser staff = fixtures.user(org);
    fixtures.grant(
        staff,
        facility,
        Permissions.PATIENT_READ,
        Permissions.PATIENT_REGISTER,
        Permissions.ENCOUNTER_READ,
        Permissions.ENCOUNTER_OPEN,
        Permissions.OBSERVATION_READ,
        Permissions.OBSERVATION_RECORD);

    String[] encounters = new String[2];
    for (int i = 0; i < 2; i++) {
      String patient =
          JsonPath.read(
              send(
                      mvc,
                      post("/api/v1/patients"),
                      staff,
                      "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"Queue%d\", \"given_names\": \"Order%d\", \"birth_date\": \"198%d-01-01\", \"sex\": \"FEMALE\"}"
                          .formatted(facility, i, i, i))
                  .andExpect(status().isCreated())
                  .andReturn()
                  .getResponse()
                  .getContentAsString(),
              "$.id");
      encounters[i] =
          JsonPath.read(
              send(
                      mvc,
                      post("/api/v1/encounters"),
                      staff,
                      "{\"patient_id\": \"%s\", \"facility_id\": \"%s\", \"department_id\": \"%s\", \"encounter_type\": \"NEW_VISIT\"}"
                          .formatted(patient, facility, opd))
                  .andExpect(status().isCreated())
                  .andReturn()
                  .getResponse()
                  .getContentAsString(),
              "$.encounter.id");
    }
    // The later arrival gets the higher-priority level and a value beyond a limit.
    send(
            mvc,
            post("/api/v1/encounters/{id}/observations", encounters[0]),
            staff,
            "{\"urgency\": \"TEST_LATER\"}")
        .andExpect(status().isCreated());
    send(
            mvc,
            post("/api/v1/encounters/{id}/observations", encounters[1]),
            staff,
            "{\"urgency\": \"TEST_FIRST\", \"pulse_bpm\": 160, \"spo2_pct\": 97}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.flags.length()").value(1))
        .andExpect(jsonPath("$.flags[0].measure").value("PULSE_BPM"))
        .andExpect(jsonPath("$.flags[0].direction").value("HIGH"));
    send(
            mvc,
            post("/api/v1/encounters/{id}/observations", encounters[0]),
            staff,
            "{\"urgency\": \"NOT_A_LEVEL\"}")
        .andExpect(status().isBadRequest());

    send(mvc, get("/api/v1/encounters").param("facility_id", facility.toString()), staff, "")
        .andExpect(jsonPath("$.items[0].id").value(encounters[1]))
        .andExpect(jsonPath("$.items[0].urgency").value("TEST_FIRST"))
        .andExpect(jsonPath("$.items[0].flags[0]").value("PULSE_BPM_HIGH"))
        .andExpect(jsonPath("$.items[1].id").value(encounters[0]));
    send(mvc, get("/api/v1/triage/settings"), staff, "")
        .andExpect(jsonPath("$.urgency_levels.length()").value(2))
        .andExpect(jsonPath("$.alarm_limits_configured").value(true));
  }
}
