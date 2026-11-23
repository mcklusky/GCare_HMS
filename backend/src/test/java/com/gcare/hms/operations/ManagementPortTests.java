package com.gcare.hms.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.gcare.hms.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Health and metrics (task 5.4; docs/23): the application port answers only status-only probes;
 * health details and metrics are served on the separate management port only.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMetrics // tests turn metrics export off by default
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagementPortTests {

  @LocalServerPort private int appPort;
  @LocalManagementPort private int managementPort;

  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(int port, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void theApplicationPortOnlyAnswersStatusProbes() throws Exception {
    assertThat(managementPort).isNotEqualTo(appPort);
    for (String probe : new String[] {"/actuator/health", "/livez", "/readyz"}) {
      HttpResponse<String> r = get(appPort, probe);
      assertThat(r.statusCode()).as(probe).isEqualTo(200);
      assertThat(r.body()).as(probe).contains("\"UP\"").doesNotContain("components");
    }
    for (String hidden :
        new String[] {
          "/actuator/prometheus", "/actuator/health/db", "/actuator/info", "/actuator/env"
        }) {
      assertThat(get(appPort, hidden).statusCode()).as(hidden).isIn(401, 403, 404);
    }
  }

  @Test
  void theManagementPortServesDetailsAndMetricsButNothingElse() throws Exception {
    HttpResponse<String> health = get(managementPort, "/actuator/health");
    assertThat(health.statusCode())
        .as("health on " + managementPort + ": " + health.body())
        .isEqualTo(200);
    assertThat(health.body()).contains("\"db\"");
    HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
    assertThat(metrics.statusCode()).as("metrics: " + metrics.body()).isEqualTo(200);
    assertThat(metrics.body()).contains("jvm_memory_used_bytes", "hikaricp_connections");
    assertThat(get(managementPort, "/actuator/env").statusCode()).isIn(401, 403, 404);
    assertThat(get(managementPort, "/api/v1/me").statusCode()).isIn(401, 403, 404);
  }
}
