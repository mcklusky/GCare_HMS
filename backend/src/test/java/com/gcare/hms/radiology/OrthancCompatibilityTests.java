package com.gcare.hms.radiology;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Evaluation check against a real candidate archive (docs/24 §3). Runs only when GCARE_ORTHANC_URL
 * points at the local evaluation Orthanc (docker compose --profile imaging up -d orthanc), e.g.
 * {@code GCARE_ORTHANC_URL=http://127.0.0.1:8142}; skipped in CI. Synthetic studies only.
 */
@EnabledIfEnvironmentVariable(named = "GCARE_ORTHANC_URL", matches = "https?://.+")
class OrthancCompatibilityTests {

  private final String url = System.getenv("GCARE_ORTHANC_URL");
  private final String user = System.getenv().getOrDefault("GCARE_ORTHANC_USER", "gcare-local");
  private final String password =
      System.getenv().getOrDefault("GCARE_ORTHANC_PASSWORD", "local-dev-only-not-a-secret");

  @Test
  void studiesSentToTheCandidateArchiveAreFoundByAccessionAndDate() throws Exception {
    String accession = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String today = LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    String auth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    HttpResponse<String> created =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url + "/tools/create-dicom"))
                    .header("Authorization", auth)
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """
                            {"Tags": {"PatientID": "P-SYNTH-1", "PatientName": "SYNTHETIC^TEST",
                              "AccessionNumber": "%s", "Modality": "CR",
                              "StudyDescription": "Synthetic compatibility check", "StudyDate": "%s"}}
                            """
                                .formatted(accession, today)))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(created.statusCode()).as(created.body()).isEqualTo(200);

    DicomWebArchive archive =
        new DicomWebArchive(
            new ImagingArchiveProperties(
                URI.create(url + "/dicom-web"),
                user,
                password,
                null,
                url + "/stone-webviewer/index.html?study={StudyInstanceUID}",
                Duration.ofSeconds(10),
                100),
            JsonMapper.builder().build());
    assertThat(archive.status().reachable()).isTrue();
    List<ImagingArchive.Study> found = archive.studiesByAccession(accession);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).patientId()).isEqualTo("P-SYNTH-1");
    assertThat(found.get(0).modalities()).contains("CR");
    assertThat(found.get(0).instanceCount()).isEqualTo(1);
    assertThat(archive.studiesBetween(LocalDate.now(), LocalDate.now()))
        .extracting(ImagingArchive.Study::accessionNumber)
        .contains(accession);
    assertThat(archive.viewerUrl(found.get(0).studyInstanceUid())).isPresent();

    DicomWebArchive wrongPassword =
        new DicomWebArchive(
            new ImagingArchiveProperties(
                URI.create(url + "/dicom-web"), user, "wrong", null, null, null, null),
            JsonMapper.builder().build());
    assertThat(wrongPassword.status().reachable()).isFalse();
    assertThat(wrongPassword.status().message()).contains("refused");
  }
}
