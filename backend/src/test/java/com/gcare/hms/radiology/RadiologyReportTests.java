package com.gcare.hms.radiology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import com.gcare.hms.testsupport.IdentityFixtures;
import com.gcare.hms.testsupport.IdentityFixtures.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/**
 * Radiology reports and critical results (task 6.4; FR-012, FR-013; docs/26). Report text, names
 * and orders are test fixtures; no clinical thresholds are involved.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RadiologyReportTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private JdbcClient jdbc;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private TestUser clerk;
  private TestUser clinician;
  private TestUser nurse;
  private TestUser radiographer;
  private TestUser radiologist;
  private TestUser radiologist2;
  private int orders;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    clerk =
        user(
            Permissions.PATIENT_READ,
            Permissions.PATIENT_REGISTER,
            Permissions.ENCOUNTER_READ,
            Permissions.ENCOUNTER_OPEN);
    clinician =
        user(
            Permissions.ENCOUNTER_READ,
            Permissions.ORDER_READ,
            Permissions.ORDER_CREATE,
            Permissions.RADIOLOGY_REPORT_READ,
            Permissions.CRITICAL_RESULT_ACKNOWLEDGE);
    nurse = user(Permissions.ENCOUNTER_READ, Permissions.RADIOLOGY_REPORT_READ);
    radiographer =
        user(
            Permissions.ORDER_READ,
            Permissions.ORDER_PROCESS,
            Permissions.IMAGING_WORKLIST,
            Permissions.IMAGING_LINK);
    String[] rad = {
      Permissions.ORDER_READ,
      Permissions.IMAGING_WORKLIST,
      Permissions.RADIOLOGY_REPORT_READ,
      Permissions.RADIOLOGY_REPORT_WRITE,
      Permissions.CRITICAL_RESULT_OVERSIGHT
    };
    radiologist = user(rad);
    radiologist2 = user(rad);
  }

  private TestUser user(String... permissions) {
    TestUser u = fixtures.user(orgId);
    fixtures.grant(u, facility, permissions);
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

  private static long version(ResultActions r) throws Exception {
    return Long.parseLong(read(r, "$.version"));
  }

  /** An imaging order on a new visit; returns {order id, encounter id}. */
  private String[] imagingOrder(boolean examined) throws Exception {
    String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    UUID rad = fixtures.department(orgId, facility, "RAD" + tag);
    TestUser admin = user(Permissions.ORDER_CATALOG_MANAGE);
    String item =
        read(
            send(
                    post("/api/v1/order-catalog"),
                    admin,
                    "{\"code\": \"T-CT-%s\", \"name\": \"Test head CT\", \"category\": \"IMAGING\", \"performing_department_id\": \"%s\"}"
                        .formatted(tag, rad))
                .andExpect(status().isCreated()),
            "$.id");
    orders++;
    String patient =
        read(
            send(
                    post("/api/v1/patients"),
                    clerk,
                    "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"Rep%s\", \"given_names\": \"Test\", \"birth_date\": \"196%d-02-02\", \"sex\": \"FEMALE\"}"
                        .formatted(facility, tag, orders))
                .andExpect(status().isCreated()),
            "$.id");
    String encounter =
        read(
            send(
                    post("/api/v1/encounters"),
                    clerk,
                    "{\"patient_id\": \"%s\", \"facility_id\": \"%s\", \"department_id\": \"%s\", \"encounter_type\": \"NEW_VISIT\"}"
                        .formatted(patient, facility, rad))
                .andExpect(status().isCreated()),
            "$.encounter.id");
    String order =
        read(
            send(
                    post("/api/v1/encounters/{id}/orders", encounter),
                    clinician,
                    "{\"catalog_item_id\": \"%s\", \"priority\": \"URGENT\"}".formatted(item))
                .andExpect(status().isCreated()),
            "$.id");
    send(get("/api/v1/imaging/worklist"), radiographer, "").andExpect(status().isOk());
    if (examined) {
      send(post("/api/v1/imaging/orders/{id}/arrived", order), radiographer, "{\"version\": 0}")
          .andExpect(status().isOk());
      send(post("/api/v1/imaging/orders/{id}/examined", order), radiographer, "{\"version\": 1}")
          .andExpect(status().isOk());
    }
    return new String[] {order, encounter};
  }

  private ResultActions draft(String order, TestUser by, String body) throws Exception {
    return send(post("/api/v1/imaging/orders/{id}/reports", order), by, body);
  }

  @Test
  void radiologistsDraftPrivatelySignOnceAndCorrectByAmendment() throws Exception {
    String[] o = imagingOrder(false);
    String order = o[0];
    send(get("/api/v1/encounters/{id}/imaging-results", o[1]), clinician, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].report_state").value("NOT_YET_REPORTED"))
        .andExpect(jsonPath("$[0].report").doesNotExist());
    draft(order, radiologist, "{\"sections\": {\"findings\": \"x\"}}")
        .andExpect(status().isUnprocessableContent());
    send(post("/api/v1/imaging/orders/{id}/arrived", order), radiographer, "{\"version\": 0}");
    send(post("/api/v1/imaging/orders/{id}/examined", order), radiographer, "{\"version\": 1}");
    send(get("/api/v1/radiology/reporting"), radiologist, "")
        .andExpect(jsonPath("$[0].order_id").value(order));

    ResultActions d =
        draft(
                order,
                radiologist,
                "{\"sections\": {\"technique\": \"Test technique\", \"findings\": \"Test findings\"}}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"));
    String report = read(d, "$.id");
    draft(order, radiologist2, "{}").andExpect(status().isConflict());
    // Drafts: radiologists only; only the author edits or signs.
    send(get("/api/v1/imaging/orders/{id}/reports", order), clinician, "")
        .andExpect(jsonPath("$.length()").value(0));
    send(get("/api/v1/imaging/orders/{id}/reports", order), radiologist2, "")
        .andExpect(jsonPath("$.length()").value(1));
    send(
            put("/api/v1/radiology-reports/{id}", report),
            radiologist2,
            "{\"version\": 0, \"sections\": {\"findings\": \"Other\"}}")
        .andExpect(status().isUnprocessableContent());
    send(post("/api/v1/radiology-reports/{id}/sign", report), radiologist, "{\"version\": 0}")
        .andExpect(status().isBadRequest());
    ResultActions saved =
        send(
                put("/api/v1/radiology-reports/{id}", report),
                radiologist,
                "{\"version\": 0, \"sections\": {\"technique\": \"Test technique\", \"findings\": \"Test findings\", \"impression\": \"Test impression\"}}")
            .andExpect(status().isOk());
    send(
            post("/api/v1/radiology-reports/{id}/sign", report),
            radiologist,
            "{\"version\": %d}".formatted(version(saved)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SIGNED"));
    send(
            put("/api/v1/radiology-reports/{id}", report),
            radiologist,
            "{\"version\": 2, \"sections\": {\"findings\": \"Changed\"}}")
        .andExpect(status().isUnprocessableContent());
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE radiology_reports SET findings = 'x' WHERE id = ?::uuid")
                        .params(report)
                        .update()))
        .isInstanceOf(DataAccessException.class);
    send(get("/api/v1/encounters/{id}/imaging-results", o[1]), nurse, "")
        .andExpect(jsonPath("$[0].report_state").value("REPORTED"))
        .andExpect(jsonPath("$[0].report.impression").value("Test impression"));

    // Amendment: needs a reason, starts from the signed text, keeps the original.
    draft(order, radiologist2, "{}").andExpect(status().isBadRequest());
    ResultActions amend =
        draft(order, radiologist2, "{\"amendment_reason\": \"Laterality corrected\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.amends_report_id").value(report))
            .andExpect(jsonPath("$.findings").value("Test findings"));
    String amendment = read(amend, "$.id");
    send(post("/api/v1/radiology-reports/{id}/sign", amendment), radiologist2, "{\"version\": 0}")
        .andExpect(status().isOk());
    send(get("/api/v1/encounters/{id}/imaging-results", o[1]), clinician, "")
        .andExpect(jsonPath("$[0].report_state").value("AMENDED"))
        .andExpect(jsonPath("$[0].report.amendment_reason").value("Laterality corrected"))
        .andExpect(jsonPath("$[0].earlier_reports.length()").value(1))
        .andExpect(jsonPath("$[0].earlier_reports[0].id").value(report));
    assertThat(fixtures.auditActions(radiologist.userId()))
        .contains("RADIOLOGY_REPORT_STARTED", "RADIOLOGY_REPORT_SAVED", "RADIOLOGY_REPORT_SIGNED");
    assertThat(fixtures.auditActions(radiologist2.userId())).contains("RADIOLOGY_REPORT_AMENDED");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE organization_id = ? AND metadata::text LIKE '%Test findings%'",
                orgId))
        .isZero();
  }

  @Test
  void criticalResultsNeedTheDocumentedContactAndAClinicianAcknowledgement() throws Exception {
    String order = imagingOrder(true)[0];
    String report =
        read(
            draft(
                    order,
                    radiologist,
                    "{\"sections\": {\"findings\": \"Test finding\", \"impression\": \"Test urgent impression\", \"critical\": true}}")
                .andExpect(status().isCreated()),
            "$.id");
    send(post("/api/v1/radiology-reports/{id}/sign", report), radiologist, "{\"version\": 0}")
        .andExpect(status().isBadRequest());
    // A contact time before the examination was even ordered is a typing error, not a 500.
    for (String wrong : new String[] {"1900-01-01T00:00:00Z", "-5000-01-01T00:00:00Z"}) {
      send(
              post("/api/v1/radiology-reports/{id}/sign", report),
              radiologist,
              "{\"version\": 0, \"communication\": {\"communicated_to\": \"Dr Test\", \"method\": \"PHONE\", \"communicated_at\": \"%s\"}}"
                  .formatted(wrong))
          .andExpect(status().isBadRequest());
    }
    String when = Instant.now().minus(5, ChronoUnit.MINUTES).toString();
    send(
            post("/api/v1/radiology-reports/{id}/sign", report),
            radiologist,
            "{\"version\": 0, \"communication\": {\"communicated_to\": \"Dr Test (fictional), ward\", \"method\": \"PHONE\", \"communicated_at\": \"%s\"}}"
                .formatted(when))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.critical_result.communication_method").value("PHONE"))
        .andExpect(jsonPath("$.critical_result.acknowledged_at").doesNotExist());

    send(get("/api/v1/critical-results"), radiologist2, "")
        .andExpect(jsonPath("$.length()").value(1));
    ResultActions open =
        send(get("/api/v1/critical-results"), clinician, "")
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].report_id").value(report));
    send(get("/api/v1/critical-results"), nurse, "").andExpect(status().isForbidden());
    send(
            post("/api/v1/critical-results/{id}/acknowledge", report),
            radiologist2,
            "{\"version\": 0}")
        .andExpect(status().isForbidden());
    send(
            post("/api/v1/critical-results/{id}/acknowledge", report),
            clinician,
            "{\"version\": %s, \"note\": \"Patient recalled\"}"
                .formatted(read(open, "$[0].version")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acknowledged_at").exists());
    send(post("/api/v1/critical-results/{id}/acknowledge", report), clinician, "{\"version\": 1}")
        .andExpect(status().isUnprocessableContent());
    send(get("/api/v1/critical-results"), clinician, "").andExpect(jsonPath("$.length()").value(0));
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql(
                            "UPDATE critical_results SET acknowledgement_note = 'x' WHERE report_id = ?::uuid")
                        .params(report)
                        .update()))
        .isInstanceOf(DataAccessException.class);

    // A report that is not critical cannot carry a contact record.
    String other = imagingOrder(true)[0];
    String plain =
        read(
            draft(
                other, radiologist, "{\"sections\": {\"findings\": \"F\", \"impression\": \"I\"}}"),
            "$.id");
    send(
            post("/api/v1/radiology-reports/{id}/sign", plain),
            radiologist,
            "{\"version\": 0, \"communication\": {\"communicated_to\": \"x\", \"method\": \"PHONE\", \"communicated_at\": \"%s\"}}"
                .formatted(when))
        .andExpect(status().isBadRequest());
    assertThat(fixtures.auditActions(clinician.userId())).contains("CRITICAL_RESULT_ACKNOWLEDGED");
  }

  @Test
  void amendingACriticalReportIsCommunicatedAgainAndReplacesTheOpenResult() throws Exception {
    String order = imagingOrder(true)[0];
    String when = Instant.now().minus(5, ChronoUnit.MINUTES).toString();
    String contact =
        "\"communication\": {\"communicated_to\": \"Dr Test (fictional)\", \"method\": \"PHONE\", \"communicated_at\": \"%s\"}"
            .formatted(when);
    String first =
        read(
            draft(
                    order,
                    radiologist,
                    "{\"sections\": {\"findings\": \"F\", \"impression\": \"Urgent\", \"critical\": true}}")
                .andExpect(status().isCreated()),
            "$.id");
    send(
            post("/api/v1/radiology-reports/{id}/sign", first),
            radiologist,
            "{\"version\": 0, %s}".formatted(contact))
        .andExpect(status().isOk());

    // The amendment says the finding is not critical after all: the change must be told again.
    ResultActions amendment =
        draft(order, radiologist, "{\"amendment_reason\": \"Second look: not critical\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.communication_required").value(true));
    String second = read(amendment, "$.id");
    ResultActions saved =
        send(
                put("/api/v1/radiology-reports/{id}", second),
                radiologist,
                "{\"version\": 0, \"sections\": {\"findings\": \"F\", \"impression\": \"Not urgent\", \"critical\": false}}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.communication_required").value(true));
    long v = version(saved);
    send(
            post("/api/v1/radiology-reports/{id}/sign", second),
            radiologist,
            "{\"version\": %d}".formatted(v))
        .andExpect(status().isBadRequest());
    send(
            post("/api/v1/radiology-reports/{id}/sign", second),
            radiologist,
            "{\"version\": %d, %s}".formatted(v, contact))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.critical_result.kind").value("CHANGED"));

    // One open item for the clinician: the latest; the first was replaced, not acknowledged.
    ResultActions open =
        send(get("/api/v1/critical-results"), clinician, "")
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].report_id").value(second))
            .andExpect(jsonPath("$[0].kind").value("CHANGED"));
    send(
            post("/api/v1/critical-results/{id}/acknowledge", first),
            clinician,
            "{\"version\": 1, \"note\": \"x\"}")
        .andExpect(status().isUnprocessableContent());
    send(
            post("/api/v1/critical-results/{id}/acknowledge", second),
            clinician,
            "{\"version\": %s, \"note\": \"Plan changed back\"}"
                .formatted(read(open, "$[0].version")))
        .andExpect(status().isOk());
    // The database keeps a replaced result closed.
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql(
                            "UPDATE critical_results SET superseded_at = NULL, superseded_by = NULL WHERE report_id = ?::uuid")
                        .params(first)
                        .update()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void reportsStayWithinTheFacilityAndOrganization() throws Exception {
    String[] o = imagingOrder(true);
    UUID other = fixtures.facility(orgId, "GH2");
    TestUser elsewhere = fixtures.user(orgId);
    fixtures.grant(
        elsewhere, other, Permissions.RADIOLOGY_REPORT_READ, Permissions.RADIOLOGY_REPORT_WRITE);
    send(get("/api/v1/imaging/orders/{id}/reports", o[0]), elsewhere, "")
        .andExpect(status().isNotFound());
    draft(o[0], elsewhere, "{}").andExpect(status().isNotFound());
    send(get("/api/v1/encounters/{id}/imaging-results", o[1]), elsewhere, "")
        .andExpect(jsonPath("$.length()").value(0));
    // Lists are scoped before their limit: the other facility's radiologist sees none of this work.
    send(get("/api/v1/radiology/reporting"), elsewhere, "")
        .andExpect(jsonPath("$[?(@.order_id == '" + o[0] + "')]").isEmpty());
    send(get("/api/v1/radiology/reporting"), radiologist, "")
        .andExpect(jsonPath("$[?(@.order_id == '" + o[0] + "')]").isNotEmpty());
    // Reading a visit's imaging results is audited against that visit.
    send(get("/api/v1/encounters/{id}/imaging-results", o[1]), nurse, "")
        .andExpect(status().isOk());
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE actor_id = ? AND action = 'IMAGING_RESULTS_VIEWED'"
                    + " AND resource_type = 'Encounter' AND resource_id = ?",
                nurse.userId(),
                o[1]))
        .isEqualTo(1);
    send(get("/api/v1/radiology/reporting"), clinician, "").andExpect(status().isForbidden());
    UUID otherOrg = fixtures.organization();
    assertThat(
            OrganizationContext.callAs(
                otherOrg,
                () ->
                    jdbc.sql("SELECT count(*) FROM radiology_reports").query(Long.class).single()))
        .isZero();
  }
}
