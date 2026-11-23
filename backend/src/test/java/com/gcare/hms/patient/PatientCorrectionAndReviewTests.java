package com.gcare.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Corrections with history, staff duplicate reports and the review queue (task 2.2). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PatientCorrectionAndReviewTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private TestUser clerk;
  private TestUser supervisor;

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
        Permissions.PATIENT_REGISTER,
        Permissions.PATIENT_UPDATE,
        Permissions.PATIENT_DUPLICATE_REVIEW);
  }

  private ResultActions send(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
      TestUser user,
      String json)
      throws Exception {
    return mvc.perform(
        req.with(user.signedIn())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private String register(TestUser user, String fields) throws Exception {
    String json =
        "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\"%s}"
            .formatted(facility, fields);
    return JsonPath.read(
        send(post("/api/v1/patients"), user, json)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        "$.id");
  }

  // --- corrections -------------------------------------------------------------------------------

  @Test
  void correctionKeepsPreviousValuesWithReasonAndAuthor() throws Exception {
    String id =
        register(
            clerk,
            ", \"family_name\": \"Speling\", \"given_names\": \"Hotel\", \"sex\": \"UNKNOWN\"");

    send(
            patch("/api/v1/patients/{id}", id),
            clerk,
            """
            {"version": 0, "reason": "Spelling corrected from ID card",
             "changes": {"family_name": "Spelling", "sex": "FEMALE", "birth_date": "1991-01-31"}}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.family_name").value("Spelling"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.details_incomplete").value(false));

    mvc.perform(get("/api/v1/patients/{id}/history", id).with(clerk.signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].changes.family_name.from").value("Speling"))
        .andExpect(jsonPath("$[0].changes.family_name.to").value("Spelling"))
        .andExpect(jsonPath("$[0].changes.sex.from").value("UNKNOWN"))
        .andExpect(jsonPath("$[0].reason").value("Spelling corrected from ID card"))
        .andExpect(jsonPath("$[0].changed_by").value(clerk.userId().toString()));

    // The audit trail names the fields, never the values or the reason.
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE action = 'PATIENT_CORRECTED'"
                    + " AND resource_id = ? AND metadata->>'fields' = 'birth_date,family_name,sex'",
                id))
        .isEqualTo(1);
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE metadata::text LIKE '%Spelling%'"))
        .isZero();
  }

  @Test
  void staleCorrectionIsRejected() throws Exception {
    String id = register(clerk, ", \"family_name\": \"Stale\", \"sex\": \"MALE\"");
    String change =
        "{\"version\": 0, \"reason\": \"fix\", \"changes\": {\"phone\": \"0770000123\"}}";
    send(patch("/api/v1/patients/{id}", id), clerk, change).andExpect(status().isOk());

    send(patch("/api/v1/patients/{id}", id), supervisor, change)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("Reload")));
  }

  @Test
  void correctionNeedsReasonPermissionAndARealChange() throws Exception {
    String id = register(clerk, ", \"family_name\": \"Rules\", \"sex\": \"MALE\"");
    TestUser reader = fixtures.user(orgId);
    fixtures.grant(reader, null, Permissions.PATIENT_READ);

    send(
            patch("/api/v1/patients/{id}", id),
            clerk,
            "{\"version\": 0, \"reason\": \" \", \"changes\": {\"sex\": \"FEMALE\"}}")
        .andExpect(status().isBadRequest());
    send(
            patch("/api/v1/patients/{id}", id),
            clerk,
            "{\"version\": 0, \"reason\": \"x\", \"changes\": {\"sex\": \"MALE\"}}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field_errors.changes[0]").value("No detail was changed"));
    send(
            patch("/api/v1/patients/{id}", id),
            clerk,
            "{\"version\": 0, \"reason\": \"x\", \"changes\": {\"patient_number\": \"P1\"}}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field_errors['changes.patient_number']").exists());
    send(
            patch("/api/v1/patients/{id}", id),
            reader,
            "{\"version\": 0, \"reason\": \"x\", \"changes\": {\"sex\": \"FEMALE\"}}")
        .andExpect(status().isForbidden());
  }

  // --- review queue ------------------------------------------------------------------------------

  @Test
  void staffReportGoesToQueueAndAnotherReviewerDecides() throws Exception {
    String a =
        register(
            clerk, ", \"family_name\": \"Queue\", \"given_names\": \"India\", \"sex\": \"FEMALE\"");
    String b =
        register(
            clerk, ", \"family_name\": \"Kew\", \"given_names\": \"Juliet\", \"sex\": \"FEMALE\"");

    send(
            post("/api/v1/patients/{id}/duplicate-reports", a),
            clerk,
            "{\"candidate_patient_id\": \""
                + b
                + "\", \"reason\": \"Same mother's name and village\"}")
        .andExpect(status().isNoContent());
    send(
            post("/api/v1/patients/{id}/duplicate-reports", b),
            clerk,
            "{\"candidate_patient_id\": \"" + a + "\", \"reason\": \"again\"}")
        .andExpect(status().isConflict());

    String list =
        mvc.perform(get("/api/v1/patient-duplicate-flags").with(supervisor.signedIn()))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath(
                    "$.items[?(@.patient.id == '" + a + "')].origin", contains("STAFF_REPORT")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String flagId =
        JsonPath.<java.util.List<String>>read(list, "$.items[?(@.patient.id == '" + a + "')].id")
            .getFirst();

    send(
            post("/api/v1/patient-duplicate-flags/{id}/not-duplicate", flagId),
            supervisor,
            "{\"version\": 0, \"note\": \"Checked ID cards: different people\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NOT_DUPLICATE"))
        .andExpect(jsonPath("$.resolved_by").value(supervisor.userId().toString()));

    send(
            post("/api/v1/patient-duplicate-flags/{id}/not-duplicate", flagId),
            supervisor,
            "{\"version\": 1, \"note\": \"again\"}")
        .andExpect(status().isUnprocessableContent());
    assertThat(fixtures.auditActions(supervisor.userId())).contains("PATIENT_DUPLICATE_RESOLVED");
  }

  @Test
  void personWhoRaisedAnItemCannotDecideIt() throws Exception {
    String a = register(supervisor, ", \"family_name\": \"Selfreview\", \"sex\": \"MALE\"");
    String b = register(supervisor, ", \"family_name\": \"Other\", \"sex\": \"MALE\"");
    send(
            post("/api/v1/patients/{id}/duplicate-reports", a),
            supervisor,
            "{\"candidate_patient_id\": \"" + b + "\", \"reason\": \"looks similar\"}")
        .andExpect(status().isNoContent());
    String list =
        mvc.perform(get("/api/v1/patient-duplicate-flags").with(supervisor.signedIn()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String flagId =
        JsonPath.<java.util.List<String>>read(list, "$.items[?(@.patient.id == '" + a + "')].id")
            .getFirst();

    send(
            post("/api/v1/patient-duplicate-flags/{id}/not-duplicate", flagId),
            supervisor,
            "{\"version\": 0, \"note\": \"fine\"}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.message").value(containsString("another reviewer")));
  }

  @Test
  void pairsDecidedNotDuplicateAreNotSuggestedAgain() throws Exception {
    String fields =
        ", \"family_name\": \"Twinlike\", \"given_names\": \"Kilo\", \"birth_date\": \"2001-01-01\", \"sex\": \"MALE\", \"phone\": \"0770 444 555\"";
    String a = register(clerk, fields);
    String b =
        JsonPath.read(
            send(
                    post("/api/v1/patients"),
                    clerk,
                    "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\"%s, \"duplicate_review\": {\"reviewed_candidate_ids\": [\"%s\"], \"reason\": \"Twin brother\"}}"
                        .formatted(facility, fields, a))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");
    mvc.perform(get("/api/v1/patients/{id}/possible-duplicates", b).with(clerk.signedIn()))
        .andExpect(jsonPath("$[*].patient.id", contains(a)));

    String list =
        mvc.perform(get("/api/v1/patient-duplicate-flags").with(supervisor.signedIn()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String flagId =
        JsonPath.<java.util.List<String>>read(list, "$.items[?(@.patient.id == '" + b + "')].id")
            .getFirst();
    send(
            post("/api/v1/patient-duplicate-flags/{id}/not-duplicate", flagId),
            supervisor,
            "{\"version\": 0, \"note\": \"Twins, confirmed\"}")
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/patients/{id}/possible-duplicates", b).with(clerk.signedIn()))
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void queueNeedsReviewPermission() throws Exception {
    mvc.perform(get("/api/v1/patient-duplicate-flags").with(clerk.signedIn()))
        .andExpect(status().isForbidden());
  }
}
