package com.gcare.hms.encounter;

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

/** Clinical notes (draft, sign, amend) and visit diagnoses (task 3.2; FR-008). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ClinicalNoteTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private JdbcClient jdbc;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private UUID opd;
  private TestUser clerk;
  private TestUser nurse;
  private TestUser doctor;
  private TestUser doctor2;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    opd = fixtures.department(orgId, facility, "OPD");
    clerk = fixtures.user(orgId);
    fixtures.grant(
        clerk,
        facility,
        Permissions.PATIENT_READ,
        Permissions.PATIENT_REGISTER,
        Permissions.ENCOUNTER_READ,
        Permissions.ENCOUNTER_OPEN);
    nurse = fixtures.user(orgId);
    fixtures.grant(nurse, facility, Permissions.ENCOUNTER_READ, Permissions.CLINICAL_NOTE_READ);
    doctor = clinician();
    doctor2 = clinician();
  }

  private TestUser clinician() {
    TestUser u = fixtures.user(orgId);
    fixtures.grant(
        u,
        facility,
        Permissions.PATIENT_READ,
        Permissions.ENCOUNTER_READ,
        Permissions.ENCOUNTER_MANAGE,
        Permissions.ENCOUNTER_REOPEN,
        Permissions.CLINICAL_NOTE_READ,
        Permissions.CLINICAL_NOTE_WRITE);
    return u;
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

  /** A visit in consultation with {@code doctor}. */
  private String visit() throws Exception {
    String patient =
        read(
            send(
                    post("/api/v1/patients"),
                    clerk,
                    "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"Note%s\", \"given_names\": \"Case\", \"birth_date\": \"1970-01-01\", \"sex\": \"MALE\"}"
                        .formatted(facility, UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().isCreated()),
            "$.id");
    String id =
        read(
            send(
                    post("/api/v1/encounters"),
                    clerk,
                    "{\"patient_id\": \"%s\", \"facility_id\": \"%s\", \"department_id\": \"%s\", \"encounter_type\": \"NEW_VISIT\"}"
                        .formatted(patient, facility, opd))
                .andExpect(status().isCreated()),
            "$.encounter.id");
    send(post("/api/v1/encounters/{id}/call", id), doctor, "{\"version\": 0}")
        .andExpect(status().isOk());
    return id;
  }

  private String draft(TestUser user, String encounter, String body) throws Exception {
    return read(
        send(post("/api/v1/encounters/{id}/notes", encounter), user, body)
            .andExpect(status().isCreated()),
        "$.id");
  }

  private ResultActions sign(TestUser user, String note, long version) throws Exception {
    return send(
        post("/api/v1/notes/{id}/sign", note), user, "{\"version\": %d}".formatted(version));
  }

  // --- notes -------------------------------------------------------------------------------------

  @Test
  void draftIsPrivateUntilSignedThenNeverChanges() throws Exception {
    String e = visit();
    String note = draft(doctor, e, "{\"history\": \"Cough for 3 days\"}");

    // Only the author sees or edits the draft.
    send(get("/api/v1/encounters/{id}/notes", e), nurse, "")
        .andExpect(jsonPath("$.length()").value(0));
    send(patch("/api/v1/notes/{id}", note), doctor2, "{\"version\": 0, \"plan\": \"x\"}")
        .andExpect(status().isNotFound());
    send(
            patch("/api/v1/notes/{id}", note),
            doctor,
            "{\"version\": 0, \"history\": \"Cough for 3 days\", \"assessment\": \"Viral illness\", \"plan\": \"Fluids\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    send(patch("/api/v1/notes/{id}", note), doctor, "{\"version\": 0, \"plan\": \"stale\"}")
        .andExpect(status().isConflict());

    sign(doctor, note, 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SIGNED"))
        .andExpect(jsonPath("$.signed_at").exists())
        .andExpect(jsonPath("$.author.id").value(doctor.userId().toString()));
    send(get("/api/v1/encounters/{id}/notes", e), nurse, "")
        .andExpect(jsonPath("$[0].assessment").value("Viral illness"));

    // Signed: no more edits, through the API or directly in the database.
    send(patch("/api/v1/notes/{id}", note), doctor, "{\"version\": 2, \"plan\": \"changed\"}")
        .andExpect(status().isUnprocessableContent());
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE clinical_notes SET plan = 'changed' WHERE id = ?")
                        .params(UUID.fromString(note))
                        .update()))
        .isInstanceOf(DataAccessException.class);

    assertThat(fixtures.auditActions(doctor.userId()))
        .contains("NOTE_DRAFT_STARTED", "NOTE_DRAFT_SAVED", "NOTE_SIGNED");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE metadata::text LIKE '%Cough%' OR metadata::text LIKE '%Viral%'"))
        .isZero();
  }

  @Test
  void emptyNoteCannotBeSignedAndDraftsCanBeDiscarded() throws Exception {
    String e = visit();
    String empty = draft(doctor, e, "{\"history\": \"   \"}");
    sign(doctor, empty, 0).andExpect(status().isBadRequest());
    send(post("/api/v1/notes/{id}/discard", empty), doctor, "{\"version\": 0}")
        .andExpect(status().isNoContent());
    send(get("/api/v1/encounters/{id}/notes", e), doctor, "")
        .andExpect(jsonPath("$.length()").value(0));
    sign(doctor, empty, 1).andExpect(status().isUnprocessableContent());
  }

  @Test
  void amendmentsCorrectSignedNotesEvenAfterTheVisitCloses() throws Exception {
    String e = visit();
    String original = draft(doctor, e, "{\"plan\": \"Review in 2 weeks\"}");
    sign(doctor, original, 0).andExpect(status().isOk());
    send(post("/api/v1/encounters/{id}/close", e), doctor, "{\"version\": 1}")
        .andExpect(status().isOk());

    // A new note needs an open visit; an amendment needs a signed original and a reason.
    send(post("/api/v1/encounters/{id}/notes", e), doctor, "{\"plan\": \"late\"}")
        .andExpect(status().isUnprocessableContent());
    send(
            post("/api/v1/encounters/{id}/notes", e),
            doctor2,
            "{\"plan\": \"x\", \"amends_note_id\": \"%s\"}".formatted(original))
        .andExpect(status().isBadRequest());
    String amendment =
        draft(
            doctor2,
            e,
            "{\"plan\": \"Review in 1 week\", \"amends_note_id\": \"%s\", \"amendment_reason\": \"Wrong interval written\"}"
                .formatted(original));
    sign(doctor2, amendment, 0).andExpect(status().isOk());

    send(get("/api/v1/encounters/{id}/notes", e), nurse, "")
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(original))
        .andExpect(jsonPath("$[0].plan").value("Review in 2 weeks"))
        .andExpect(jsonPath("$[0].amended_by[0]").value(amendment))
        .andExpect(jsonPath("$[1].author.id").value(doctor2.userId().toString()))
        .andExpect(jsonPath("$[1].amendment_reason").value("Wrong interval written"));
    assertThat(fixtures.auditActions(doctor2.userId())).contains("NOTE_AMENDMENT_SIGNED");
  }

  @Test
  void permissionsAndScopeAreEnforced() throws Exception {
    String e = visit();
    send(post("/api/v1/encounters/{id}/notes", e), nurse, "{\"history\": \"x\"}")
        .andExpect(status().isForbidden());
    send(get("/api/v1/encounters/{id}/notes", e), clerk, "").andExpect(status().isForbidden());
    UUID other = fixtures.facility(orgId, "OTHER");
    TestUser elsewhere = fixtures.user(orgId);
    fixtures.grant(
        elsewhere, other, Permissions.CLINICAL_NOTE_READ, Permissions.CLINICAL_NOTE_WRITE);
    send(get("/api/v1/encounters/{id}/notes", e), elsewhere, "").andExpect(status().isNotFound());
    send(post("/api/v1/encounters/{id}/notes", e), elsewhere, "{\"history\": \"x\"}")
        .andExpect(status().isNotFound());
  }

  // --- diagnoses ---------------------------------------------------------------------------------

  @Test
  void diagnosesAreFreeTextWithoutCodesAndCorrectedByMarking() throws Exception {
    String e = visit();
    String d =
        read(
            send(
                    post("/api/v1/encounters/{id}/diagnoses", e),
                    doctor,
                    "{\"description\": \"Upper respiratory infection\", \"certainty\": \"PROVISIONAL\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.recorded_by.id").value(doctor.userId().toString())),
            "$.id");
    send(
            post("/api/v1/encounters/{id}/diagnoses", e),
            nurse,
            "{\"description\": \"x\", \"certainty\": \"CONFIRMED\"}")
        .andExpect(status().isForbidden());
    send(
            post("/api/v1/encounters/{id}/diagnoses", e),
            doctor,
            "{\"description\": \" \", \"certainty\": \"CONFIRMED\"}")
        .andExpect(status().isBadRequest());

    send(
            post("/api/v1/diagnoses/{id}/entered-in-error", d),
            doctor,
            "{\"reason\": \"Wrong visit\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ENTERED_IN_ERROR"))
        .andExpect(jsonPath("$.description").value("Upper respiratory infection"));
    send(get("/api/v1/encounters/{id}/diagnoses", e), nurse, "")
        .andExpect(jsonPath("$[0].status").value("ENTERED_IN_ERROR"));
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE encounter_diagnoses SET description = 'x' WHERE id = ?")
                        .params(UUID.fromString(d))
                        .update()))
        .isInstanceOf(DataAccessException.class);

    send(post("/api/v1/encounters/{id}/close", e), doctor, "{\"version\": 1}")
        .andExpect(status().isOk());
    send(
            post("/api/v1/encounters/{id}/diagnoses", e),
            doctor,
            "{\"description\": \"Late\", \"certainty\": \"CONFIRMED\"}")
        .andExpect(status().isUnprocessableContent());
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE metadata::text LIKE '%respiratory%'"))
        .isZero();
  }

  @Test
  void notesAndDiagnosesAreIsolatedAndCannotBeDeleted() throws Exception {
    String e = visit();
    draft(doctor, e, "{\"history\": \"x\"}");
    send(
            post("/api/v1/encounters/{id}/diagnoses", e),
            doctor,
            "{\"description\": \"x\", \"certainty\": \"CONFIRMED\"}")
        .andExpect(status().isCreated());
    UUID otherOrg = fixtures.organization();
    for (String table : new String[] {"clinical_notes", "encounter_diagnoses"}) {
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
