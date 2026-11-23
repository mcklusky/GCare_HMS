package com.gcare.hms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataRedactorTests {

  @Test
  void redactsSensitiveKeysCaseInsensitively() {
    Map<String, String> out =
        MetadataRedactor.redact(
            Map.of(
                "Authorization", "Bearer abc",
                "patient_phone", "0000000000",
                "reportText", "findings",
                "reason", "correction"));

    assertThat(out)
        .containsEntry("Authorization", MetadataRedactor.REDACTED)
        .containsEntry("patient_phone", MetadataRedactor.REDACTED)
        .containsEntry("reportText", MetadataRedactor.REDACTED)
        .containsEntry("reason", "correction");
  }

  @Test
  void capsValueLength() {
    String longValue = "x".repeat(500);
    String stored = MetadataRedactor.redact(Map.of("reason", longValue)).get("reason");

    assertThat(stored).hasSize(MetadataRedactor.MAX_VALUE_LENGTH + 1).endsWith("…");
  }

  @Test
  void rejectsOversizedMetadata() {
    Map<String, String> big = new HashMap<>();
    for (int i = 0; i <= MetadataRedactor.MAX_ENTRIES; i++) {
      big.put("k" + i, "v");
    }
    assertThatThrownBy(() -> MetadataRedactor.redact(big))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
