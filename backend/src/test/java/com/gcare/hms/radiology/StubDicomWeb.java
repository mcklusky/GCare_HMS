package com.gcare.hms.radiology;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** A tiny DICOMweb (QIDO-RS) stand-in for tests: answers every query with a fixed body. */
final class StubDicomWeb implements AutoCloseable {

  /** Two synthetic studies in the shape Orthanc 1.12 returns (one without a usable UID). */
  static final String TWO_STUDIES =
      """
      [{"00080020":{"vr":"DA","Value":["20261123"]},
        "00080050":{"vr":"SH","Value":["ACC-T-1"]},
        "00080061":{"vr":"CS","Value":["CR","DX"]},
        "00081030":{"vr":"LO","Value":["Synthetic chest"]},
        "00100010":{"vr":"PN","Value":[{"Alphabetic":"SYNTHETIC^TEST"}]},
        "00100020":{"vr":"LO","Value":["P-000001"]},
        "0020000D":{"vr":"UI","Value":["1.2.826.0.1.3680043.2.1125.1"]},
        "00201206":{"vr":"IS","Value":[1]},
        "00201208":{"vr":"IS","Value":["3"]}},
       {"00080050":{"vr":"SH","Value":["ACC-T-1"]},
        "0020000D":{"vr":"UI","Value":["not-a-uid\\"><script>"]}}]
      """;

  final AtomicReference<String> lastQuery = new AtomicReference<>();
  final AtomicReference<String> lastAuth = new AtomicReference<>();
  volatile int status = 200;
  volatile String body = TWO_STUDIES;
  private final HttpServer server;

  StubDicomWeb() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/dicom-web/studies",
        ex -> {
          lastQuery.set(ex.getRequestURI().getRawQuery());
          lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
          byte[] b = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/dicom+json");
          if (status == 204) {
            ex.sendResponseHeaders(204, -1);
          } else {
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
          }
          ex.close();
        });
    server.start();
  }

  URI baseUrl() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/dicom-web/");
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
