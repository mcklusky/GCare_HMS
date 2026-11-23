package com.gcare.hms.radiology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** DICOMweb client against a stub archive (task 6.1; docs/24). */
class DicomWebArchiveTests {

  private StubDicomWeb stub;

  @BeforeEach
  void start() throws Exception {
    stub = new StubDicomWeb();
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  private DicomWebArchive archive(String user, String token, String viewer) {
    return new DicomWebArchive(
        new ImagingArchiveProperties(
            stub.baseUrl(), user, "pw", token, viewer, Duration.ofSeconds(3), 50),
        JsonMapper.builder().build());
  }

  @Test
  void readsOnlyMatchingMetadataAndSkipsUnusableUids() {
    List<ImagingArchive.Study> studies = archive("u", null, null).studiesByAccession("ACC-T-1");
    assertThat(studies).hasSize(1);
    ImagingArchive.Study s = studies.get(0);
    assertThat(s.studyInstanceUid()).isEqualTo("1.2.826.0.1.3680043.2.1125.1");
    assertThat(s.accessionNumber()).isEqualTo("ACC-T-1");
    assertThat(s.patientId()).isEqualTo("P-000001");
    assertThat(s.modalities()).containsExactly("CR", "DX");
    assertThat(s.studyDate()).isEqualTo(LocalDate.of(2026, 11, 23));
    assertThat(s.description()).isEqualTo("Synthetic chest");
    assertThat(s.seriesCount()).isEqualTo(1);
    assertThat(s.instanceCount()).isEqualTo(3);
    assertThat(stub.lastQuery.get())
        .startsWith("AccessionNumber=ACC-T-1&")
        .contains("includefield=00201206", "limit=50");
    assertThat(stub.lastAuth.get()).isEqualTo("Basic dTpwdw==");
  }

  @Test
  void encodesCriteriaAndQueriesByDateRange() {
    archive(null, "tok", null).studiesByAccession("A&B=C");
    assertThat(stub.lastQuery.get()).startsWith("AccessionNumber=A%26B%3DC&");
    assertThat(stub.lastAuth.get()).isEqualTo("Bearer tok");
    archive(null, null, null).studiesBetween(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 23));
    assertThat(stub.lastQuery.get()).startsWith("StudyDate=20261101-20261123&");
    assertThat(stub.lastAuth.get()).isNull();
  }

  @Test
  void viewerLinksOnlyForValidUids() {
    DicomWebArchive a = archive(null, null, "https://viewer.example/v?study={StudyInstanceUID}");
    assertThat(a.viewerUrl("1.2.3")).contains(URI.create("https://viewer.example/v?study=1.2.3"));
    assertThat(a.viewerUrl("1.2.3&x=<script>")).isEmpty();
    assertThat(a.viewerUrl("1." + "2".repeat(70))).isEmpty();
    assertThat(archive(null, null, null).viewerUrl("1.2.3")).isEmpty();
  }

  @Test
  void archiveFailuresAreReportedNotHidden() {
    DicomWebArchive a = archive(null, null, null);
    assertThat(a.status().reachable()).isTrue();
    stub.status = 204;
    assertThat(a.studiesByAccession("X")).isEmpty();
    stub.status = 401;
    assertThatThrownBy(() -> a.studiesByAccession("X")).hasMessageContaining("refused");
    assertThat(a.status().reachable()).isFalse();
    stub.status = 200;
    stub.body = "{\"not\": \"a list\"}";
    assertThatThrownBy(() -> a.studiesByAccession("X"))
        .isInstanceOf(ImagingArchive.UnavailableException.class);
    stub.close();
    ImagingArchive.Status down = a.status();
    assertThat(down.configured()).isTrue();
    assertThat(down.reachable()).isFalse();
  }

  @Test
  void configurationIsCheckedAtStartUp() {
    assertThatThrownBy(() ->
            new ImagingArchiveProperties(
                URI.create("ftp://x/dicom-web"), null, null, null, null, null, null))
        .hasMessageContaining("http(s)");
    assertThatThrownBy(() ->
            new ImagingArchiveProperties(
                URI.create("https://x/dicom-web"), null, null, null, "https://v/{uid}", null, null))
        .hasMessageContaining("{StudyInstanceUID}");
    DicomWebArchive.NotConfigured none = new DicomWebArchive.NotConfigured();
    assertThat(none.status().configured()).isFalse();
    assertThatThrownBy(() -> none.studiesByAccession("X"))
        .isInstanceOf(ImagingArchive.UnavailableException.class);
  }
}
