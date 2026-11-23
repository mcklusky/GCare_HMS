package com.gcare.hms.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import com.gcare.hms.testsupport.IdentityFixtures;
import com.gcare.hms.testsupport.IdentityFixtures.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.hamcrest.Matchers;
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
 * Versioned configuration with two-person approval and facility branding (task 5.2; FR-042;
 * docs/21). Triage codes, labels and limits here are test fixtures, not clinical rules.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConfigurationTests {

  /** A 1×1 PNG. */
  private static final String PNG =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

  private static final String TRIAGE =
      "{\"urgency_levels\": [{\"code\": \"T_FIRST\", \"label\": \"Test first\", \"rank\": 1},"
          + " {\"code\": \"T_LATER\", \"label\": \"Test later\", \"rank\": 2}],"
          + " \"alarm_limits\": {\"PULSE_BPM\": {\"high\": 150}}}";

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private JdbcClient jdbc;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private UUID opd;
  private TestUser lead;
  private TestUser lead2;
  private TestUser admin;
  private TestUser admin2;
  private TestUser clerk;
  private TestUser nurse;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    opd = fixtures.department(orgId, facility, "OPD");
    lead = user(Permissions.CONFIG_CLINICAL_MANAGE);
    lead2 = user(Permissions.CONFIG_CLINICAL_MANAGE);
    admin = user(Permissions.CONFIG_ORGANIZATION_MANAGE);
    admin2 = user(Permissions.CONFIG_ORGANIZATION_MANAGE);
    clerk =
        user(
            Permissions.PATIENT_READ,
            Permissions.PATIENT_REGISTER,
            Permissions.ENCOUNTER_READ,
            Permissions.ENCOUNTER_OPEN);
    nurse =
        user(
            Permissions.ENCOUNTER_READ,
            Permissions.OBSERVATION_READ,
            Permissions.OBSERVATION_RECORD);
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

  private ResultActions propose(TestUser user, String key, UUID facilityId, String value)
      throws Exception {
    String f = facilityId == null ? "" : "\"facility_id\": \"%s\", ".formatted(facilityId);
    return send(
        post("/api/v1/settings/{key}/versions", key),
        user,
        "{%s\"value\": %s, \"reason\": \"Agreed at meeting\"}".formatted(f, value));
  }

  private ResultActions decide(TestUser user, String id, String decision, long version)
      throws Exception {
    return send(
        post("/api/v1/settings/versions/{id}/" + decision, id),
        user,
        "{\"version\": %d, \"note\": \"Checked\"}".formatted(version));
  }

  private String visit() throws Exception {
    String patient =
        read(
            send(
                    post("/api/v1/patients"),
                    clerk,
                    "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"Cfg%s\", \"given_names\": \"Case\", \"birth_date\": \"1970-01-01\", \"sex\": \"MALE\"}"
                        .formatted(facility, UUID.randomUUID().toString().substring(0, 6)))
                .andExpect(status().isCreated()),
            "$.id");
    return read(
        send(
                post("/api/v1/encounters"),
                clerk,
                "{\"patient_id\": \"%s\", \"facility_id\": \"%s\", \"department_id\": \"%s\", \"encounter_type\": \"NEW_VISIT\"}"
                    .formatted(patient, facility, opd))
            .andExpect(status().isCreated()),
        "$.encounter.id");
  }

  @Test
  void triageScaleTakesEffectOnlyAfterASecondClinicalLeadApproves() throws Exception {
    String e = visit();
    ResultActions proposed =
        propose(lead, "TRIAGE", null, TRIAGE)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PROPOSED"))
            .andExpect(jsonPath("$.version_number").value(1));
    String id = read(proposed, "$.id");
    send(get("/api/v1/triage/settings"), nurse, "")
        .andExpect(jsonPath("$.urgency_levels.length()").value(0));
    propose(lead2, "TRIAGE", null, TRIAGE).andExpect(status().isConflict());

    decide(lead, id, "approve", 0).andExpect(status().isUnprocessableContent());
    decide(admin, id, "approve", 0).andExpect(status().isNotFound());
    decide(lead2, id, "approve", 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    send(get("/api/v1/triage/settings"), nurse, "")
        .andExpect(
            jsonPath("$.urgency_levels[*].code").value(Matchers.contains("T_FIRST", "T_LATER")));
    send(
            post("/api/v1/encounters/{id}/observations", e),
            nurse,
            "{\"urgency\": \"T_FIRST\", \"pulse_bpm\": 160}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.flags[0].measure").value("PULSE_BPM"));

    // A new version supersedes the old one; the old one stays on record.
    String v2 =
        read(
            propose(lead2, "TRIAGE", null, "{\"urgency_levels\": [], \"alarm_limits\": {}}")
                .andExpect(status().isCreated()),
            "$.id");
    decide(lead, v2, "approve", 0).andExpect(status().isOk());
    send(get("/api/v1/settings/TRIAGE/versions"), lead, "")
        .andExpect(jsonPath("$[*].status").value(Matchers.contains("ACTIVE", "SUPERSEDED")));
    send(get("/api/v1/triage/settings"), nurse, "")
        .andExpect(jsonPath("$.urgency_levels.length()").value(0));

    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE config_versions SET value = '{}'::jsonb WHERE id = ?::uuid")
                        .params(id)
                        .update()))
        .isInstanceOf(DataAccessException.class);
    assertThat(fixtures.auditActions(lead2.userId())).contains("CONFIG_APPROVED");
  }

  @Test
  void invalidValuesAreRefused() throws Exception {
    propose(
            lead,
            "TRIAGE",
            null,
            "{\"urgency_levels\": [{\"code\": \"A\", \"label\": \"x\", \"rank\": 1}, {\"code\": \"A\", \"label\": \"y\", \"rank\": 2}]}")
        .andExpect(status().isBadRequest());
    propose(lead, "TRIAGE", null, "{\"alarm_limits\": {\"NOT_A_MEASURE\": {\"high\": 1}}}")
        .andExpect(status().isBadRequest());
    propose(
            lead,
            "TRIAGE",
            null,
            "{\"alarm_limits\": {\"PULSE_BPM\": {\"low\": 100, \"high\": 50}}}")
        .andExpect(status().isBadRequest());
    propose(lead, "TRIAGE", facility, TRIAGE).andExpect(status().isBadRequest());
    propose(admin, "FACILITY_BRANDING", null, "{\"display_name\": \"X\"}")
        .andExpect(status().isBadRequest());
    propose(admin, "FACILITY_BRANDING", facility, "{\"colour\": \"red\"}")
        .andExpect(status().isBadRequest());
    propose(admin, "BILLING_OPTIONS", null, "{\"billable_visit_days\": 91}")
        .andExpect(status().isBadRequest());
    propose(lead, "FACILITY_BRANDING", facility, "{\"display_name\": \"X\"}")
        .andExpect(status().isForbidden());
    propose(admin, "NO_SUCH", null, "{}").andExpect(status().isNotFound());
    send(get("/api/v1/settings"), lead, "")
        .andExpect(jsonPath("$[*].key").value(Matchers.contains("TRIAGE")));
    send(get("/api/v1/settings"), clerk, "").andExpect(status().isForbidden());
  }

  @Test
  void brandingAndLogoAreShownOnlyOnceApproved() throws Exception {
    send(
            post("/api/v1/facilities/{id}/logos", facility),
            admin,
            "{\"content_type\": \"image/png\", \"data_base64\": \"bm90IGFuIGltYWdl\"}")
        .andExpect(status().isBadRequest());
    String logo =
        read(
            send(
                    post("/api/v1/facilities/{id}/logos", facility),
                    admin,
                    "{\"content_type\": \"image/png\", \"data_base64\": \"%s\"}".formatted(PNG))
                .andExpect(status().isCreated()),
            "$.id");
    UUID other = fixtures.facility(orgId, "GH2");
    propose(
            admin,
            "FACILITY_BRANDING",
            facility,
            "{\"display_name\": \"X\", \"logo_id\": \"%s\"}".formatted(UUID.randomUUID()))
        .andExpect(status().isBadRequest());

    String id =
        read(
            propose(
                    admin,
                    "FACILITY_BRANDING",
                    facility,
                    "{\"display_name\": \"Test General Hospital\", \"address_lines\": [\"1 Test Road\"], \"phone\": \"000\", \"registration_number\": \"T-123\", \"receipt_footer\": \"Thank you\", \"logo_id\": \"%s\"}"
                        .formatted(logo))
                .andExpect(status().isCreated()),
            "$.id");
    send(get("/api/v1/facilities/{id}/branding", facility), nurse, "")
        .andExpect(jsonPath("$.configured").value(false))
        .andExpect(jsonPath("$.display_name").value("Test Facility (synthetic)"));
    send(get("/api/v1/facility-logos/{id}", logo), nurse, "").andExpect(status().isNotFound());
    send(get("/api/v1/facility-logos/{id}", logo), admin, "").andExpect(status().isOk());

    decide(admin2, id, "approve", 0).andExpect(status().isOk());
    send(get("/api/v1/facilities/{id}/branding", facility), nurse, "")
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.display_name").value("Test General Hospital"))
        .andExpect(jsonPath("$.address_lines[0]").value("1 Test Road"))
        .andExpect(jsonPath("$.logo_id").value(logo));
    send(get("/api/v1/facility-logos/{id}", logo), nurse, "")
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/png"));
    send(get("/api/v1/facilities/{id}/branding", other), nurse, "")
        .andExpect(status().isNotFound());
  }

  @Test
  void billingLookBackFollowsTheApprovedOption() throws Exception {
    TestUser cashier = user(Permissions.INVOICE_MANAGE);
    String e = visit();
    OrganizationContext.callAsSchemaOwner(() ->
        jdbc.sql("UPDATE encounters SET arrived_at = now() - interval '10 days' WHERE id = ?::uuid")
            .params(e)
            .update());
    send(get("/api/v1/invoices/billable-visits"), cashier, "")
        .andExpect(jsonPath("$.length()").value(0));
    String id =
        read(
            propose(admin, "BILLING_OPTIONS", null, "{\"billable_visit_days\": 30}")
                .andExpect(status().isCreated()),
            "$.id");
    send(get("/api/v1/settings/pending"), admin2, "").andExpect(jsonPath("$[0].id").value(id));
    decide(admin2, id, "approve", 0).andExpect(status().isOk());
    send(get("/api/v1/invoices/billable-visits"), cashier, "")
        .andExpect(jsonPath("$[0].encounter_id").value(e));
  }

  @Test
  void configurationTablesAreIsolatedAndCannotBeDeleted() throws Exception {
    propose(lead, "TRIAGE", null, TRIAGE).andExpect(status().isCreated());
    send(
            post("/api/v1/facilities/{id}/logos", facility),
            admin,
            "{\"content_type\": \"image/png\", \"data_base64\": \"%s\"}".formatted(PNG))
        .andExpect(status().isCreated());
    UUID otherOrg = fixtures.organization();
    for (String table : new String[] {"config_versions", "facility_logos"}) {
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
