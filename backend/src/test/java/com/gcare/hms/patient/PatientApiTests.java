package com.gcare.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Patient registration, duplicate review, search and access rules through the real API and database
 * (task 2.1; FR-003–FR-005; docs/17-PATIENT-IDENTITY-POLICY.md §4 scenarios 8–10). All people are
 * fictional.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PatientApiTests {

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;

  private MockMvc mvc;
  private UUID org;
  private UUID facility;
  private UUID otherFacility;
  private TestUser clerk;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    org = fixtures.organization();
    facility = fixtures.facility(org, "REG-A");
    otherFacility = fixtures.facility(org, "REG-B");
    clerk = fixtures.user(org);
    fixtures.grant(clerk, facility, Permissions.PATIENT_READ, Permissions.PATIENT_REGISTER);
  }

  private ResultActions register(TestUser user, String json) throws Exception {
    return mvc.perform(
        post("/api/v1/patients")
            .with(user.signedIn())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private String body(String extra) {
    return """
    {"registration_facility_id": "%s", "registration_source": "WALK_IN"%s}
    """
        .formatted(facility, extra);
  }

  private String registerOk(String extra) throws Exception {
    String response =
        register(clerk, body(extra))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(response, "$.id");
  }

  // --- registration ------------------------------------------------------------------------------

  @Test
  void registersPatientWithCheckDigitNumberAndIdentifier() throws Exception {
    register(
            clerk,
            body(
                """
                , "family_name": "Testperson", "given_names": "Charlie", "birth_date": "1992-04-01",
                  "sex": "FEMALE", "phone": "+231 77 000 0099",
                  "identifiers": [{"type": "NATIONAL_ID", "value": "test-1234", "verified": true}]
                """))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", matchesPattern("/api/v1/patients/[0-9a-f-]{36}")))
        .andExpect(jsonPath("$.patient_number", matchesPattern("P[0-9]{7}-[0-9]")))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.details_incomplete").value(false))
        .andExpect(jsonPath("$.identifiers[0].value").value("test-1234"))
        .andExpect(jsonPath("$.identifiers[0].source").value("REGISTRATION"));

    assertThat(fixtures.auditActions(clerk.userId())).contains("PATIENT_REGISTERED");
  }

  @Test
  void numbersAreSequentialAndValid() throws Exception {
    String first = registerOk(", \"family_name\": \"Seqone\", \"sex\": \"MALE\"");
    String second = registerOk(", \"family_name\": \"Seqtwo\", \"sex\": \"FEMALE\"");

    String n1 = numberOf(first);
    String n2 = numberOf(second);
    assertThat(PatientNumber.parse(n1)).contains(n1);
    assertThat(Long.parseLong(n2.substring(1, 8)))
        .isEqualTo(Long.parseLong(n1.substring(1, 8)) + 1);
  }

  /** Scenario 8: an unknown patient registers without blocking and is flagged incomplete. */
  @Test
  void unknownPatientRegistersAndIsFlaggedIncomplete() throws Exception {
    register(
            clerk,
            """
            {"registration_facility_id": "%s", "registration_source": "EMERGENCY"}
            """
                .formatted(facility))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.details_incomplete").value(true))
        .andExpect(jsonPath("$.sex").value("UNKNOWN"));
  }

  @Test
  void rejectsFutureBirthDateAndEstimatedWithoutDate() throws Exception {
    register(clerk, body(", \"birth_date\": \"2999-01-01\""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field_errors.birth_date[0]").value("must not be in the future"));
    register(clerk, body(", \"birth_date_estimated\": true"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field_errors.birth_date").exists());
  }

  // --- duplicate review (scenario 9)
  // --------------------------------------------------------------

  @Test
  void likelyDuplicateMustBeReviewedAndNeedsAReason() throws Exception {
    String existing =
        registerOk(
            """
            , "family_name": "Dupcheck", "given_names": "Delta", "birth_date": "1980-02-02",
              "sex": "MALE", "phone": "0770 555 001"
            """);
    String sameAgain =
        """
        , "family_name": "Dupcheck", "given_names": "Delta", "birth_date": "1980-02-02",
          "sex": "MALE", "phone": "0770555001"
        """;

    // 1. Not reviewed: 409 with the candidate and why it matched.
    register(clerk, body(sameAgain))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_CANDIDATES"))
        .andExpect(jsonPath("$.candidates", hasSize(1)))
        .andExpect(jsonPath("$.candidates[0].patient.id").value(existing))
        .andExpect(jsonPath("$.candidates[0].band").value("LIKELY"))
        .andExpect(jsonPath("$.candidates[0].evidence").isArray());

    // 2. Reviewed but no reason: refused.
    register(
            clerk,
            body(
                sameAgain
                    + ", \"duplicate_review\": {\"reviewed_candidate_ids\": [\""
                    + existing
                    + "\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field_errors['duplicate_review.reason']").exists());

    // 3. Reviewed with a reason: created, and the pair goes to the review queue.
    String created =
        registerOk(
            sameAgain
                + ", \"duplicate_review\": {\"reviewed_candidate_ids\": [\""
                + existing
                + "\"], \"reason\": \"Different person: confirmed by ID card photo\"}");

    assertThat(
            fixtures.count(
                "SELECT count(*) FROM patient_duplicate_flags WHERE patient_id = ?::uuid"
                    + " AND candidate_patient_id = ?::uuid AND status = 'OPEN'"
                    + " AND origin = 'REGISTERED_DESPITE_MATCH'",
                created,
                existing))
        .isEqualTo(1);
    // The free-text reason is not copied into the audit trail.
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE metadata::text LIKE '%ID card photo%'"))
        .isZero();
  }

  @Test
  void possibleDuplicateNeedsReviewButNoReason() throws Exception {
    String existing =
        registerOk(
            """
            , "family_name": "Possible", "given_names": "Echo", "birth_date": "1975-07-07",
              "sex": "FEMALE", "phone": "0770 000 111"
            """);
    String similar =
        """
        , "family_name": "Possible", "given_names": "Echo", "birth_date": "1975-07-07",
          "sex": "FEMALE", "phone": "0880 000 222"
        """;

    register(clerk, body(similar))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.candidates[0].band").value("POSSIBLE"));

    register(
            clerk,
            body(
                similar
                    + ", \"duplicate_review\": {\"reviewed_candidate_ids\": [\""
                    + existing
                    + "\"]}"))
        .andExpect(status().isCreated());
  }

  @Test
  void possibleDuplicatesOfARecordAreListed() throws Exception {
    String a =
        registerOk(
            ", \"family_name\": \"Listdup\", \"given_names\": \"Foxtrot\", \"birth_date\": \"1999-09-09\", \"sex\": \"MALE\"");
    String b =
        JsonPath.read(
            register(
                    clerk,
                    body(
                        ", \"family_name\": \"Listdup\", \"given_names\": \"Foxtrot\", \"birth_date\": \"1999-09-09\", \"sex\": \"MALE\", \"duplicate_review\": {\"reviewed_candidate_ids\": [\""
                            + a
                            + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");

    mvc.perform(get("/api/v1/patients/{id}/possible-duplicates", b).with(clerk.signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].patient.id", contains(a)));
  }

  // --- search (scenario 10) ----------------------------------------------------------------------

  @Test
  void nameOnlySearchIsRefused() throws Exception {
    mvc.perform(get("/api/v1/patients").param("name", "Testperson").with(clerk.signedIn()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SEARCH_TOO_BROAD"));
  }

  @Test
  void searchesByNumberAsTypedAndRejectsTypos() throws Exception {
    String id = registerOk(", \"family_name\": \"Numbersearch\", \"sex\": \"FEMALE\"");
    String number = numberOf(id);
    String digits = number.replaceAll("[^0-9]", "");

    mvc.perform(get("/api/v1/patients").param("number", digits).with(clerk.signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id", contains(id)))
        .andExpect(jsonPath("$.total").value(1));

    String typo =
        digits.substring(0, 3) + ((digits.charAt(3) - '0' + 1) % 10) + digits.substring(4);
    mvc.perform(get("/api/v1/patients").param("number", typo).with(clerk.signedIn()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field_errors.number").exists());
  }

  @Test
  void searchesByFuzzyNameWithBirthYearAndByIdentifierAndPhone() throws Exception {
    String id =
        registerOk(
            """
            , "family_name": "Fuzzysearch", "given_names": "Golf", "birth_date": "1988-08-08",
              "sex": "MALE", "phone": "0770 123 999",
              "identifiers": [{"type": "PASSPORT", "value": "TP-998877", "verified": false}]
            """);

    mvc.perform(
            get("/api/v1/patients")
                .param("name", "golf fuzysearch")
                .param("birth_year", "1988")
                .with(clerk.signedIn()))
        .andExpect(jsonPath("$.items[*].id", contains(id)));
    mvc.perform(
            get("/api/v1/patients")
                .param("identifier_type", "PASSPORT")
                .param("identifier_value", "tp 998877")
                .with(clerk.signedIn()))
        .andExpect(jsonPath("$.items[*].id", contains(id)));
    mvc.perform(get("/api/v1/patients").param("phone", "+231770123999").with(clerk.signedIn()))
        .andExpect(jsonPath("$.items[*].id", contains(id)));
  }

  @Test
  void searchAuditRecordsCriteriaTypesNeverValues() throws Exception {
    mvc.perform(
            get("/api/v1/patients")
                .param("name", "Secretname")
                .param("sex", "FEMALE")
                .with(clerk.signedIn()))
        .andExpect(status().isOk());

    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE actor_id = ?::uuid"
                    + " AND action = 'PATIENT_SEARCHED' AND metadata->>'criteria' = 'name+sex'",
                clerk.userId().toString()))
        .isEqualTo(1);
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE metadata::text ILIKE '%secretname%'"))
        .isZero();
  }

  // --- access ------------------------------------------------------------------------------------

  @Test
  void registeringAtAFacilityOutsideScopeIsRefused() throws Exception {
    register(
            clerk,
            """
            {"registration_facility_id": "%s", "registration_source": "WALK_IN"}
            """
                .formatted(otherFacility))
        .andExpect(status().isNotFound());
  }

  @Test
  void readersCannotRegisterAndOthersCannotSearch() throws Exception {
    TestUser reader = fixtures.user(org);
    fixtures.grant(reader, null, Permissions.PATIENT_READ);
    TestUser admin = fixtures.user(org);
    fixtures.grant(admin, null, Permissions.FACILITY_READ, Permissions.USER_MANAGE);

    register(reader, body("")).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/patients").param("number", "P1000001-6").with(admin.signedIn()))
        .andExpect(status().isForbidden());
  }

  @Test
  void patientsOfAnotherOrganizationAreInvisible() throws Exception {
    String id = registerOk(", \"family_name\": \"Isolated\", \"sex\": \"MALE\"");
    UUID otherOrg = fixtures.organization();
    TestUser outsider = fixtures.user(otherOrg);
    fixtures.grant(outsider, null, Permissions.PATIENT_READ);

    mvc.perform(get("/api/v1/patients/{id}", id).with(outsider.signedIn()))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/patients").param("number", numberOf(id)).with(outsider.signedIn()))
        .andExpect(jsonPath("$.items", hasSize(0)));
  }

  @Test
  void viewingAPatientIsAudited() throws Exception {
    String id = registerOk(", \"family_name\": \"Viewed\", \"sex\": \"FEMALE\"");

    mvc.perform(get("/api/v1/patients/{id}", id).with(clerk.signedIn())).andExpect(status().isOk());

    assertThat(fixtures.auditActions(clerk.userId())).contains("PATIENT_VIEWED");
  }

  @Test
  void patientRecordsCannotBeDeletedByTheApplication() {
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                org, () -> context.getBean(JdbcClient.class).sql("DELETE FROM patients").update()))
        .isInstanceOf(DataAccessException.class);
  }

  private String numberOf(String id) throws Exception {
    String response =
        mvc.perform(get("/api/v1/patients/{id}", id).with(clerk.signedIn()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(response, "$.patient_number");
  }
}
