package com.gcare.hms.orders;

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

/** Order catalog, orders and referrals (task 3.3; FR-009). Catalog items are test fixtures. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrderTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private JdbcClient jdbc;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private UUID opd;
  private UUID lab;
  private TestUser admin;
  private TestUser clerk;
  private TestUser doctor;
  private TestUser nurse;
  private TestUser labStaff;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    opd = fixtures.department(orgId, facility, "OPD");
    lab = fixtures.department(orgId, facility, "LAB");
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
    nurse = user(Permissions.ENCOUNTER_READ, Permissions.ORDER_READ);
    labStaff = user(Permissions.PATIENT_READ, Permissions.ORDER_READ, Permissions.ORDER_PROCESS);
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

  private String item(String code, String category, UUID department) throws Exception {
    String dept = department == null ? "null" : "\"" + department + "\"";
    return read(
        send(
                post("/api/v1/order-catalog"),
                admin,
                "{\"code\": \"%s\", \"name\": \"Test item %s\", \"category\": \"%s\", \"performing_department_id\": %s}"
                    .formatted(code, code, category, dept))
            .andExpect(status().isCreated()),
        "$.id");
  }

  private String visit() throws Exception {
    String patient =
        read(
            send(
                    post("/api/v1/patients"),
                    clerk,
                    "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"Order%s\", \"given_names\": \"Case\", \"birth_date\": \"1972-02-02\", \"sex\": \"FEMALE\"}"
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

  private ResultActions order(String encounter, String item, String priority) throws Exception {
    return send(
        post("/api/v1/encounters/{id}/orders", encounter),
        doctor,
        "{\"catalog_item_id\": \"%s\", \"priority\": \"%s\", \"indication\": \"Persistent fever\"}"
            .formatted(item, priority));
  }

  // --- catalog -----------------------------------------------------------------------------------

  @Test
  void catalogIsManagedByAdministratorsAndOrdersKeepASnapshot() throws Exception {
    String itemId = item("T-LAB-1", "LAB", lab);
    send(
            post("/api/v1/order-catalog"),
            admin,
            "{\"code\": \"T-LAB-1\", \"name\": \"Again\", \"category\": \"LAB\"}")
        .andExpect(status().isConflict());
    send(
            post("/api/v1/order-catalog"),
            doctor,
            "{\"code\": \"T-X\", \"name\": \"X\", \"category\": \"LAB\"}")
        .andExpect(status().isForbidden());
    send(
            post("/api/v1/order-catalog"),
            admin,
            "{\"code\": \"bad code\", \"name\": \"X\", \"category\": \"LAB\"}")
        .andExpect(status().isBadRequest());

    String e = visit();
    String orderId = read(order(e, itemId, "ROUTINE").andExpect(status().isCreated()), "$.id");

    // Renaming and deactivating the item does not change the order.
    send(
            put("/api/v1/order-catalog/{id}", itemId),
            admin,
            "{\"version\": 0, \"name\": \"Renamed\", \"performing_department_id\": \"%s\", \"status\": \"INACTIVE\"}"
                .formatted(lab))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INACTIVE"));
    send(get("/api/v1/encounters/{id}/orders", e), nurse, "")
        .andExpect(jsonPath("$[0].id").value(orderId))
        .andExpect(jsonPath("$[0].item.name").value("Test item T-LAB-1"));
    send(get("/api/v1/order-catalog"), doctor, "")
        .andExpect(jsonPath("$[?(@.code == 'T-LAB-1')]").isEmpty());
    send(get("/api/v1/order-catalog").param("include_inactive", "true"), admin, "")
        .andExpect(jsonPath("$[?(@.code == 'T-LAB-1')].name").value(Matchers.contains("Renamed")));
    order(e, itemId, "ROUTINE").andExpect(status().isBadRequest());

    // The database refuses to change an order's item snapshot.
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE clinical_orders SET item_name = 'x' WHERE id = ?")
                        .params(UUID.fromString(orderId))
                        .update()))
        .isInstanceOf(DataAccessException.class);
  }

  // --- orders ------------------------------------------------------------------------------------

  @Test
  void orderMovesThroughTheWorklistWithHistory() throws Exception {
    String itemId = item("T-LAB-2", "LAB", lab);
    String e = visit();
    String orderId =
        read(
            order(e, itemId, "ROUTINE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.requested_by.id").value(doctor.userId().toString()))
                .andExpect(jsonPath("$.performing_department.code").value("LAB")),
            "$.id");

    send(
            get("/api/v1/orders")
                .param("facility_id", facility.toString())
                .param("department_id", lab.toString()),
            labStaff,
            "")
        .andExpect(jsonPath("$.items[0].id").value(orderId))
        .andExpect(jsonPath("$.items[0].indication").value("Persistent fever"));
    send(post("/api/v1/orders/{id}/complete", orderId), labStaff, "{\"version\": 0}")
        .andExpect(status().isUnprocessableContent());
    send(post("/api/v1/orders/{id}/start", orderId), labStaff, "{\"version\": 0}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    send(post("/api/v1/orders/{id}/start", orderId), doctor, "{\"version\": 1}")
        .andExpect(status().isForbidden());
    send(post("/api/v1/orders/{id}/complete", orderId), labStaff, "{\"version\": 0}")
        .andExpect(status().isConflict());
    send(post("/api/v1/orders/{id}/complete", orderId), labStaff, "{\"version\": 1}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(
            jsonPath("$.events[*].event_type")
                .value(Matchers.contains("REQUESTED", "STARTED", "COMPLETED")));
    send(post("/api/v1/orders/{id}/cancel", orderId), doctor, "{\"version\": 2, \"reason\": \"x\"}")
        .andExpect(status().isUnprocessableContent());

    assertThat(fixtures.auditActions(labStaff.userId()))
        .contains("ORDER_STARTED", "ORDER_COMPLETED");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE resource_type IN ('Order', 'Encounter')"
                    + " AND (metadata::text LIKE '%fever%' OR metadata::text LIKE '%T-LAB-2%')"))
        .isZero();
  }

  @Test
  void cancellingNeedsAReasonAndUrgentOrdersComeFirst() throws Exception {
    String itemId = item("T-IMG-1", "IMAGING", lab);
    String e = visit();
    String routine = read(order(e, itemId, "ROUTINE").andExpect(status().isCreated()), "$.id");
    String urgent = read(order(e, itemId, "URGENT").andExpect(status().isCreated()), "$.id");
    send(get("/api/v1/orders").param("department_id", lab.toString()), labStaff, "")
        .andExpect(jsonPath("$.items[0].id").value(urgent))
        .andExpect(jsonPath("$.items[1].id").value(routine));

    send(post("/api/v1/orders/{id}/cancel", routine), doctor, "{\"version\": 0, \"reason\": \" \"}")
        .andExpect(status().isBadRequest());
    send(
            post("/api/v1/orders/{id}/cancel", routine),
            doctor,
            "{\"version\": 0, \"reason\": \"Ordered twice\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.cancel_reason").value("Ordered twice"));
    send(get("/api/v1/orders").param("department_id", lab.toString()), labStaff, "")
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void ordersNeedAnOpenVisitAnItemOfThisFacilityAndPermission() throws Exception {
    UUID otherFacility = fixtures.facility(orgId, "OTHER");
    UUID otherLab = fixtures.department(orgId, otherFacility, "LAB");
    fixtures.grant(admin, otherFacility, Permissions.ORDER_CATALOG_MANAGE);
    String elsewhere = item("T-ELSEWHERE", "LAB", otherLab);
    String referral = item("T-REF-1", "REFERRAL", null);
    String e = visit();

    order(e, elsewhere, "ROUTINE").andExpect(status().isBadRequest());
    send(
            post("/api/v1/encounters/{id}/orders", e),
            nurse,
            "{\"catalog_item_id\": \"%s\", \"priority\": \"ROUTINE\"}".formatted(referral))
        .andExpect(status().isForbidden());
    send(
            post("/api/v1/encounters/{id}/orders", e),
            labStaff,
            "{\"catalog_item_id\": \"%s\", \"priority\": \"ROUTINE\"}".formatted(referral))
        .andExpect(status().isForbidden());
    order(e, referral, "ROUTINE")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.performing_department").doesNotExist());

    TestUser outsider = fixtures.user(orgId);
    fixtures.grant(outsider, otherFacility, Permissions.ORDER_READ, Permissions.ORDER_CREATE);
    send(get("/api/v1/encounters/{id}/orders", e), outsider, "").andExpect(status().isNotFound());

    send(post("/api/v1/encounters/{id}/close", e), doctor, "{\"version\": 1}")
        .andExpect(status().isOk());
    order(e, referral, "ROUTINE").andExpect(status().isUnprocessableContent());
  }

  @Test
  void orderTablesAreIsolatedAndCannotBeDeleted() throws Exception {
    String itemId = item("T-ISO", "LAB", lab);
    order(visit(), itemId, "ROUTINE").andExpect(status().isCreated());
    UUID otherOrg = fixtures.organization();
    for (String table : new String[] {"order_catalog_items", "clinical_orders", "order_events"}) {
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
