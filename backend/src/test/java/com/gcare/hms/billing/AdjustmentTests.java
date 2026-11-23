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
 * Refunds and fee exemptions (task 4.4; FR-018, FR-019; docs/19 §5). All prices, methods and
 * reasons are test fixtures.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AdjustmentTests {

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
  private TestUser officer;
  private TestUser manager;
  private TestUser cashier;
  private String cash;
  private String card;
  private String service;
  private String reason;

  @BeforeEach
  void setUp() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    opd = fixtures.department(orgId, facility, "OPD");
    admin = user(Permissions.PAYMENT_METHOD_MANAGE, Permissions.EXEMPTION_REASON_MANAGE);
    clerk =
        user(
            Permissions.PATIENT_READ,
            Permissions.PATIENT_REGISTER,
            Permissions.ENCOUNTER_READ,
            Permissions.ENCOUNTER_OPEN);
    officer =
        user(
            Permissions.BILLING_READ,
            Permissions.BILLING_CATALOG_MANAGE,
            Permissions.PRICE_PROPOSE);
    manager =
        user(
            Permissions.BILLING_READ,
            Permissions.PRICE_APPROVE,
            Permissions.INVOICE_READ,
            Permissions.INVOICE_VOID,
            Permissions.REFUND_APPROVE,
            Permissions.EXEMPTION_APPROVE);
    cashier =
        user(
            Permissions.BILLING_READ,
            Permissions.INVOICE_READ,
            Permissions.INVOICE_MANAGE,
            Permissions.PAYMENT_RECORD,
            Permissions.REFUND_REQUEST,
            Permissions.EXEMPTION_REQUEST);
    cash = method("T-CASH", true);
    card = method("T-CARD", false);
    reason =
        read(
            send(
                    post("/api/v1/exemption-reasons"),
                    admin,
                    "{\"code\": \"T-REASON\", \"name\": \"Test reason (fixture)\"}")
                .andExpect(status().isCreated()),
            "$.id");
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

  private static BigDecimal money(ResultActions r, String path) throws Exception {
    return new BigDecimal(read(r, path));
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

  /** An issued 10.00 USD invoice. */
  private String issued() throws Exception {
    String patient =
        read(
            send(
                    post("/api/v1/patients"),
                    clerk,
                    "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"Adj%s\", \"given_names\": \"Case\", \"birth_date\": \"1990-04-04\", \"sex\": \"MALE\"}"
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
    String invoice =
        read(
            send(post("/api/v1/encounters/{id}/invoices", e), cashier, "{\"currency\": \"USD\"}")
                .andExpect(status().isCreated()),
            "$.id");
    send(
            post("/api/v1/invoices/{id}/lines", invoice),
            cashier,
            "{\"version\": 0, \"service_id\": \"%s\", \"quantity\": 1, \"reason\": \"Consultation\"}"
                .formatted(service))
        .andExpect(status().isOk());
    send(post("/api/v1/invoices/{id}/issue", invoice), cashier, "{\"version\": 1}")
        .andExpect(status().isOk());
    return invoice;
  }

  private String openShift() throws Exception {
    return read(
        send(post("/api/v1/shifts"), cashier, "{\"facility_id\": \"%s\"}".formatted(facility))
            .andExpect(status().isCreated()),
        "$.id");
  }

  private ResultActions pay(String invoice, String method, String amount) throws Exception {
    return send(
        post("/api/v1/invoices/{id}/payments", invoice),
        cashier,
        "{\"idempotency_key\": \"%s\", \"method_id\": \"%s\", \"amount\": \"%s\"}"
            .formatted(UUID.randomUUID(), method, amount));
  }

  private ResultActions refund(TestUser user, String payment, String amount) throws Exception {
    return send(
        post("/api/v1/payments/{id}/refunds", payment),
        user,
        "{\"amount\": \"%s\", \"reason\": \"Lab test not done\"}".formatted(amount));
  }

  private ResultActions decide(TestUser user, String kind, String id, String decision, long v)
      throws Exception {
    return send(
        post("/api/v1/{kind}/{id}/" + decision, kind, id),
        user,
        "{\"version\": %d, \"note\": \"Checked\"}".formatted(v));
  }

  private ResultActions payOut(String refund, long v, UUID key) throws Exception {
    return send(
        post("/api/v1/refunds/{id}/pay", refund),
        cashier,
        "{\"version\": %d, \"idempotency_key\": \"%s\"}".formatted(v, key));
  }

  private ResultActions exempt(TestUser user, String invoice, String reasonId, String amount)
      throws Exception {
    return send(
        post("/api/v1/invoices/{id}/exemptions", invoice),
        user,
        "{\"reason_id\": \"%s\", \"amount\": \"%s\", \"note\": \"Cannot pay\"}"
            .formatted(reasonId, amount));
  }

  // --- refunds -----------------------------------------------------------------------------------

  @Test
  void refundsAreApprovedBySomeoneElseThenPaidOutInAShift() throws Exception {
    String invoice = issued();
    String shift = openShift();
    String payment = read(pay(invoice, cash, "10").andExpect(status().isCreated()), "$.id");

    refund(cashier, payment, "11").andExpect(status().isUnprocessableContent());
    refund(cashier, payment, "4.001").andExpect(status().isBadRequest());
    ResultActions requested =
        refund(cashier, payment, "4")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("REQUESTED"));
    String id = read(requested, "$.id");
    refund(cashier, payment, "7").andExpect(status().isUnprocessableContent());

    // Two people: the requester cannot decide, and a cashier cannot approve at all.
    TestUser both = user(Permissions.REFUND_REQUEST, Permissions.REFUND_APPROVE);
    String own = read(refund(both, payment, "1").andExpect(status().isCreated()), "$.id");
    decide(both, "refunds", own, "approve", 0).andExpect(status().isUnprocessableContent());
    decide(manager, "refunds", own, "reject", 0).andExpect(status().isOk());
    decide(cashier, "refunds", id, "approve", 0).andExpect(status().isForbidden());
    payOut(id, 0, UUID.randomUUID()).andExpect(status().isUnprocessableContent());

    ResultActions approved =
        decide(manager, "refunds", id, "approve", 0)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));
    UUID key = UUID.randomUUID();
    ResultActions paid =
        payOut(id, version(approved), key)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.receipt_number").value("RCT-000002"));
    assertThat(money(paid, "$.balance_after")).isEqualByComparingTo("4");
    payOut(id, version(approved), key).andExpect(status().isOk());
    payOut(id, version(paid), UUID.randomUUID()).andExpect(status().isUnprocessableContent());

    ResultActions view =
        send(get("/api/v1/invoices/{id}", invoice), cashier, "").andExpect(status().isOk());
    assertThat(money(view, "$.paid")).isEqualByComparingTo("10");
    assertThat(money(view, "$.refunded")).isEqualByComparingTo("4");
    assertThat(money(view, "$.balance")).isEqualByComparingTo("4");
    ResultActions s =
        send(get("/api/v1/shifts/{id}", shift), cashier, "").andExpect(status().isOk());
    assertThat(money(s, "$.expected_cash[0].amount")).isEqualByComparingTo("6");
    send(get("/api/v1/refunds/{id}/receipt", id), cashier, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("REFUND"))
        .andExpect(jsonPath("$.receipt_number").value("RCT-000002"));

    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE refunds SET status = 'APPROVED' WHERE id = ?::uuid")
                        .params(id)
                        .update()))
        .isInstanceOf(DataAccessException.class);
    assertThat(fixtures.auditActions(manager.userId())).contains("REFUND_APPROVED");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE organization_id = ?"
                    + " AND metadata::text LIKE '%Lab test not done%'",
                orgId))
        .isZero();
  }

  @Test
  void cashRefundsNeedCashTakenInTheShift() throws Exception {
    String invoice = issued();
    String first = openShift();
    String payment = read(pay(invoice, cash, "10").andExpect(status().isCreated()), "$.id");
    send(
            post("/api/v1/shifts/{id}/close", first),
            cashier,
            "{\"version\": 0, \"counts\": [{\"currency\": \"USD\", \"counted\": \"10\"}]}")
        .andExpect(status().isOk());
    String id = read(refund(cashier, payment, "3").andExpect(status().isCreated()), "$.id");
    decide(manager, "refunds", id, "approve", 0).andExpect(status().isOk());

    payOut(id, 1, UUID.randomUUID()).andExpect(status().isUnprocessableContent());
    openShift();
    payOut(id, 1, UUID.randomUUID()).andExpect(status().isUnprocessableContent());

    // A non-cash refund does not touch the drawer.
    String other = issued();
    String cardPayment = read(pay(other, card, "10").andExpect(status().isCreated()), "$.id");
    String cardRefund =
        read(refund(cashier, cardPayment, "10").andExpect(status().isCreated()), "$.id");
    decide(manager, "refunds", cardRefund, "approve", 0).andExpect(status().isOk());
    payOut(cardRefund, 1, UUID.randomUUID()).andExpect(status().isOk());
  }

  // --- exemptions --------------------------------------------------------------------------------

  @Test
  void exemptionsWaiveUpToTheBalanceAfterApprovalBySomeoneElse() throws Exception {
    String invoice = issued();
    openShift();
    exempt(cashier, invoice, reason, "11").andExpect(status().isUnprocessableContent());
    ResultActions requested =
        exempt(cashier, invoice, reason, "6")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("REQUESTED"));
    exempt(cashier, invoice, reason, "5").andExpect(status().isUnprocessableContent());
    send(get("/api/v1/adjustments/pending"), manager, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exemptions.length()").value(1));

    decide(cashier, "exemptions", read(requested, "$.id"), "approve", 0)
        .andExpect(status().isForbidden());
    decide(manager, "exemptions", read(requested, "$.id"), "approve", 0).andExpect(status().isOk());
    ResultActions view =
        send(get("/api/v1/invoices/{id}", invoice), cashier, "").andExpect(status().isOk());
    assertThat(money(view, "$.exempted")).isEqualByComparingTo("6");
    assertThat(money(view, "$.balance")).isEqualByComparingTo("4");
    pay(invoice, cash, "5").andExpect(status().isUnprocessableContent());
    pay(invoice, cash, "4").andExpect(status().isCreated());
    send(
            post("/api/v1/invoices/{id}/void", invoice),
            manager,
            "{\"version\": %d, \"reason\": \"x\"}".formatted(version(view)))
        .andExpect(status().isUnprocessableContent());

    // The requester cannot approve their own; a reason must be active.
    TestUser both = user(Permissions.EXEMPTION_REQUEST, Permissions.EXEMPTION_APPROVE);
    String second = issued();
    String own = read(exempt(both, second, reason, "1").andExpect(status().isCreated()), "$.id");
    decide(both, "exemptions", own, "approve", 0).andExpect(status().isUnprocessableContent());
    send(
            put("/api/v1/exemption-reasons/{id}", reason),
            admin,
            "{\"version\": 0, \"name\": \"Test reason\", \"status\": \"INACTIVE\"}")
        .andExpect(status().isOk());
    exempt(cashier, second, reason, "1").andExpect(status().isBadRequest());
  }

  @Test
  void approvalRechecksTheBalance() throws Exception {
    String invoice = issued();
    openShift();
    String id = read(exempt(cashier, invoice, reason, "8").andExpect(status().isCreated()), "$.id");
    pay(invoice, cash, "5").andExpect(status().isCreated());
    decide(manager, "exemptions", id, "approve", 0).andExpect(status().isUnprocessableContent());
    decide(manager, "exemptions", id, "reject", 0).andExpect(status().isOk());
  }

  @Test
  void adjustmentTablesAreIsolatedAndCannotBeDeleted() throws Exception {
    String invoice = issued();
    openShift();
    String payment = read(pay(invoice, cash, "5").andExpect(status().isCreated()), "$.id");
    refund(cashier, payment, "1").andExpect(status().isCreated());
    exempt(cashier, invoice, reason, "1").andExpect(status().isCreated());
    UUID otherOrg = fixtures.organization();
    for (String table : new String[] {"exemption_reasons", "exemptions", "refunds"}) {
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
