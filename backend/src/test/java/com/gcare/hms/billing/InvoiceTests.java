package com.gcare.hms.billing;

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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Visit invoices (task 4.2; FR-017; docs/19 §3). Every price and tax rate here is a test fixture,
 * not a tariff or a legal rate.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InvoiceTests {

  private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private JdbcClient jdbc;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private UUID opd;
  private TestUser admin;
  private TestUser clerk;
  private TestUser doctor;
  private TestUser officer;
  private TestUser manager;
  private TestUser cashier;
  private TestUser voider;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    opd = fixtures.department(orgId, facility, "OPD");
    admin = user(Permissions.FACILITY_READ, Permissions.ORDER_CATALOG_MANAGE);
    clerk =
        user(
            Permissions.PATIENT_READ,
            Permissions.PATIENT_REGISTER,
            Permissions.ENCOUNTER_READ,
            Permissions.ENCOUNTER_OPEN);
    doctor =
        user(
            Permissions.PATIENT_READ,
            Permissions.ENCOUNTER_READ,
            Permissions.ENCOUNTER_MANAGE,
            Permissions.ORDER_READ,
            Permissions.ORDER_CREATE);
    officer =
        user(
            Permissions.BILLING_READ,
            Permissions.BILLING_CATALOG_MANAGE,
            Permissions.PRICE_PROPOSE);
    manager = user(Permissions.BILLING_READ, Permissions.PRICE_PROPOSE, Permissions.PRICE_APPROVE);
    cashier = user(Permissions.BILLING_READ, Permissions.INVOICE_READ, Permissions.INVOICE_MANAGE);
    voider = user(Permissions.INVOICE_READ, Permissions.INVOICE_VOID);
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

  // --- fixtures ----------------------------------------------------------------------------------

  private String orderItem(String code) throws Exception {
    return read(
        send(
                post("/api/v1/order-catalog"),
                admin,
                "{\"code\": \"%s\", \"name\": \"Test item %s\", \"category\": \"LAB\"}"
                    .formatted(code, code))
            .andExpect(status().isCreated()),
        "$.id");
  }

  private String billable(String code, String orderItem) throws Exception {
    String item =
        orderItem == null ? "" : ", \"order_catalog_item_id\": \"%s\"".formatted(orderItem);
    return read(
        send(
                post("/api/v1/billing/services"),
                officer,
                "{\"code\": \"%s\", \"name\": \"Test service %s\"%s}".formatted(code, code, item))
            .andExpect(status().isCreated()),
        "$.id");
  }

  private void taxRate(String code, String percent) throws Exception {
    String id =
        read(
            send(
                    post("/api/v1/billing/tax-rates"),
                    officer,
                    "{\"code\": \"%s\", \"name\": \"Test rate\", \"rate_percent\": \"%s\", \"effective_from\": \"%s\", \"reason\": \"Fixture\"}"
                        .formatted(code, percent, TODAY))
                .andExpect(status().isCreated()),
            "$.id");
    send(
            post("/api/v1/billing/tax-rates/{id}/approve", id),
            manager,
            "{\"version\": 0, \"note\": \"Fixture\"}")
        .andExpect(status().isOk());
  }

  private void price(String service, String currency, String amount, String tax) throws Exception {
    String taxJson = tax == null ? "" : ", \"tax_rate_code\": \"%s\"".formatted(tax);
    String id =
        read(
            send(
                    post("/api/v1/billing/services/{id}/prices", service),
                    officer,
                    "{\"currency\": \"%s\", \"amount\": \"%s\", \"effective_from\": \"%s\", \"reason\": \"Fixture\"%s}"
                        .formatted(currency, amount, TODAY, taxJson))
                .andExpect(status().isCreated()),
            "$.id");
    send(
            post("/api/v1/billing/prices/{id}/approve", id),
            manager,
            "{\"version\": 0, \"note\": \"Fixture\"}")
        .andExpect(status().isOk());
  }

  private String visit() throws Exception {
    String patient =
        read(
            send(
                    post("/api/v1/patients"),
                    clerk,
                    "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"Bill%s\", \"given_names\": \"Case\", \"birth_date\": \"1980-03-03\", \"sex\": \"MALE\"}"
                        .formatted(facility, UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().isCreated()),
            "$.id");
    String e =
        read(
            send(
                    post("/api/v1/encounters"),
                    clerk,
                    "{\"patient_id\": \"%s\", \"facility_id\": \"%s\", \"department_id\": \"%s\", \"encounter_type\": \"NEW_VISIT\"}"
                        .formatted(patient, facility, opd))
                .andExpect(status().isCreated()),
            "$.encounter.id");
    send(post("/api/v1/encounters/{id}/call", e), doctor, "{\"version\": 0}")
        .andExpect(status().isOk());
    return e;
  }

  private String order(String encounter, String item) throws Exception {
    return read(
        send(
                post("/api/v1/encounters/{id}/orders", encounter),
                doctor,
                "{\"catalog_item_id\": \"%s\", \"priority\": \"ROUTINE\"}".formatted(item))
            .andExpect(status().isCreated()),
        "$.id");
  }

  private ResultActions start(TestUser user, String encounter, String currency) throws Exception {
    return send(
        post("/api/v1/encounters/{id}/invoices", encounter),
        user,
        "{\"currency\": \"%s\"}".formatted(currency));
  }

  private ResultActions addLine(String invoice, long version, String service, int quantity)
      throws Exception {
    return send(
        post("/api/v1/invoices/{id}/lines", invoice),
        cashier,
        "{\"version\": %d, \"service_id\": \"%s\", \"quantity\": %d, \"reason\": \"Consultation given\"}"
            .formatted(version, service, quantity));
  }

  private ResultActions issue(String invoice, long version) throws Exception {
    return send(
        post("/api/v1/invoices/{id}/issue", invoice),
        cashier,
        "{\"version\": %d}".formatted(version));
  }

  private static BigDecimal money(ResultActions r, String path) throws Exception {
    return new BigDecimal(read(r, path));
  }

  // --- tests -------------------------------------------------------------------------------------

  @Test
  void orderChargesAndManualLinesWithTaxRoundedHalfUpPerLine() throws Exception {
    taxRate("T-VAT", "15");
    String labItem = orderItem("T-LAB-1");
    String labService = billable("T-LAB-SVC", labItem);
    price(labService, "USD", "12.50", "T-VAT");
    String consult = billable("T-CONSULT", null);
    price(consult, "USD", "3.35", "T-VAT");
    String e = visit();
    order(e, labItem);

    ResultActions created = start(cashier, e, "USD").andExpect(status().isCreated());
    String invoice = read(created, "$.id");
    created
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].source").value("ORDER"))
        .andExpect(jsonPath("$.lines[0].service_code").value("T-LAB-SVC"));
    // 12.50 × 15 % = 1.875 → 1.88 (half-up).
    assertThat(money(created, "$.lines[0].tax_amount")).isEqualByComparingTo("1.88");

    ResultActions withManual =
        addLine(invoice, version(created), consult, 3).andExpect(status().isOk());
    // 3 × 3.35 = 10.05; × 15 % = 1.5075 → 1.51. Totals are sums of the rounded lines
    // (a single rounding on the total would give 3.38, not 3.39).
    assertThat(money(withManual, "$.subtotal")).isEqualByComparingTo("22.55");
    assertThat(money(withManual, "$.tax_total")).isEqualByComparingTo("3.39");
    assertThat(money(withManual, "$.total")).isEqualByComparingTo("25.94");

    ResultActions issued =
        issue(invoice, version(withManual))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ISSUED"))
            .andExpect(jsonPath("$.invoice_number").value("INV-000001"));
    assertThat(money(issued, "$.total")).isEqualByComparingTo("25.94");

    // A later price does not change the issued invoice, and nothing can be added to it.
    addLine(invoice, version(issued), consult, 1).andExpect(status().isUnprocessableContent());
    send(get("/api/v1/invoices/{id}", invoice), voider, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines.length()").value(2));

    // The database refuses changes to issued totals and lines.
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE invoices SET total = 1 WHERE id = ?::uuid")
                        .params(invoice)
                        .update()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql(
                            "UPDATE invoice_lines SET status = 'REMOVED', removed_reason = 'x',"
                                + " removed_by = created_by, removed_at = now()"
                                + " FROM invoices WHERE invoice_lines.invoice_id = invoices.id"
                                + " AND invoices.id = ?::uuid")
                        .params(invoice)
                        .update()))
        .isInstanceOf(DataAccessException.class);

    assertThat(fixtures.auditActions(cashier.userId()))
        .contains("INVOICE_CREATED", "INVOICE_LINE_ADDED", "INVOICE_ISSUED");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE resource_type = 'Invoice'"
                    + " AND (metadata::text LIKE '%25.94%' OR metadata::text LIKE '%Consultation given%')"))
        .isZero();
  }

  @Test
  void eachLineNeedsAnApprovedPriceInTheInvoiceCurrency() throws Exception {
    String labItem = orderItem("T-LAB-2");
    String labService = billable("T-LAB-SVC2", labItem);
    price(labService, "USD", "5", null);
    String e = visit();
    order(e, labItem);

    // No conversion: in LRD the order has no price, so it is listed but not charged.
    ResultActions created =
        start(cashier, e, "LRD")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.lines.length()").value(0))
            .andExpect(jsonPath("$.unbilled_orders[0].problem").value("NO_PRICE"));
    String invoice = read(created, "$.id");
    addLine(invoice, version(created), labService, 1).andExpect(status().isUnprocessableContent());
    issue(invoice, version(created)).andExpect(status().isUnprocessableContent());

    start(cashier, visit(), "XXX").andExpect(status().isBadRequest());
    start(cashier, visit(), "usd").andExpect(status().isBadRequest());
  }

  @Test
  void newOrdersMustBeChargedAndCancelledOrdersRemovedBeforeIssuing() throws Exception {
    String labItem = orderItem("T-LAB-3");
    price(billable("T-LAB-SVC3", labItem), "USD", "4", null);
    String e = visit();
    String first = order(e, labItem);
    ResultActions created = start(cashier, e, "USD").andExpect(status().isCreated());
    String invoice = read(created, "$.id");
    String firstLine = read(created, "$.lines[0].id");

    order(e, labItem);
    issue(invoice, version(created)).andExpect(status().isUnprocessableContent());
    ResultActions synced =
        send(
                post("/api/v1/invoices/{id}/order-charges", invoice),
                cashier,
                "{\"version\": %d}".formatted(version(created)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines.length()").value(2));

    send(
            post("/api/v1/orders/{id}/cancel", first),
            doctor,
            "{\"version\": 0, \"reason\": \"Not needed\"}")
        .andExpect(status().isOk());
    issue(invoice, version(synced)).andExpect(status().isUnprocessableContent());
    // Stale version.
    send(
            post("/api/v1/invoices/{id}/lines/{line}/remove", invoice, firstLine),
            cashier,
            "{\"version\": %d, \"reason\": \"Order cancelled\"}".formatted(version(created)))
        .andExpect(status().isConflict());
    ResultActions removed =
        send(
                post("/api/v1/invoices/{id}/lines/{line}/remove", invoice, firstLine),
                cashier,
                "{\"version\": %d, \"reason\": \"Order cancelled\"}".formatted(version(synced)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].status").value("REMOVED"));
    assertThat(money(removed, "$.total")).isEqualByComparingTo("4");
    // The removed line stays on the invoice as the record; the cancelled order is not re-added.
    send(
            post("/api/v1/invoices/{id}/order-charges", invoice),
            cashier,
            "{\"version\": %d}".formatted(version(removed)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines.length()").value(2));
  }

  @Test
  void issuedInvoicesAreVoidedWithAReasonAndReplaced() throws Exception {
    String consult = billable("T-CONSULT2", null);
    price(consult, "USD", "2", null);
    String e = visit();
    ResultActions created = start(cashier, e, "USD").andExpect(status().isCreated());
    String invoice = read(created, "$.id");
    ResultActions issued =
        issue(invoice, version(addLine(invoice, version(created), consult, 1)))
            .andExpect(status().isOk());
    start(cashier, e, "USD").andExpect(status().isConflict());

    String voidBody = "{\"version\": %d, \"reason\": \"Wrong patient\"}".formatted(version(issued));
    send(post("/api/v1/invoices/{id}/void", invoice), cashier, voidBody)
        .andExpect(status().isForbidden());
    send(
            post("/api/v1/invoices/{id}/void", invoice),
            voider,
            "{\"version\": %d, \"reason\": \" \"}".formatted(version(issued)))
        .andExpect(status().isBadRequest());
    send(post("/api/v1/invoices/{id}/void", invoice), voider, voidBody)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VOID"))
        .andExpect(jsonPath("$.invoice_number").value("INV-000001"));

    ResultActions replacement =
        start(cashier, e, "USD")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.replaces_invoice_id").value(invoice));
    // A cashier may discard a draft (never issued, so it has no number).
    send(
            post("/api/v1/invoices/{id}/void", read(replacement, "$.id")),
            cashier,
            "{\"version\": %d, \"reason\": \"Wrong currency\"}".formatted(version(replacement)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.invoice_number").isEmpty());
    send(get("/api/v1/encounters/{id}/invoices", e), cashier, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
    assertThat(fixtures.auditActions(voider.userId())).contains("INVOICE_VOIDED");
  }

  @Test
  void invoicesAreLimitedByPermissionAndFacility() throws Exception {
    String e = visit();
    start(doctor, e, "USD").andExpect(status().isForbidden());
    send(get("/api/v1/invoices"), doctor, "").andExpect(status().isForbidden());

    UUID otherFacility = fixtures.facility(orgId, "GH2");
    TestUser elsewhere = fixtures.user(orgId);
    fixtures.grant(elsewhere, otherFacility, Permissions.INVOICE_READ, Permissions.INVOICE_MANAGE);
    start(elsewhere, e, "USD").andExpect(status().isNotFound());

    send(get("/api/v1/invoices/billable-visits"), cashier, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].encounter_id").value(e));
    String invoice = read(start(cashier, e, "USD").andExpect(status().isCreated()), "$.id");
    send(get("/api/v1/invoices/billable-visits"), cashier, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    send(get("/api/v1/invoices/{id}", invoice), elsewhere, "").andExpect(status().isNotFound());
    send(get("/api/v1/invoices"), elsewhere, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    send(get("/api/v1/invoices"), cashier, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(invoice));
  }

  @Test
  void invoiceTablesAreIsolatedAndCannotBeDeleted() throws Exception {
    String consult = billable("T-ISO", null);
    price(consult, "USD", "1", null);
    ResultActions created = start(cashier, visit(), "USD").andExpect(status().isCreated());
    issue(
            read(created, "$.id"),
            version(addLine(read(created, "$.id"), version(created), consult, 1)))
        .andExpect(status().isOk());
    UUID otherOrg = fixtures.organization();
    for (String table : new String[] {"invoices", "invoice_lines", "invoice_counters"}) {
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
