package com.gcare.hms.radiology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.testsupport.IdentityFixtures;
import com.gcare.hms.testsupport.IdentityFixtures.TestUser;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Archive status and study look-up API (task 6.1; docs/24), against a stub DICOMweb archive. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ImagingArchiveApiTests {

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
    // Tests create a new organization each time, so the archive cannot be bound to one.
    r.add("gcare.imaging.archive.allow-any-organization", () -> "true");
    r.add("gcare.imaging.archive.username", () -> "hms");
    r.add("gcare.imaging.archive.password", () -> "test-only");
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

  private MockMvc mvc;
  private UUID orgId;
  private TestUser admin;
  private TestUser nurse;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    STUB.status = 200;
    STUB.body = StubDicomWeb.TWO_STUDIES;
    orgId = fixtures.organization();
    UUID facility = fixtures.facility(orgId, "GH");
    admin = fixtures.user(orgId);
    fixtures.grant(admin, facility, Permissions.IMAGING_ARCHIVE_ADMIN);
    nurse = fixtures.user(orgId);
    fixtures.grant(nurse, facility, Permissions.OBSERVATION_RECORD, Permissions.PATIENT_READ);
  }

  @Test
  void theImagingAdministratorChecksTheArchiveAndLooksUpStudies() throws Exception {
    mvc.perform(get("/api/v1/imaging/archive/status").with(admin.signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.reachable").value(true))
        .andExpect(jsonPath("$.viewer_configured").value(true));
    mvc.perform(
            get("/api/v1/imaging/archive/studies")
                .param("accession_number", "ACC-T-1")
                .with(admin.signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].patient_id").value("P-000001"))
        .andExpect(jsonPath("$[0].series_count").value(1))
        .andExpect(
            jsonPath("$[0].viewer_url")
                .value("https://viewer.test.invalid/?study=1.2.826.0.1.3680043.2.1125.1"))
        .andExpect(jsonPath("$[0].patient_name").doesNotExist());
    assertThat(fixtures.auditActions(admin.userId())).contains("IMAGING_ARCHIVE_QUERIED");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM audit_events WHERE organization_id = ? AND metadata::text LIKE '%ACC-T-1%'",
                orgId))
        .isZero();
  }

  @Test
  void othersAreRefusedAndBadInputOrArchiveFailuresAreClear() throws Exception {
    mvc.perform(get("/api/v1/imaging/archive/status").with(nurse.signedIn()))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/imaging/archive/studies")
                .param("accession_number", "ACC-T-1")
                .with(nurse.signedIn()))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/imaging/archive/studies")
                .param("accession_number", "x' OR 1=1")
                .with(admin.signedIn()))
        .andExpect(status().isBadRequest());
    STUB.status = 500;
    mvc.perform(
            get("/api/v1/imaging/archive/studies")
                .param("accession_number", "ACC-T-1")
                .with(admin.signedIn()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ARCHIVE_UNAVAILABLE"));
    mvc.perform(get("/api/v1/imaging/archive/status").with(admin.signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reachable").value(false));
  }
}
