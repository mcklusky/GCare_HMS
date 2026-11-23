package com.gcare.hms.patient;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Duplicate-matching configuration ({@code gcare.patient.matching.*}). Defaults are the PROVISIONAL
 * values in docs/17-PATIENT-IDENTITY-POLICY.md §2–3; change them through configuration once
 * registration leadership approves values. Keep the policy document in step.
 */
@ConfigurationProperties("gcare.patient.matching")
public record MatchingProperties(
    @DefaultValue("9") int phoneSignificantDigits,
    @DefaultValue("80") int likelyThreshold,
    @DefaultValue("50") int possibleThreshold,
    @DefaultValue("0.6") double similarNameThreshold,
    @DefaultValue Weights weights) {

  public record Weights(
      @DefaultValue("100") int identifierMatch,
      @DefaultValue("30") int phoneMatch,
      @DefaultValue("20") int familyNameEqual,
      @DefaultValue("10") int familyNameSimilar,
      @DefaultValue("15") int givenNameEqual,
      @DefaultValue("8") int givenNameSimilar,
      @DefaultValue("25") int birthDateEqual,
      @DefaultValue("10") int birthYearClose,
      @DefaultValue("5") int sexEqual,
      @DefaultValue("-20") int sexDifferent,
      @DefaultValue("-50") int identifierConflict) {}

  /** The provisional defaults, for tests and tools. */
  public static MatchingProperties defaults() {
    return new MatchingProperties(
        9, 80, 50, 0.6, new Weights(100, 30, 20, 10, 15, 8, 25, 10, 5, -20, -50));
  }
}
