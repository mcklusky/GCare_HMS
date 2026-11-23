package com.gcare.hms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import com.gcare.hms.testsupport.IdentityFixtures;
import com.gcare.hms.testsupport.IdentityFixtures.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Operational reports, dashboard and CSV export (task 5.1; FR-024, FR-025; docs/20). All data is
 * synthetic; prices are fixtures.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReportTests {

  /** UTC+14, so a facility-local day differs from the UTC day for part of every day. */
  private static final String ZONE = "Pacific/Kiritimati";

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private JdbcClient jdbc;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private UUID opd;
  private LocalDate today;
  private TestUser clerk;
  private TestUser doctor;
  private TestUser manager;
  private TestUser finance;
  private String family;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    OrganizationContext.callAsSchemaOwner(() ->
        jdbc.sql("UPDATE facilities SET timezone = ? WHERE id = ?")
            .params(ZONE, facility)
            .update());
    today = LocalDate.now(ZoneId.of(ZONE));
    opd = fixtures.department(orgId, facility, "OPD");
    clerk =
        user(
            Permissions.PATIENT_READ,
            Permissions.PATIENT_REGISTER,
            Permissions.ENCOUNTER_READ,
            Permissions.ENCOUNTER_OPEN);
    doctor = user(Permissions.ENCOUNTER_READ, Permissions.ENCOUNTER_MANAGE);
    manager = user(Permissions.REPORT_OPERATIONS_READ, Permissions.REPORT_EXPORT);
    finance = user(Permissions.REPORT_REVENUE_READ);
    family = "Reportcase" + UUID.randomUUID().toString().substring(0, 6);
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

  private int visits;

  private String visit() throws Exception {
    visits++;
    String patient =
        read(
            send(
                    post("/api/v1/patients"),
                    clerk,
                    "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"%s\", \"given_names\": \"%s\", \"birth_date\": \"19%d-03-03\", \"sex\": \"MALE\"}"
                        .formatted(
                            facility,
                            family + "abcdefgh".charAt(visits),
                            "Case" + "abcdefgh".charAt(visits),
                            50 + visits))
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

  private ResultActions report(TestUser user, String name, LocalDate from, LocalDate to)
      throws Exception {
    return send(
        get("/api/v1/reports/{r}", name)
            .param("facility_id", facility.toString())
            .param("from", from.toString())
            .param("to", to.toString()),
        user,
        "");
  }

  @Test
  void visitAndRegistrationReportsCountByFacilityLocalDayWithoutPatientData() throws Exception {
    String first = visit();
    visit();
    send(post("/api/v1/encounters/{id}/call", first), doctor, "{\"version\": 0}")
        .andExpect(status().isOk());

    ResultActions visits =
        report(manager, "visits", today, today)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.timezone").value(ZONE))
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].day").value(today.toString()))
            .andExpect(jsonPath("$.rows[0].arrivals").value(2))
            .andExpect(jsonPath("$.rows[0].seen").value(1))
            .andExpect(jsonPath("$.rows[0].still_open").value(2))
            .andExpect(jsonPath("$.generated_by.id").value(manager.userId().toString()));
    assertThat(visits.andReturn().getResponse().getContentAsString()).doesNotContain(family);

    report(manager, "registrations", today, today)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].source").value("WALK_IN"))
        .andExpect(jsonPath("$.rows[0].registrations").value(2));
    // A day with nothing in it.
    report(manager, "visits", today.minusDays(3), today.minusDays(2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(0));
    assertThat(fixtures.auditActions(manager.userId())).contains("REPORT_VIEWED");
  }

  @Test
  void reportsNeedTheirPermissionAFacilityInScopeAndAValidPeriod() throws Exception {
    report(manager, "revenue", today, today).andExpect(status().isForbidden());
    report(finance, "visits", today, today).andExpect(status().isForbidden());
    report(clerk, "visits", today, today).andExpect(status().isForbidden());
    report(manager, "no-such-report", today, today).andExpect(status().isNotFound());
    report(manager, "visits", today, today.minusDays(1)).andExpect(status().isBadRequest());
    report(manager, "visits", today.minusDays(366), today).andExpect(status().isBadRequest());
    report(manager, "visits", today.minusDays(365), today).andExpect(status().isOk());
    send(
            get("/api/v1/reports/visits")
                .param("facility_id", fixtures.facility(orgId, "GH2").toString())
                .param("from", today.toString())
                .param("to", today.toString()),
            manager,
            "")
        .andExpect(status().isNotFound());
    send(get("/api/v1/reports"), manager, "")
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[*].slug").value(Matchers.contains("visits", "orders", "registrations")))
        .andExpect(jsonPath("$[0].exportable").value(true));
    send(get("/api/v1/reports"), finance, "")
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[*].slug")
                .value(Matchers.contains("revenue", "payment-methods", "cash-shifts")))
        .andExpect(jsonPath("$[0].exportable").value(false));
  }

  @Test
  void exportsAreAggregateCsvForExportHoldersAndAudited() throws Exception {
    visit();
    ResultActions csv =
        send(
                get("/api/v1/reports/visits/export")
                    .param("facility_id", facility.toString())
                    .param("from", today.toString())
                    .param("to", today.toString()),
                manager,
                "")
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(
                header().string("Content-Disposition", Matchers.containsString("attachment")));
    String body = csv.andReturn().getResponse().getContentAsString();
    assertThat(body)
        .startsWith("# GCare HMS report: Visits and waiting\r\n")
        .contains(
            "Day,Department,Arrivals,Called in,Closed,Cancelled,Still open,Average wait (min)\r\n")
        .contains(
            today
                + ",Test OPD department,1,0,0,0,1,\r\n"
                    .replace("Test OPD department", departmentName()))
        .doesNotContain(family);

    send(
            get("/api/v1/reports/revenue/export")
                .param("facility_id", facility.toString())
                .param("from", today.toString())
                .param("to", today.toString()),
            finance,
            "")
        .andExpect(status().isForbidden());
    assertThat(fixtures.auditActions(manager.userId())).contains("REPORT_EXPORTED");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE actor_id = ? AND action = 'REPORT_EXPORTED'"
                    + " AND metadata->>'from' = ? AND resource_id = 'visits'",
                manager.userId(),
                today.toString()))
        .isEqualTo(1);
  }

  @Test
  void dashboardShowsOnlyWhatTheRoleMaySee() throws Exception {
    visit();
    send(get("/api/v1/reports/dashboard").param("facility_id", facility.toString()), manager, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.day").value(today.toString()))
        .andExpect(
            jsonPath("$.tiles[?(@.key == 'arrivals_today')].value").value(Matchers.contains(1)))
        .andExpect(jsonPath("$.tiles[?(@.key == 'waiting_now')].value").value(Matchers.contains(1)))
        .andExpect(jsonPath("$.tiles[?(@.currency == 'USD')]").isEmpty());
    send(get("/api/v1/reports/dashboard").param("facility_id", facility.toString()), finance, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tiles[?(@.key == 'arrivals_today')]").isEmpty());
    send(get("/api/v1/reports/dashboard").param("facility_id", facility.toString()), clerk, "")
        .andExpect(status().isForbidden());
  }

  @Test
  void revenueReportsShowEachCurrencySeparately() throws Exception {
    TestUser officer =
        user(
            Permissions.BILLING_READ,
            Permissions.BILLING_CATALOG_MANAGE,
            Permissions.PRICE_PROPOSE);
    TestUser approver = user(Permissions.PRICE_APPROVE);
    TestUser admin = user(Permissions.PAYMENT_METHOD_MANAGE);
    TestUser cashier =
        user(
            Permissions.BILLING_READ,
            Permissions.INVOICE_READ,
            Permissions.INVOICE_MANAGE,
            Permissions.PAYMENT_RECORD);
    String service =
        read(
            send(post("/api/v1/billing/services"), officer, "{\"code\": \"T-S\", \"name\": \"T\"}")
                .andExpect(status().isCreated()),
            "$.id");
    LocalDate utcToday = LocalDate.now(ZoneOffset.UTC);
    String price =
        read(
            send(
                    post("/api/v1/billing/services/{id}/prices", service),
                    officer,
                    "{\"currency\": \"USD\", \"amount\": \"10.50\", \"effective_from\": \"%s\", \"reason\": \"Fixture\"}"
                        .formatted(utcToday))
                .andExpect(status().isCreated()),
            "$.id");
    send(
            post("/api/v1/billing/prices/{id}/approve", price),
            approver,
            "{\"version\": 0, \"note\": \"ok\"}")
        .andExpect(status().isOk());
    String cash =
        read(
            send(
                    post("/api/v1/payment-methods"),
                    admin,
                    "{\"code\": \"T-CASH\", \"name\": \"Test cash\", \"is_cash\": true}")
                .andExpect(status().isCreated()),
            "$.id");
    String invoice =
        read(
            send(
                    post("/api/v1/encounters/{id}/invoices", visit()),
                    cashier,
                    "{\"currency\": \"USD\"}")
                .andExpect(status().isCreated()),
            "$.id");
    send(
            post("/api/v1/invoices/{id}/lines", invoice),
            cashier,
            "{\"version\": 0, \"service_id\": \"%s\", \"quantity\": 1, \"reason\": \"x\"}"
                .formatted(service))
        .andExpect(status().isOk());
    send(post("/api/v1/invoices/{id}/issue", invoice), cashier, "{\"version\": 1}")
        .andExpect(status().isOk());
    String shift =
        read(
            send(post("/api/v1/shifts"), cashier, "{\"facility_id\": \"%s\"}".formatted(facility))
                .andExpect(status().isCreated()),
            "$.id");
    send(
            post("/api/v1/invoices/{id}/payments", invoice),
            cashier,
            "{\"idempotency_key\": \"%s\", \"method_id\": \"%s\", \"amount\": \"4\"}"
                .formatted(UUID.randomUUID(), cash))
        .andExpect(status().isCreated());
    send(
            post("/api/v1/shifts/{id}/close", shift),
            cashier,
            "{\"version\": 0, \"counts\": [{\"currency\": \"USD\", \"counted\": \"3.50\"}], \"note\": \"Short\"}")
        .andExpect(status().isOk());

    ResultActions revenue =
        report(finance, "revenue", today, today)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows[0].currency").value("USD"))
            .andExpect(jsonPath("$.rows[0].invoices_issued").value(1));
    assertThat(new BigDecimal(read(revenue, "$.rows[0].invoiced"))).isEqualByComparingTo("10.5");
    assertThat(new BigDecimal(read(revenue, "$.rows[0].collected"))).isEqualByComparingTo("4");
    assertThat(new BigDecimal(read(revenue, "$.rows[0].net_collected"))).isEqualByComparingTo("4");
    report(finance, "payment-methods", today, today)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].method").value("Test cash"))
        .andExpect(jsonPath("$.rows[0].payments").value(1));
    ResultActions shifts = report(finance, "cash-shifts", today, today).andExpect(status().isOk());
    assertThat(new BigDecimal(read(shifts, "$.rows[0].difference"))).isEqualByComparingTo("-0.5");
  }

  @Test
  void csvCellsAreQuotedAndFormulaSafe() {
    assertThat(ReportCsv.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
    assertThat(ReportCsv.cell("Ward, east")).isEqualTo("\"Ward, east\"");
    assertThat(ReportCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(ReportCsv.cell(new BigDecimal("-1.50"))).isEqualTo("-1.50");
    assertThat(ReportCsv.cell(null)).isEmpty();
  }

  private String departmentName() {
    return OrganizationContext.callAsSchemaOwner(() ->
        jdbc.sql("SELECT name FROM departments WHERE id = ?")
            .params(opd)
            .query(String.class)
            .single());
  }
}
