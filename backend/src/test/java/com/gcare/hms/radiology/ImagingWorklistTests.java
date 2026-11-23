package com.gcare.hms.radiology;

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
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Imaging worklist, radiographer steps and study linking (task 6.3; FR-010, FR-011; docs/25),
 * against a stub DICOMweb archive. Catalog items, patients and studies are test fixtures.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ImagingWorklistTests {

  private static final StubDicomWeb STUB;

  static {
    try {
      STUB = new StubDicomWeb();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void archive(DynamicPropertyRegistry r) {
    r.add("gcare.imaging.archive.base-url", () -> STUB.baseUrl().toString());
    r.add(
        "gcare.imaging.archive.viewer-url-template",
        () -> "https://viewer.test.invalid/?study={StudyInstanceUID}");
  }

  @AfterAll
  static void stop() {
    STUB.close();
  }

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityFixtures fixtures;
  @Autowired private JdbcClient jdbc;
  @Autowired private ImagingService imaging;

  private MockMvc mvc;
  private UUID orgId;
  private UUID facility;
  private TestUser clerk;
  private TestUser doctor;
  private TestUser radiographer;
  private TestUser radiographer2;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    STUB.status = 200;
    STUB.body = "[]";
    orgId = fixtures.organization();
    facility = fixtures.facility(orgId, "GH");
    clerk =
        user(
            Permissions.PATIENT_READ,
            Permissions.PATIENT_REGISTER,
            Permissions.ENCOUNTER_READ,
            Permissions.ENCOUNTER_OPEN);
    doctor = user(Permissions.ENCOUNTER_READ, Permissions.ORDER_READ, Permissions.ORDER_CREATE);
    String[] rad = {
      Permissions.PATIENT_READ,
      Permissions.ORDER_READ,
      Permissions.ORDER_PROCESS,
      Permissions.IMAGING_WORKLIST,
      Permissions.IMAGING_LINK
    };
    radiographer = user(rad);
    radiographer2 = user(rad);
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

  /** An imaging order for a new patient; returns {order id, patient number}. */
  private String[] imagingOrder() throws Exception {
    UUID rad =
        fixtures.department(orgId, facility, "RAD" + UUID.randomUUID().toString().substring(0, 4));
    TestUser admin = user(Permissions.ORDER_CATALOG_MANAGE);
    String code = "T-XR-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String item =
        read(
            send(
                    post("/api/v1/order-catalog"),
                    admin,
                    "{\"code\": \"%s\", \"name\": \"Test chest X-ray\", \"category\": \"IMAGING\", \"performing_department_id\": \"%s\"}"
                        .formatted(code, rad))
                .andExpect(status().isCreated()),
            "$.id");
    ResultActions p =
        send(
                post("/api/v1/patients"),
                clerk,
                "{\"registration_facility_id\": \"%s\", \"registration_source\": \"WALK_IN\", \"family_name\": \"Img%s\", \"given_names\": \"Test\", \"birth_date\": \"1970-01-05\", \"sex\": \"MALE\"}"
                    .formatted(facility, UUID.randomUUID().toString().substring(0, 6)))
            .andExpect(status().isCreated());
    String encounter =
        read(
            send(
                    post("/api/v1/encounters"),
                    clerk,
                    "{\"patient_id\": \"%s\", \"facility_id\": \"%s\", \"department_id\": \"%s\", \"encounter_type\": \"NEW_VISIT\"}"
                        .formatted(read(p, "$.id"), facility, rad))
                .andExpect(status().isCreated()),
            "$.encounter.id");
    String order =
        read(
            send(
                    post("/api/v1/encounters/{id}/orders", encounter),
                    doctor,
                    "{\"catalog_item_id\": \"%s\", \"priority\": \"ROUTINE\"}".formatted(item))
                .andExpect(status().isCreated()),
            "$.id");
    return new String[] {order, read(p, "$.patient_number")};
  }

  private static String study(String uid, String accession, String patientId) {
    return """
    {"0020000D":{"vr":"UI","Value":["%s"]},"00080050":{"vr":"SH","Value":["%s"]},
     "00100020":{"vr":"LO","Value":["%s"]},"00080061":{"vr":"CS","Value":["CR"]},
     "00080020":{"vr":"DA","Value":["20261123"]},"00201208":{"vr":"IS","Value":[2]}}
    """
        .formatted(uid, accession, patientId);
  }

  @Test
  void ordersGetAccessionNumbersStepsMoveTheOrderAndExactMatchesLinkAutomatically()
      throws Exception {
    String[] o = imagingOrder();
    String order = o[0];
    String patientNumber = o[1];
    String sel = "$[?(@.order_id == '" + order + "')]";
    ResultActions list =
        send(get("/api/v1/imaging/worklist"), radiographer, "")
            .andExpect(status().isOk())
            .andExpect(jsonPath(sel + ".accession_number").value("ACC000000001"))
            .andExpect(jsonPath(sel + ".step").value("ORDERED"));
    assertThat(read(list, sel + ".patient.patient_number")).contains(patientNumber);

    send(post("/api/v1/imaging/orders/{id}/examined", order), radiographer, "{\"version\": 0}")
        .andExpect(status().isUnprocessableContent());
    send(post("/api/v1/imaging/orders/{id}/arrived", order), radiographer, "{\"version\": 0}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.step").value("ARRIVED"))
        .andExpect(jsonPath("$.order_status").value("IN_PROGRESS"));
    send(post("/api/v1/imaging/orders/{id}/arrived", order), radiographer, "{\"version\": 1}")
        .andExpect(status().isUnprocessableContent());
    send(post("/api/v1/imaging/orders/{id}/examined", order), radiographer, "{\"version\": 1}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.step").value("EXAMINED"))
        .andExpect(jsonPath("$.order_status").value("COMPLETED"));

    // The archive holds: the right study, one with the right accession but another patient ID,
    // and one with an unknown accession number.
    STUB.body =
        "["
            + study("1.2.3.100", "ACC000000001", patientNumber)
            + ","
            + study("1.2.3.101", "ACC000000001", "P9999999-9")
            + ","
            + study("1.2.3.102", "ACC999999999", patientNumber)
            + "]";
    send(post("/api/v1/imaging/orders/{id}/check-archive", order), radiographer, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.step").value("IMAGES_AVAILABLE"))
        .andExpect(jsonPath("$.studies.length()").value(1))
        .andExpect(jsonPath("$.studies[0].study_instance_uid").value("1.2.3.100"))
        .andExpect(jsonPath("$.studies[0].link_method").value("AUTOMATIC"))
        .andExpect(
            jsonPath("$.studies[0].viewer_url")
                .value("https://viewer.test.invalid/?study=1.2.3.100"));
    send(get("/api/v1/imaging/studies/unlinked"), radiographer, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(
            jsonPath("$[?(@.study_instance_uid == '1.2.3.101')].unmatched_reason")
                .value("The patient ID on the study differs from the order's patient number"))
        .andExpect(
            jsonPath("$[?(@.study_instance_uid == '1.2.3.102')].unmatched_reason")
                .value("No imaging order has this accession number"));

    // A repeated check finds nothing new and links nothing twice; the scheduled sweep agrees.
    send(post("/api/v1/imaging/orders/{id}/check-archive", order), radiographer, "")
        .andExpect(jsonPath("$.studies.length()").value(1));
    var swept = OrganizationContext.callAs(orgId, () -> imaging.sweep(orgId, 2));
    assertThat(swept.studiesSeen()).isEqualTo(3);
    assertThat(swept.newStudies()).isZero();
    assertThat(swept.linked()).isZero();

    // The accession number never changes.
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql(
                            "UPDATE imaging_orders SET accession_number = 'ACC000000777' WHERE order_id = ?::uuid")
                        .params(order)
                        .update()))
        .isInstanceOf(DataAccessException.class);
    assertThat(fixtures.auditActions(radiographer.userId()))
        .contains(
            "IMAGING_WORKLIST_VIEWED",
            "IMAGING_PATIENT_ARRIVED",
            "IMAGING_EXAMINATION_DONE",
            "IMAGING_ARCHIVE_CHECKED");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE organization_id = ? AND metadata::text LIKE ?",
                orgId,
                "%" + patientNumber + "%"))
        .isZero();
  }

  @Test
  void mismatchesAreLinkedOnlyByTwoPeopleAndUnlinkingNeedsAReason() throws Exception {
    String[] o = imagingOrder();
    send(get("/api/v1/imaging/worklist"), radiographer, "").andExpect(status().isOk());
    String accession =
        OrganizationContext.callAs(
            orgId,
            () ->
                jdbc.sql("SELECT accession_number FROM imaging_orders WHERE order_id = ?::uuid")
                    .params(o[0])
                    .query(String.class)
                    .single());
    // The modality typed the patient ID wrongly.
    STUB.body = "[" + study("1.2.3.200", accession, "TYPO-123") + "]";
    send(post("/api/v1/imaging/orders/{id}/check-archive", o[0]), radiographer, "")
        .andExpect(jsonPath("$.studies.length()").value(0));
    String studyId =
        read(send(get("/api/v1/imaging/studies/unlinked"), radiographer, ""), "$[0].id");

    send(
            post("/api/v1/imaging/studies/{id}/propose", studyId),
            radiographer,
            "{\"version\": 0, \"accession_number\": \"ACC000000999\", \"note\": \"x\"}")
        .andExpect(status().isBadRequest());
    send(
            post("/api/v1/imaging/studies/{id}/propose", studyId),
            radiographer,
            "{\"version\": 0, \"accession_number\": \"%s\", \"note\": \"Checked patient at scanner\"}"
                .formatted(accession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROPOSED"));
    send(
            post("/api/v1/imaging/studies/{id}/approve", studyId),
            radiographer,
            "{\"version\": 1, \"note\": \"Own\"}")
        .andExpect(status().isUnprocessableContent());
    send(
            post("/api/v1/imaging/studies/{id}/approve", studyId),
            radiographer2,
            "{\"version\": 1, \"note\": \"Same patient, typo at modality\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LINKED"))
        .andExpect(jsonPath("$.link_method").value("REVIEWED"));
    send(
            post("/api/v1/imaging/studies/{id}/unlink", studyId),
            radiographer2,
            "{\"version\": 2, \"note\": \"\"}")
        .andExpect(status().isBadRequest());
    send(
            post("/api/v1/imaging/studies/{id}/unlink", studyId),
            radiographer2,
            "{\"version\": 2, \"note\": \"Linked to the wrong order\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNMATCHED"));
    send(get("/api/v1/imaging/studies/{id}/history", studyId), radiographer, "")
        .andExpect(
            jsonPath("$[*].event_type")
                .value(
                    org.hamcrest.Matchers.contains(
                        "DISCOVERED", "PROPOSED", "LINKED", "UNLINKED")));
    // The database refuses a reviewed link approved by the proposer.
    assertThatThrownBy(() ->
            OrganizationContext.callAs(
                orgId,
                () ->
                    jdbc.sql(
                            """
                            UPDATE imaging_studies SET status = 'LINKED', order_id = ?::uuid, link_method = 'REVIEWED',
                                linked_at = now(), linked_by = ?::uuid, proposed_order_id = ?::uuid,
                                proposed_by = ?::uuid, proposed_at = now(), proposal_note = 'x'
                            WHERE id = ?::uuid
                            """)
                        .params(o[0], radiographer.userId(), o[0], radiographer.userId(), studyId)
                        .update()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void othersCannotSeeOrActAndArchiveFailuresAreReported() throws Exception {
    String[] o = imagingOrder();
    TestUser nurse = user(Permissions.PATIENT_READ, Permissions.OBSERVATION_RECORD);
    send(get("/api/v1/imaging/worklist"), nurse, "").andExpect(status().isForbidden());
    send(get("/api/v1/imaging/studies/unlinked"), nurse, "").andExpect(status().isForbidden());
    UUID other = fixtures.facility(orgId, "GH2");
    TestUser elsewhere = fixtures.user(orgId);
    fixtures.grant(
        elsewhere,
        other,
        Permissions.IMAGING_WORKLIST,
        Permissions.IMAGING_LINK,
        Permissions.ORDER_PROCESS,
        Permissions.ORDER_READ);
    send(get("/api/v1/imaging/worklist"), elsewhere, "")
        .andExpect(jsonPath("$[?(@.order_id == '" + o[0] + "')]").isEmpty());
    send(post("/api/v1/imaging/orders/{id}/arrived", o[0]), elsewhere, "{\"version\": 0}")
        .andExpect(status().isNotFound());
    STUB.status = 503;
    send(post("/api/v1/imaging/orders/{id}/check-archive", o[0]), radiographer, "")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ARCHIVE_UNAVAILABLE"));
  }
}
