package com.gcare.hms.billing;

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
 * Payments, receipts and cashier shifts (task 4.3; FR-018; docs/19 §4). Every price and payment
 * method here is a test fixture.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentTests {

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
  private TestUser reviewer;
  private String cash;
  private String card;
  private String service;

  @BeforeEach
  void setUp() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    opd = fixtures.department(orgId, facility, "OPD");
    admin = user(Permissions.PAYMENT_METHOD_MANAGE);
    clerk =
        user(
            Permissions.PATIENT_READ,
            Permissions.PATIENT_REGISTER,
            Permissions.ENCOUNTER_READ,
            Permissions.ENCOUNTER_OPEN);
    doctor = user(Permissions.ENCOUNTER_READ, Permissions.ENCOUNTER_MANAGE);
    officer =
        user(
            Permissions.BILLING_READ,
            Permissions.BILLING_CATALOG_MANAGE,
            Permissions.PRICE_PROPOSE);
    manager = user(Permissions.BILLING_READ, Permissions.PRICE_APPROVE, Permissions.INVOICE_VOID);
    cashier =
        user(
            Permissions.BILLING_READ,
            Permissions.INVOICE_READ,
            Permissions.INVOICE_MANAGE,
            Permissions.PAYMENT_RECORD);
    reviewer = user(Permissions.SHIFT_REVIEW, Permissions.INVOICE_READ);
    cash = method("T-CASH", true);
    card = method("T-CARD", false);
    service =
        read(
            send(
                    post("/api/v1/billing/services"),
                    officer,
                    "{\"code\": \"T-SVC\", \"name\": \"Test service\"}")
                .andExpect(status().isCreated()),
            "$.id");
    String price =
        read(
            send(
                    post("/api/v1/billing/services/{id}/prices", service),
                    officer,
                    "{\"currency\": \"USD\", \"amount\": \"10.00\", \"effective_from\": \"%s\", \"reason\": \"Fixture\"}"
                        .formatted(TODAY))
                .andExpect(status().isCreated()),
            "$.id");
    send(
            post("/api/v1/billing/prices/{id}/approve", price),
            manager,
            "{\"version\": 0, \"note\": \"Fixture\"}")
        .andExpect(status().isOk());
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

  private String method(String code, boolean isCash) throws Exception {
    return read(
        send(
                post("/api/v1/payment-methods"),
                admin,
                "{\"code\": \"%s\", \"name\": \"Test %s\", \"is_cash\": %s}"
                    .formatted(code, code, isCash))
            .andExpect(status().isCreated()),
        "$.id");
  }

  /** A visit with a draft invoice holding one 10.00 USD charge; returns the draft response. */
  private ResultActions draft() throws Exception {
    String patient =
        read(
            send(
                    post("/api/v1/patients"),
                    clerk,
                    "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"Pay%s\", \"given_names\": \"Case\", \"birth_date\": \"1990-04-04\", \"sex\": \"FEMALE\"}"
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
    ResultActions created =
        send(post("/api/v1/encounters/{id}/invoices", e), cashier, "{\"currency\": \"USD\"}")
            .andExpect(status().isCreated());
    return send(
            post("/api/v1/invoices/{id}/lines", read(created, "$.id")),
            cashier,
            "{\"version\": 0, \"service_id\": \"%s\", \"quantity\": 1, \"reason\": \"Consultation\"}"
                .formatted(service))
        .andExpect(status().isOk());
  }

  private String issued() throws Exception {
    ResultActions d = draft();
    String id = read(d, "$.id");
    send(
            post("/api/v1/invoices/{id}/issue", id),
            cashier,
            "{\"version\": %d}".formatted(version(d)))
        .andExpect(status().isOk());
    return id;
  }

  private ResultActions pay(String invoice, UUID key, String method, String amount)
      throws Exception {
    return send(
        post("/api/v1/invoices/{id}/payments", invoice),
        cashier,
        "{\"idempotency_key\": \"%s\", \"method_id\": \"%s\", \"amount\": \"%s\"}"
            .formatted(key, method, amount));
  }

  private String openShift(TestUser user) throws Exception {
    return read(
        send(post("/api/v1/shifts"), user, "{\"facility_id\": \"%s\"}".formatted(facility))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("OPEN")),
        "$.id");
  }

  // --- tests -------------------------------------------------------------------------------------

  @Test
  void partPaymentsUpToTheBalanceWithReceiptsAndIdempotentRetries() throws Exception {
    String invoice = issued();
    UUID key = UUID.randomUUID();
    pay(invoice, key, cash, "4.00").andExpect(status().isUnprocessableContent());
    openShift(cashier);

    ResultActions first =
        pay(invoice, key, cash, "4.00")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.receipt_number").value("RCT-000001"));
    assertThat(new BigDecimal(read(first, "$.balance_after"))).isEqualByComparingTo("6");
    // Retrying the same request returns the same payment; reusing the key differently is refused.
    pay(invoice, key, cash, "4.00")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(read(first, "$.id")));
    pay(invoice, key, cash, "5.00").andExpect(status().isConflict());

    pay(invoice, UUID.randomUUID(), card, "7.00").andExpect(status().isUnprocessableContent());
    pay(invoice, UUID.randomUUID(), card, "6.001").andExpect(status().isBadRequest());
    pay(invoice, UUID.randomUUID(), card, "0").andExpect(status().isBadRequest());
    pay(invoice, UUID.randomUUID(), card, "6")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.receipt_number").value("RCT-000002"));
    pay(invoice, UUID.randomUUID(), cash, "1").andExpect(status().isUnprocessableContent());

    ResultActions view =
        send(get("/api/v1/invoices/{id}", invoice), cashier, "").andExpect(status().isOk());
    assertThat(new BigDecimal(read(view, "$.paid"))).isEqualByComparingTo("10");
    assertThat(new BigDecimal(read(view, "$.balance"))).isEqualByComparingTo("0");
    assertThat(fixtures.count("SELECT count(*) FROM payments WHERE invoice_id = ?::uuid", invoice))
        .isEqualTo(2);

    send(get("/api/v1/payments/{id}/receipt", read(first, "$.id")), cashier, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.format").value("PROVISIONAL"))
        .andExpect(jsonPath("$.receipt_number").value("RCT-000001"))
        .andExpect(jsonPath("$.invoice_number").value("INV-000001"))
        .andExpect(jsonPath("$.method_name").value("Test T-CASH"))
        .andExpect(jsonPath("$.facility_name").exists());
    send(get("/api/v1/payments/{id}/receipt", read(first, "$.id")), doctor, "")
        .andExpect(status().isForbidden());

    // A paid invoice cannot be voided (refunds are task 4.4); payments never change.
    send(
            post("/api/v1/invoices/{id}/void", invoice),
            manager,
            "{\"version\": %d, \"reason\": \"Wrong\"}".formatted(version(view)))
        .andExpect(status().isUnprocessableContent());
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE payments SET amount = 1 WHERE invoice_id = ?::uuid")
                        .params(invoice)
                        .update()))
        .isInstanceOf(DataAccessException.class);

    assertThat(fixtures.auditActions(cashier.userId()))
        .contains("SHIFT_OPENED", "PAYMENT_RECORDED", "RECEIPT_VIEWED");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE organization_id = ?"
                    + " AND (metadata::text LIKE '%4.00%' OR metadata::text LIKE '%\"6\"%')",
                orgId))
        .isZero();
  }

  @Test
  void onlyIssuedInvoicesWithActiveMethodsCanBePaid() throws Exception {
    openShift(cashier);
    String draft = read(draft(), "$.id");
    pay(draft, UUID.randomUUID(), cash, "1").andExpect(status().isUnprocessableContent());

    String invoice = issued();
    send(
            put("/api/v1/payment-methods/{id}", card),
            admin,
            "{\"version\": 0, \"name\": \"Test card\", \"status\": \"INACTIVE\"}")
        .andExpect(status().isOk());
    pay(invoice, UUID.randomUUID(), card, "1").andExpect(status().isBadRequest());
    send(
            post("/api/v1/payment-methods"),
            cashier,
            "{\"code\": \"X\", \"name\": \"X\", \"is_cash\": true}")
        .andExpect(status().isForbidden());

    UUID other = fixtures.facility(orgId, "GH2");
    TestUser elsewhere = fixtures.user(orgId);
    fixtures.grant(elsewhere, other, Permissions.PAYMENT_RECORD);
    send(post("/api/v1/shifts"), elsewhere, "{\"facility_id\": \"%s\"}".formatted(other))
        .andExpect(status().isCreated());
    send(
            post("/api/v1/invoices/{id}/payments", invoice),
            elsewhere,
            "{\"idempotency_key\": \"%s\", \"method_id\": \"%s\", \"amount\": \"1\"}"
                .formatted(UUID.randomUUID(), cash))
        .andExpect(status().isNotFound());
  }

  @Test
  void shiftsCloseWithACashCountAndAreSignedOffByAnotherPerson() throws Exception {
    String invoice = issued();
    String shift = openShift(cashier);
    send(post("/api/v1/shifts"), cashier, "{\"facility_id\": \"%s\"}".formatted(facility))
        .andExpect(status().isConflict());
    pay(invoice, UUID.randomUUID(), cash, "4").andExpect(status().isCreated());
    pay(invoice, UUID.randomUUID(), card, "2").andExpect(status().isCreated());

    ResultActions open =
        send(get("/api/v1/shifts/{id}", shift), cashier, "")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totals.length()").value(2))
            .andExpect(jsonPath("$.expected_cash[0].currency").value("USD"));
    assertThat(new BigDecimal(read(open, "$.expected_cash[0].amount"))).isEqualByComparingTo("4");
    long v = version(open);

    String close = "/api/v1/shifts/{id}/close";
    send(post(close, shift), cashier, "{\"version\": %d, \"counts\": []}".formatted(v))
        .andExpect(status().isBadRequest());
    send(
            post(close, shift),
            cashier,
            "{\"version\": %d, \"counts\": [{\"currency\": \"USD\", \"counted\": \"3\"}]}"
                .formatted(v))
        .andExpect(status().isBadRequest());
    send(post(close, shift), reviewer, "{\"version\": %d, \"counts\": []}".formatted(v))
        .andExpect(status().isForbidden());
    ResultActions closed =
        send(
                post(close, shift),
                cashier,
                "{\"version\": %d, \"counts\": [{\"currency\": \"USD\", \"counted\": \"3.00\"}], \"note\": \"Change given twice\"}"
                    .formatted(v))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CLOSED"));
    assertThat(new BigDecimal(read(closed, "$.counts[0].difference"))).isEqualByComparingTo("-1");
    pay(invoice, UUID.randomUUID(), cash, "1").andExpect(status().isUnprocessableContent());

    // Signing off: never your own shift.
    TestUser both = user(Permissions.PAYMENT_RECORD, Permissions.SHIFT_REVIEW);
    String own = openShift(both);
    ResultActions ownClosed =
        send(post(close, own), both, "{\"version\": 0, \"counts\": []}").andExpect(status().isOk());
    send(
            post("/api/v1/shifts/{id}/review", own),
            both,
            "{\"version\": %d, \"note\": \"ok\"}".formatted(version(ownClosed)))
        .andExpect(status().isUnprocessableContent());

    send(get("/api/v1/shifts/to-review"), reviewer, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
    send(
            post("/api/v1/shifts/{id}/review", shift),
            cashier,
            "{\"version\": %d, \"note\": \"ok\"}".formatted(version(closed)))
        .andExpect(status().isForbidden());
    send(
            post("/api/v1/shifts/{id}/review", shift),
            reviewer,
            "{\"version\": %d, \"note\": \"Difference accepted\"}".formatted(version(closed)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVIEWED"));
    assertThat(fixtures.auditActions(reviewer.userId())).contains("SHIFT_REVIEWED");
  }

  @Test
  void paymentTablesAreIsolatedAndCannotBeDeleted() throws Exception {
    String invoice = issued();
    String shift = openShift(cashier);
    pay(invoice, UUID.randomUUID(), cash, "10").andExpect(status().isCreated());
    send(
            post("/api/v1/shifts/{id}/close", shift),
            cashier,
            "{\"version\": 0, \"counts\": [{\"currency\": \"USD\", \"counted\": \"10\"}]}")
        .andExpect(status().isOk());
    UUID otherOrg = fixtures.organization();
    for (String table :
        new String[] {
          "payment_methods", "cashier_shifts", "shift_cash_counts", "receipt_counters", "payments"
        }) {
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
