package com.gcare.hms.encounter;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Triage configuration ({@code gcare.triage.*}). <strong>Empty by default on purpose</strong>: the
 * urgency scale and the alarm limits are clinical rules. Engineering must not invent them
 * (docs/07-CLAUDE.md); a clinical lead supplies them through configuration (docs/18 §4). Until then
 * no urgency can be recorded and no observation is flagged.
 */
@ConfigurationProperties("gcare.triage")
public record TriageProperties(List<UrgencyLevel> urgencyLevels, Map<String, Limit> alarmLimits) {

  /** One level of the urgency scale; lower rank is seen first. */
  public record UrgencyLevel(String code, String label, int rank) {}

  /** Flag when a value is below {@code low} or above {@code high}; either may be absent. */
  public record Limit(BigDecimal low, BigDecimal high) {}

  public TriageProperties {
    urgencyLevels = urgencyLevels == null ? List.of() : List.copyOf(urgencyLevels);
    alarmLimits = alarmLimits == null ? Map.of() : Map.copyOf(alarmLimits);
    Set<String> codes = new HashSet<>();
    for (UrgencyLevel level : urgencyLevels) {
      if (level.code() == null || level.code().isBlank() || !codes.add(level.code())) {
        throw new IllegalArgumentException("gcare.triage.urgency-levels: codes must be unique");
      }
    }
    for (String measure : alarmLimits.keySet()) {
      Measure.valueOf(measure); // fails start-up on an unknown measure name
    }
  }

  /** Measures that can have alarm limits. */
  public enum Measure {
    TEMPERATURE_C,
    PULSE_BPM,
    RESPIRATORY_RATE,
    SYSTOLIC_MMHG,
    DIASTOLIC_MMHG,
    SPO2_PCT
  }
}
