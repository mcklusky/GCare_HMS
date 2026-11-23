package com.gcare.hms.downtime;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Downtime episodes, numbered paper forms and reconciliation (task 5.3; FR-027; docs/22). All
 * names, prices and catalog items are test fixtures.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DowntimeTests {

  private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private JdbcClient jdbc;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private UUID opd;
  private TestUser coordinator;
  private TestUser clerk;
  private TestUser clerk2;
  private TestUser nurse;
  private TestUser doctor;
  private TestUser cashier;
  private TestUser admin;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    opd = fixtures.department(orgId, facility, "OPD");
    coordinator = user(Permissions.DOWNTIME_MANAGE, Permissions.DOWNTIME_RECORD);
    String[] registration = {
      Permissions.PATIENT_READ,
      Permissions.PATIENT_REGISTER,
      Permissions.ENCOUNTER_READ,
      Permissions.ENCOUNTER_OPEN,
      Permissions.DOWNTIME_RECORD
    };
    clerk = user(registration);
    clerk2 = user(registration);
    nurse =
        user(
            Permissions.PATIENT_READ,
            Permissions.ENCOUNTER_READ,
            Permissions.OBSERVATION_READ,
            Permissions.OBSERVATION_RECORD,
            Permissions.DOWNTIME_RECORD);
    doctor =
        user(
            Permissions.PATIENT_READ,
            Permissions.ENCOUNTER_READ,
            Permissions.ORDER_READ,
            Permissions.ORDER_CREATE,
            Permissions.DOWNTIME_RECORD);
    cashier =
        user(
            Permissions.BILLING_READ,
            Permissions.INVOICE_READ,
            Permissions.INVOICE_MANAGE,
            Permissions.PAYMENT_RECORD,
            Permissions.DOWNTIME_RECORD);
    admin = user(Permissions.PAYMENT_METHOD_MANAGE, Permissions.ORDER_CATALOG_MANAGE);
  }

  // --- helpers -----------------------------------------------------------------------------------

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

  private ResultActions declare(TestUser by, Instant start, Instant end) throws Exception {
    return send(
        post("/api/v1/downtime/episodes"),
        by,
        "{\"facility_id\": \"%s\", \"started_at\": \"%s\", %s \"affected_areas\": \"Whole facility\", \"reason\": \"Network outage (drill)\"}"
            .formatted(facility, start, end == null ? "" : "\"ended_at\": \"%s\",".formatted(end)));
  }

  private void issue(String type, int count) throws Exception {
    send(
            post("/api/v1/downtime/forms"),
            coordinator,
            "{\"facility_id\": \"%s\", \"form_type\": \"%s\", \"count\": %d}"
                .formatted(facility, type, count))
        .andExpect(status().isCreated());
  }

  private ResultActions key(String episode, TestUser by, String body) throws Exception {
    return send(post("/api/v1/downtime/episodes/{id}/entries", episode), by, body);
  }

  private String registrationForm(int number, String family, Instant at) {
    return "{\"form_type\": \"REGISTRATION\", \"form_number\": %d, \"occurred_at\": \"%s\", \"registration\": {\"family_name\": \"%s\", \"given_names\": \"Paper\", \"birth_date\": \"1985-02-03\", \"sex\": \"FEMALE\", \"registration_source\": \"WALK_IN\", \"department_id\": \"%s\", \"encounter_type\": \"NEW_VISIT\"}}"
        .formatted(number, at, family, opd);
  }

  private ResultActions decide(String action, String entry, TestUser by, long v, String note)
      throws Exception {
    return send(
        post("/api/v1/downtime/entries/{id}/" + action, entry),
        by,
        "{\"version\": %d, \"note\": \"%s\"}".formatted(v, note));
  }

  private String issuedInvoice(String encounter) throws Exception {
    TestUser officer =
        user(
            Permissions.BILLING_READ,
            Permissions.BILLING_CATALOG_MANAGE,
            Permissions.PRICE_PROPOSE);
    TestUser manager = user(Permissions.BILLING_READ, Permissions.PRICE_APPROVE);
    String service =
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
    String invoice =
        read(
            send(
                    post("/api/v1/encounters/{id}/invoices", encounter),
                    cashier,
                    "{\"currency\": \"USD\"}")
                .andExpect(status().isCreated()),
            "$.id");
    ResultActions line =
        send(
                post("/api/v1/invoices/{id}/lines", invoice),
                cashier,
                "{\"version\": 0, \"service_id\": \"%s\", \"quantity\": 1, \"reason\": \"Consultation\"}"
                    .formatted(service))
            .andExpect(status().isOk());
    send(
            post("/api/v1/invoices/{id}/issue", invoice),
            cashier,
            "{\"version\": %d}".formatted(version(line)))
        .andExpect(status().isOk());
    return invoice;
  }

  // --- tests -------------------------------------------------------------------------------------

  @Test
  void paperFormsBecomeRecordsOnlyThroughReconciliationWithASecondPersonForHighRisk()
      throws Exception {
    Instant start = NOW.minus(3, ChronoUnit.HOURS);
    Instant paper = NOW.minus(2, ChronoUnit.HOURS);
    String episode =
        read(
            declare(coordinator, start, NOW.minus(1, ChronoUnit.HOURS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.episode_number").value(1)),
            "$.id");
    issue("REGISTRATION", 5);
    issue("TRIAGE", 5);
    issue("ORDER", 5);
    issue("PAYMENT", 5);

    // Keying: repeated identical submission is safe; a different keying of the same number is not.
    String family = "Downtime" + UUID.randomUUID().toString().substring(0, 6);
    ResultActions keyed =
        key(episode, clerk, registrationForm(1, family, paper))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.form_label").value("REG-000001"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.needs_second_person").value(true));
    String reg = read(keyed, "$.id");
    key(episode, clerk, registrationForm(1, family, paper))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(reg));
    key(episode, clerk, registrationForm(1, family + "X", paper)).andExpect(status().isConflict());
    key(episode, clerk, registrationForm(99, family, paper)).andExpect(status().isBadRequest());
    key(episode, clerk, registrationForm(2, family, NOW.minus(5, ChronoUnit.HOURS)))
        .andExpect(status().isBadRequest());
    assertThat(fixtures.count("SELECT count(*) FROM patients WHERE family_name = ?", family))
        .isZero();

    String tri =
        read(
            key(
                    episode,
                    nurse,
                    "{\"form_type\": \"TRIAGE\", \"form_number\": 1, \"registration_form_number\": 1, \"occurred_at\": \"%s\", \"triage\": {\"pulse_bpm\": 88, \"temperature_c\": 37.2}}"
                        .formatted(paper))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.needs_second_person").value(false)),
            "$.id");
    decide("reconcile", tri, nurse, 0, "Checked against paper")
        .andExpect(status().isUnprocessableContent());

    // Registration: proposer and approver must differ; the approver creates patient and visit.
    send(get("/api/v1/downtime/entries/{id}/candidates", reg), clerk, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidates.length()").value(0));
    send(
            post("/api/v1/downtime/entries/{id}/propose", reg),
            clerk,
            "{\"version\": 0, \"action\": \"APPLY\", \"note\": \"No existing record\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROPOSED"));
    decide("approve", reg, clerk, 1, "Checked").andExpect(status().isUnprocessableContent());
    ResultActions approved =
        decide("approve", reg, clerk2, 1, "Checked against paper")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RECONCILED"))
            .andExpect(jsonPath("$.events.length()").value(3));
    String encounter = read(approved, "$.encounter_id");
    assertThat(fixtures.count("SELECT count(*) FROM patients WHERE family_name = ?", family))
        .isEqualTo(1);
    decide("approve", reg, clerk2, 2, "Again").andExpect(status().isUnprocessableContent());

    // Triage: one person, on the reconciled visit.
    decide("reconcile", tri, nurse, 0, "Checked against paper")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RECONCILED"));
    decide("reconcile", tri, nurse, 1, "Again").andExpect(status().isUnprocessableContent());
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM triage_observations WHERE encounter_id = ?::uuid", encounter))
        .isEqualTo(1);

    // Order: the approver needs ORDER_CREATE; a failed approval creates nothing.
    String item =
        read(
            send(
                    post("/api/v1/order-catalog"),
                    admin,
                    "{\"code\": \"T-LAB\", \"name\": \"Test lab\", \"category\": \"LAB\", \"performing_department_id\": null}")
                .andExpect(status().isCreated()),
            "$.id");
    String ord =
        read(
            key(
                    episode,
                    coordinator,
                    "{\"form_type\": \"ORDER\", \"form_number\": 1, \"registration_form_number\": 1, \"occurred_at\": \"%s\", \"order\": {\"catalog_item_id\": \"%s\", \"priority\": \"ROUTINE\", \"ordered_by_name\": \"Dr Paper (fictional)\"}}"
                        .formatted(paper, item))
                .andExpect(status().isCreated()),
            "$.id");
    send(
            post("/api/v1/downtime/entries/{id}/propose", ord),
            coordinator,
            "{\"version\": 0, \"action\": \"APPLY\", \"note\": \"Matches paper\"}")
        .andExpect(status().isOk());
    decide("approve", ord, clerk2, 1, "Ok").andExpect(status().isForbidden());
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM clinical_orders WHERE encounter_id = ?::uuid", encounter))
        .isZero();
    decide("approve", ord, doctor, 1, "Ordered during downtime")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RECONCILED"));
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM clinical_orders WHERE encounter_id = ?::uuid", encounter))
        .isEqualTo(1);

    // Payment: on the visit's issued invoice, in the approver's open shift, at most once.
    String cash =
        read(
            send(
                    post("/api/v1/payment-methods"),
                    admin,
                    "{\"code\": \"T-CASH\", \"name\": \"Test cash\", \"is_cash\": true}")
                .andExpect(status().isCreated()),
            "$.id");
    String pay =
        read(
            key(
                    episode,
                    coordinator,
                    "{\"form_type\": \"PAYMENT\", \"form_number\": 1, \"registration_form_number\": 1, \"occurred_at\": \"%s\", \"payment\": {\"method_id\": \"%s\", \"amount\": \"10.00\", \"currency\": \"USD\"}}"
                        .formatted(paper, cash))
                .andExpect(status().isCreated()),
            "$.id");
    String invoice = issuedInvoice(encounter);
    // Choosing the invoice needs invoice access: the coordinator who keyed the form cannot.
    send(
            post("/api/v1/downtime/entries/{id}/propose", pay),
            coordinator,
            "{\"version\": 0, \"action\": \"APPLY\", \"note\": \"Receipt book\", \"invoice_id\": \"%s\"}"
                .formatted(invoice))
        .andExpect(status().isForbidden());
    TestUser cashier2 =
        user(Permissions.BILLING_READ, Permissions.INVOICE_READ, Permissions.DOWNTIME_RECORD);
    send(
            post("/api/v1/downtime/entries/{id}/propose", pay),
            cashier2,
            "{\"version\": 0, \"action\": \"APPLY\", \"note\": \"Receipt book\", \"invoice_id\": \"%s\"}"
                .formatted(invoice))
        .andExpect(status().isOk());
    decide("approve", pay, cashier, 1, "Cash handed in")
        .andExpect(status().isUnprocessableContent());
    send(post("/api/v1/shifts"), cashier, "{\"facility_id\": \"%s\"}".formatted(facility))
        .andExpect(status().isCreated());
    decide("approve", pay, cashier, 1, "Cash handed in")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RECONCILED"));
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM payments WHERE invoice_id = ?::uuid AND idempotency_key = ?::uuid",
                invoice,
                pay))
        .isEqualTo(1);

    // The visit shows its paper sources; the episode closes once the queue is empty.
    send(get("/api/v1/encounters/{id}/downtime-sources", encounter), nurse, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[0].occurred_at").exists());
    ResultActions ep =
        send(get("/api/v1/downtime/episodes/{id}", episode), coordinator, "")
            .andExpect(jsonPath("$.counts.RECONCILED").value(4));
    send(
            post("/api/v1/downtime/episodes/{id}/close", episode),
            coordinator,
            "{\"version\": %d, \"note\": \"All paper reconciled\"}".formatted(version(ep)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"));
    key(episode, clerk, registrationForm(2, family, paper))
        .andExpect(status().isUnprocessableContent());

    assertThat(fixtures.auditActions(clerk.userId()))
        .contains("DOWNTIME_FORM_KEYED", "DOWNTIME_RECONCILIATION_PROPOSED");
    assertThat(fixtures.auditActions(clerk2.userId()))
        .contains("DOWNTIME_FORM_RECONCILED", "PATIENT_REGISTERED", "ENCOUNTER_OPENED");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE organization_id = ? AND metadata::text LIKE ?",
                orgId,
                "%" + family + "%"))
        .isZero();
  }

  @Test
  void registrationsAreMatchedToExistingPatientsAndNeverSilentlyDuplicated() throws Exception {
    String family = "Known" + UUID.randomUUID().toString().substring(0, 6);
    String patient =
        read(
            send(
                    post("/api/v1/patients"),
                    clerk,
                    "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"%s\", \"given_names\": \"Paper\", \"birth_date\": \"1985-02-03\", \"sex\": \"FEMALE\"}"
                        .formatted(facility, family))
                .andExpect(status().isCreated()),
            "$.id");
    String episode =
        read(
            declare(coordinator, NOW.minus(2, ChronoUnit.HOURS), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE")),
            "$.id");
    declare(coordinator, NOW.minus(1, ChronoUnit.HOURS), null).andExpect(status().isConflict());
    issue("REGISTRATION", 2);
    String reg =
        read(
            key(episode, clerk, registrationForm(1, family, NOW.minus(1, ChronoUnit.HOURS)))
                .andExpect(status().isCreated()),
            "$.id");
    send(get("/api/v1/downtime/entries/{id}/candidates", reg), clerk, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidates[0].patient.id").value(patient));
    send(get("/api/v1/downtime/entries/{id}/candidates", reg), coordinator, "")
        .andExpect(status().isForbidden());
    // A new patient cannot be proposed without reviewing the candidates.
    send(
            post("/api/v1/downtime/entries/{id}/propose", reg),
            clerk,
            "{\"version\": 0, \"action\": \"APPLY\", \"note\": \"New\"}")
        .andExpect(status().isConflict());
    send(
            post("/api/v1/downtime/entries/{id}/propose", reg),
            clerk,
            "{\"version\": 0, \"action\": \"APPLY\", \"note\": \"Same person\", \"patient_id\": \"%s\"}"
                .formatted(patient))
        .andExpect(status().isOk());
    // Sent back with a note, proposed again, then approved by another clerk.
    decide("send-back", reg, clerk2, 1, "Check the phone number")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"));
    send(
            post("/api/v1/downtime/entries/{id}/propose", reg),
            clerk,
            "{\"version\": 2, \"action\": \"APPLY\", \"note\": \"Checked\", \"patient_id\": \"%s\"}"
                .formatted(patient))
        .andExpect(status().isOk());
    decide("approve", reg, clerk2, 3, "Same person")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.patient_id").value(patient))
        .andExpect(jsonPath("$.events.length()").value(5));
    assertThat(fixtures.count("SELECT count(*) FROM patients WHERE family_name = ?", family))
        .isEqualTo(1);
  }

  @Test
  void episodesAreManagedByTheCoordinatorAndCloseOnlyWhenEveryFormIsDecided() throws Exception {
    declare(nurse, NOW.minus(2, ChronoUnit.HOURS), null).andExpect(status().isForbidden());
    send(
            post("/api/v1/downtime/forms"),
            nurse,
            "{\"facility_id\": \"%s\", \"form_type\": \"TRIAGE\", \"count\": 5}"
                .formatted(facility))
        .andExpect(status().isForbidden());
    declare(coordinator, NOW.plus(1, ChronoUnit.HOURS), null).andExpect(status().isBadRequest());
    ResultActions declared =
        declare(coordinator, NOW.minus(2, ChronoUnit.HOURS), null).andExpect(status().isCreated());
    String episode = read(declared, "$.id");
    issue("TRIAGE", 3);
    issue("REGISTRATION", 3);
    send(get("/api/v1/downtime/forms?facility_id={f}", facility), coordinator, "")
        .andExpect(jsonPath("$.length()").value(2));
    String body =
        "{\"form_type\": \"TRIAGE\", \"form_number\": 2, \"registration_form_number\": 1, \"occurred_at\": \"%s\", \"triage\": {\"pulse_bpm\": 90}}"
            .formatted(NOW.minus(1, ChronoUnit.HOURS));
    String tri = read(key(episode, nurse, body).andExpect(status().isCreated()), "$.id");
    key(
            episode,
            nurse,
            "{\"form_type\": \"TRIAGE\", \"form_number\": 3, \"registration_form_number\": 1, \"occurred_at\": \"%s\", \"triage\": {}}"
                .formatted(NOW.minus(1, ChronoUnit.HOURS)))
        .andExpect(status().isBadRequest());
    send(
            post("/api/v1/downtime/episodes/{id}/close", episode),
            coordinator,
            "{\"version\": 0, \"note\": \"Done\"}")
        .andExpect(status().isUnprocessableContent());
    ResultActions ended =
        send(
                post("/api/v1/downtime/episodes/{id}/end", episode),
                coordinator,
                "{\"version\": 0, \"ended_at\": \"%s\"}"
                    .formatted(NOW.minus(30, ChronoUnit.MINUTES)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ENDED"));
    send(
            post("/api/v1/downtime/episodes/{id}/close", episode),
            coordinator,
            "{\"version\": %d, \"note\": \"Done\"}".formatted(version(ended)))
        .andExpect(status().isUnprocessableContent());
    // Keyed data never changes in the database.
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql("UPDATE downtime_entries SET data = '{}'::jsonb WHERE id = ?::uuid")
                        .params(tri)
                        .update()))
        .isInstanceOf(DataAccessException.class);
    // Triage forms are voided by one person, and the number may then be keyed again.
    send(
            post("/api/v1/downtime/entries/{id}/propose", tri),
            nurse,
            "{\"version\": 0, \"action\": \"VOID\", \"note\": \"x\"}")
        .andExpect(status().isUnprocessableContent());
    decide("void", tri, nurse, 0, "Keyed against the wrong registration")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VOIDED"));
    String again = read(key(episode, nurse, body).andExpect(status().isCreated()), "$.id");
    decide("void", again, nurse, 0, "Drill only").andExpect(status().isOk());
    send(
            post("/api/v1/downtime/episodes/{id}/close", episode),
            coordinator,
            "{\"version\": %d, \"note\": \"Drill finished\"}".formatted(version(ended)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"));
    assertThat(fixtures.auditActions(coordinator.userId()))
        .contains(
            "DOWNTIME_DECLARED", "DOWNTIME_FORMS_ISSUED", "DOWNTIME_ENDED", "DOWNTIME_CLOSED");
  }

  @Test
  void keyedContentsAreShownOnlyToRolesThatMayReadThemAndClinicalFormsNeedClinicalRights()
      throws Exception {
    String episode =
        read(
            declare(coordinator, NOW.minus(2, ChronoUnit.HOURS), null)
                .andExpect(status().isCreated()),
            "$.id");
    issue("REGISTRATION", 2);
    issue("TRIAGE", 2);
    Instant at = NOW.minus(1, ChronoUnit.HOURS);
    // The clerk wrote a malformed patient number on the paper form.
    String reg =
        read(
            key(
                    episode,
                    clerk,
                    registrationForm(1, "Hidden", at)
                        .replace(
                            "\"family_name\"",
                            "\"written_patient_number\": \"12345\", \"family_name\""))
                .andExpect(status().isCreated()),
            "$.id");
    send(get("/api/v1/downtime/entries/{id}/candidates", reg), clerk2, "")
        .andExpect(status().isOk());
    String tri =
        read(
            key(
                    episode,
                    nurse,
                    "{\"form_type\": \"TRIAGE\", \"form_number\": 1, \"registration_form_number\": 1, \"occurred_at\": \"%s\", \"triage\": {\"pulse_bpm\": 88}}"
                        .formatted(at))
                .andExpect(status().isCreated()),
            "$.id");

    // The clerk (patient access) sees the registration; the cashier and the coordinator do not.
    send(get("/api/v1/downtime/entries/{id}", reg), clerk2, "")
        .andExpect(jsonPath("$.data.family_name").value("Hidden"));
    send(get("/api/v1/downtime/entries/{id}", reg), cashier, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.form_label").exists())
        .andExpect(jsonPath("$.data").doesNotExist());
    send(get("/api/v1/downtime/episodes/{id}/entries", episode), coordinator, "")
        .andExpect(
            jsonPath("$[*].data")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    // Vitals only for clinical staff (or whoever keyed them).
    send(get("/api/v1/downtime/entries/{id}", tri), clerk2, "")
        .andExpect(jsonPath("$.data").doesNotExist());
    send(get("/api/v1/downtime/entries/{id}", tri), nurse, "")
        .andExpect(jsonPath("$.data.pulse_bpm").value(88));
    // Voiding a triage form needs the clinical right to record observations.
    send(get("/api/v1/downtime/entries/{id}", tri), cashier, "").andExpect(status().isOk());
    decide("void", tri, cashier, 0, "Looks wrong").andExpect(status().isForbidden());
  }

  @Test
  void episodesAndFormsAreInvisibleOutsideTheFacilityAndOrganization() throws Exception {
    String episode =
        read(
            declare(coordinator, NOW.minus(2, ChronoUnit.HOURS), null)
                .andExpect(status().isCreated()),
            "$.id");
    issue("REGISTRATION", 2);
    key(episode, clerk, registrationForm(1, "Isolated", NOW.minus(1, ChronoUnit.HOURS)))
        .andExpect(status().isCreated());
    UUID otherFacility = fixtures.facility(orgId, "GH2");
    TestUser elsewhere = fixtures.user(orgId);
    fixtures.grant(
        elsewhere, otherFacility, Permissions.DOWNTIME_MANAGE, Permissions.DOWNTIME_RECORD);
    send(get("/api/v1/downtime/episodes/{id}", episode), elsewhere, "")
        .andExpect(status().isNotFound());
    send(get("/api/v1/downtime/episodes/{id}/entries", episode), elsewhere, "")
        .andExpect(status().isNotFound());
    send(get("/api/v1/downtime/episodes", episode), elsewhere, "")
        .andExpect(jsonPath("$.length()").value(0));

    UUID otherOrg = fixtures.organization();
    UUID foreignFacility = fixtures.facility(otherOrg, "GH");
    TestUser foreign = fixtures.user(otherOrg);
    fixtures.grant(foreign, foreignFacility, Permissions.DOWNTIME_MANAGE);
    send(get("/api/v1/downtime/episodes/{id}", episode), foreign, "")
        .andExpect(status().isNotFound());
    // Every downtime table is isolated by organization (row-level security).
    for (String table :
        new String[] {
          "downtime_episodes",
          "downtime_form_counters",
          "downtime_form_batches",
          "downtime_entries",
          "downtime_entry_events"
        }) {
      assertThat(
              OrganizationContext.callAs(
                  orgId,
                  () -> jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single()))
          .as(table)
          .isPositive();
      assertThat(
              OrganizationContext.callAs(
                  otherOrg,
                  () -> jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single()))
          .as(table)
          .isZero();
    }
    send(get("/api/v1/downtime/episodes", episode), user(Permissions.PATIENT_READ), "")
        .andExpect(status().isForbidden());
  }
}
