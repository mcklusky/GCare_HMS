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
 * Billable services, dated prices and tax rates (task 4.1; FR-012). All amounts and rates are test
 * fixtures, not a tariff.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TariffTests {

  private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private JdbcClient jdbc;

  private MockMvc mvc;
  private UUID orgId;
  private TestUser officer;
  private TestUser manager;
  private TestUser reader;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    UUID facility = fixtures.facility(orgId, "GH");
    officer = fixtures.user(orgId);
    fixtures.grant(
        officer,
        facility,
        Permissions.BILLING_READ,
        Permissions.BILLING_CATALOG_MANAGE,
        Permissions.PRICE_PROPOSE);
    manager = fixtures.user(orgId);
    fixtures.grant(
        manager,
        facility,
        Permissions.BILLING_READ,
        Permissions.PRICE_PROPOSE,
        Permissions.PRICE_APPROVE);
    reader = fixtures.user(orgId);
    fixtures.grant(reader, facility, Permissions.BILLING_READ);
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

  private String service(String code) throws Exception {
    return read(
        send(
                post("/api/v1/billing/services"),
                officer,
                "{\"code\": \"%s\", \"name\": \"Test service %s\"}".formatted(code, code))
            .andExpect(status().isCreated()),
        "$.id");
  }

  private ResultActions propose(
      TestUser user, String service, String currency, String amount, LocalDate from, String tax)
      throws Exception {
    String taxJson = tax == null ? "" : ", \"tax_rate_code\": \"%s\"".formatted(tax);
    return send(
        post("/api/v1/billing/services/{id}/prices", service),
        user,
        "{\"currency\": \"%s\", \"amount\": %s, \"effective_from\": \"%s\", \"reason\": \"Test fixture price\"%s}"
            .formatted(currency, amount, from, taxJson));
  }

  private ResultActions decide(TestUser user, String kind, String id, String decision, long version)
      throws Exception {
    return send(
        post("/api/v1/billing/{kind}/{id}/" + decision, kind, id),
        user,
        "{\"version\": %d, \"note\": \"Checked\"}".formatted(version));
  }

  private ResultActions effective(String service, String currency, LocalDate date)
      throws Exception {
    return send(
        get("/api/v1/billing/effective-price")
            .param("service_id", service)
            .param("currency", currency)
            .param("date", date.toString()),
        reader,
        "");
  }

  // --- services ----------------------------------------------------------------------------------

  @Test
  void servicesAreManagedByFinanceOnly() throws Exception {
    service("T-SVC-1");
    send(post("/api/v1/billing/services"), officer, "{\"code\": \"T-SVC-1\", \"name\": \"Again\"}")
        .andExpect(status().isConflict());
    send(post("/api/v1/billing/services"), manager, "{\"code\": \"T-SVC-2\", \"name\": \"X\"}")
        .andExpect(status().isForbidden());
    send(
            post("/api/v1/billing/services"),
            officer,
            "{\"code\": \"T-SVC-3\", \"name\": \"X\", \"order_catalog_item_id\": \"%s\"}"
                .formatted(UUID.randomUUID()))
        .andExpect(status().isBadRequest());
    send(get("/api/v1/billing/services"), reader, "")
        .andExpect(jsonPath("$[0].code").value("T-SVC-1"))
        .andExpect(jsonPath("$[0].current_prices").isEmpty());
  }

  // --- prices ------------------------------------------------------------------------------------

  @Test
  void pricesNeedASecondPersonAndNeverChangeOnceApproved() throws Exception {
    String svc = service("T-PRICE");
    String price =
        read(
            propose(officer, svc, "USD", "12.50", TODAY, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED")),
            "$.id");
    effective(svc, "USD", TODAY).andExpect(status().isNotFound());

    decide(officer, "prices", price, "approve", 0).andExpect(status().isForbidden());
    String own =
        read(
            propose(manager, svc, "LRD", "100", TODAY, null).andExpect(status().isCreated()),
            "$.id");
    decide(manager, "prices", own, "approve", 0).andExpect(status().isUnprocessableContent());

    decide(manager, "prices", price, "approve", 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.decided_by.id").value(manager.userId().toString()));
    decide(manager, "prices", price, "reject", 1).andExpect(status().isUnprocessableContent());
    effective(svc, "USD", TODAY)
        .andExpect(jsonPath("$.price.amount").value(12.5))
        .andExpect(jsonPath("$.tax_rate").doesNotExist());
    send(get("/api/v1/billing/services"), reader, "")
        .andExpect(jsonPath("$[0].current_prices[0].amount").value(12.5));

    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE service_prices SET amount = 1 WHERE id = ?")
                        .params(UUID.fromString(price))
                        .update()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE service_prices SET decision_note = 'x' WHERE id = ?")
                        .params(UUID.fromString(price))
                        .update()))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE metadata::text LIKE '%12.5%' OR metadata::text LIKE '%fixture%'"))
        .isZero();
  }

  @Test
  void aPriceChangeIsANewDatedPriceSoEarlierDatesKeepTheOldOne() throws Exception {
    String svc = service("T-CHANGE");
    String first =
        read(
            propose(officer, svc, "USD", "10", TODAY, null).andExpect(status().isCreated()),
            "$.id");
    decide(manager, "prices", first, "approve", 0).andExpect(status().isOk());
    String second =
        read(
            propose(officer, svc, "USD", "15", TODAY.plusDays(1), null)
                .andExpect(status().isCreated()),
            "$.id");
    decide(manager, "prices", second, "approve", 0).andExpect(status().isOk());

    effective(svc, "USD", TODAY)
        .andExpect(jsonPath("$.price.id").value(first))
        .andExpect(jsonPath("$.price.amount").value(10));
    effective(svc, "USD", TODAY.plusDays(1))
        .andExpect(jsonPath("$.price.id").value(second))
        .andExpect(jsonPath("$.price.amount").value(15));
    effective(svc, "LRD", TODAY).andExpect(status().isNotFound());
    send(get("/api/v1/billing/services/{id}/prices", svc), reader, "")
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void currencyAmountAndDateAreValidated() throws Exception {
    String svc = service("T-VALID");
    propose(officer, svc, "XYZ", "1", TODAY, null).andExpect(status().isBadRequest());
    propose(officer, svc, "usd", "1", TODAY, null).andExpect(status().isBadRequest());
    propose(officer, svc, "USD", "1.005", TODAY, null).andExpect(status().isBadRequest());
    propose(officer, svc, "JPY", "1.5", TODAY, null).andExpect(status().isBadRequest());
    propose(officer, svc, "USD", "-1", TODAY, null).andExpect(status().isBadRequest());
    propose(officer, svc, "USD", "1", TODAY.minusDays(1), null).andExpect(status().isBadRequest());
    propose(officer, svc, "JPY", "500", TODAY, null).andExpect(status().isCreated());
    propose(officer, svc, "EUR", "0.5", TODAY, null).andExpect(status().isCreated());
    propose(officer, svc, "EUR", "0.75", TODAY, null).andExpect(status().isConflict());
  }

  @Test
  void approvalAfterTheEffectiveDateIsRefused() throws Exception {
    String svc = service("T-LATE");
    UUID late = UUID.randomUUID();
    OrganizationContext.callAsSchemaOwner(() ->
        jdbc.sql(
                "INSERT INTO service_prices (id, organization_id, service_id, currency, amount, effective_from, status, reason, proposed_by)"
                    + " VALUES (?, ?, ?, 'USD', 5, ?, 'PROPOSED', 'r', ?)")
            .params(late, orgId, UUID.fromString(svc), TODAY.minusDays(2), officer.userId())
            .update());
    send(get("/api/v1/billing/approvals"), manager, "")
        .andExpect(jsonPath("$.prices[0].id").value(late.toString()));
    decide(manager, "prices", late.toString(), "approve", 0)
        .andExpect(status().isUnprocessableContent());
    decide(manager, "prices", late.toString(), "reject", 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
    send(get("/api/v1/billing/approvals"), reader, "").andExpect(status().isForbidden());
  }

  // --- tax rates ---------------------------------------------------------------------------------

  @Test
  void taxRatesAreEmptyUntilProposedAndApproved() throws Exception {
    String svc = service("T-TAX");
    send(get("/api/v1/billing/tax-rates"), reader, "").andExpect(jsonPath("$").isEmpty());
    propose(officer, svc, "USD", "20", TODAY, "TEST-RATE").andExpect(status().isBadRequest());

    send(
            post("/api/v1/billing/tax-rates"),
            officer,
            "{\"code\": \"TEST-RATE\", \"name\": \"Test rate\", \"rate_percent\": 101, \"effective_from\": \"%s\", \"reason\": \"x\"}"
                .formatted(TODAY))
        .andExpect(status().isBadRequest());
    String rate =
        read(
            send(
                    post("/api/v1/billing/tax-rates"),
                    officer,
                    "{\"code\": \"TEST-RATE\", \"name\": \"Test rate\", \"rate_percent\": 7.25, \"effective_from\": \"%s\", \"reason\": \"Fixture\"}"
                        .formatted(TODAY))
                .andExpect(status().isCreated()),
            "$.id");
    decide(officer, "tax-rates", rate, "approve", 0).andExpect(status().isForbidden());
    decide(manager, "tax-rates", rate, "approve", 0).andExpect(status().isOk());

    String price =
        read(
            propose(officer, svc, "USD", "20", TODAY, "TEST-RATE").andExpect(status().isCreated()),
            "$.id");
    decide(manager, "prices", price, "approve", 0).andExpect(status().isOk());
    effective(svc, "USD", TODAY)
        .andExpect(jsonPath("$.price.tax_rate_code").value("TEST-RATE"))
        .andExpect(jsonPath("$.tax_rate.rate_percent").value(7.25));
  }

  @Test
  void billingTablesAreIsolatedAndCannotBeDeleted() throws Exception {
    String svc = service("T-ISO");
    propose(officer, svc, "USD", "1", TODAY, null).andExpect(status().isCreated());
    send(
            post("/api/v1/billing/tax-rates"),
            officer,
            "{\"code\": \"ISO\", \"name\": \"x\", \"rate_percent\": 1, \"effective_from\": \"%s\", \"reason\": \"x\"}"
                .formatted(TODAY))
        .andExpect(status().isCreated());
    UUID otherOrg = fixtures.organization();
    for (String table : new String[] {"billable_services", "service_prices", "tax_rates"}) {
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
