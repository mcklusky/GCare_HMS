package com.gcare.hms.patient;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Scores how likely two patient records describe the same person (docs/17-PATIENT-IDENTITY-POLICY
 * .md §3). It only ranks candidates for a person to review; nothing is ever matched automatically.
 */
public final class DuplicateScorer {

  /** The comparable facts of one record, already normalized. */
  public record Facts(
      String familyNorm,
      String givenNorm,
      LocalDate birthDate,
      boolean birthDateEstimated,
      Sex sex,
      String phoneNorm,
      List<IdentifierFact> identifiers) {

    public Facts {
      identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
      sex = sex == null ? Sex.UNKNOWN : sex;
    }
  }

  public record IdentifierFact(IdentifierType type, String valueNorm, boolean verified) {}

  /** What contributed to a score, for showing staff why a record was suggested. */
  public enum Evidence {
    IDENTIFIER,
    IDENTIFIER_CONFLICT,
    PHONE,
    FAMILY_NAME,
    FAMILY_NAME_SIMILAR,
    GIVEN_NAME,
    GIVEN_NAME_SIMILAR,
    NAMES_SWAPPED,
    BIRTH_DATE,
    BIRTH_YEAR,
    SEX,
    SEX_DIFFERENT
  }

  public enum Band {
    LIKELY,
    POSSIBLE,
    NONE
  }

  public record Result(int score, Band band, List<Evidence> evidence) {}

  private final MatchingProperties config;

  public DuplicateScorer(MatchingProperties config) {
    this.config = Objects.requireNonNull(config);
  }

  public Result score(Facts a, Facts b) {
    MatchingProperties.Weights w = config.weights();
    List<Evidence> evidence = new ArrayList<>();
    int score = 0;

    boolean identifierMatch =
        a.identifiers().stream()
            .anyMatch(x ->
                b.identifiers().stream()
                    .anyMatch(y -> x.type() == y.type() && x.valueNorm().equals(y.valueNorm())));
    if (identifierMatch) {
      score += w.identifierMatch();
      evidence.add(Evidence.IDENTIFIER);
    } else if (a.identifiers().stream()
        .anyMatch(x ->
            x.verified()
                && b.identifiers().stream()
                    .anyMatch(y ->
                        y.verified()
                            && x.type() == y.type()
                            && !x.valueNorm().equals(y.valueNorm())))) {
      score += w.identifierConflict();
      evidence.add(Evidence.IDENTIFIER_CONFLICT);
    }

    if (a.phoneNorm() != null && a.phoneNorm().equals(b.phoneNorm())) {
      score += w.phoneMatch();
      evidence.add(Evidence.PHONE);
    }

    NamePoints straight = names(a.familyNorm(), b.familyNorm(), a.givenNorm(), b.givenNorm());
    // Swapped: try both weightings so that score(a, b) == score(b, a).
    NamePoints swapped1 = names(a.familyNorm(), b.givenNorm(), a.givenNorm(), b.familyNorm());
    NamePoints swapped2 = names(a.givenNorm(), b.familyNorm(), a.familyNorm(), b.givenNorm());
    NamePoints swapped = swapped2.points() > swapped1.points() ? swapped2 : swapped1;
    NamePoints chosen = swapped.points() > straight.points() ? swapped : straight;
    score += chosen.points();
    evidence.addAll(chosen.evidence());
    if (chosen == swapped && swapped.points() > 0) {
      evidence.add(Evidence.NAMES_SWAPPED);
    }

    if (a.birthDate() != null && b.birthDate() != null) {
      if (a.birthDate().equals(b.birthDate())) {
        score += w.birthDateEqual();
        evidence.add(Evidence.BIRTH_DATE);
      } else if (((a.birthDateEstimated() || b.birthDateEstimated())
              && a.birthDate().getYear() == b.birthDate().getYear())
          || Math.abs(ChronoUnit.DAYS.between(a.birthDate(), b.birthDate())) <= 1) {
        score += w.birthYearClose();
        evidence.add(Evidence.BIRTH_YEAR);
      }
    }

    if (a.sex() != Sex.UNKNOWN && b.sex() != Sex.UNKNOWN) {
      if (a.sex() == b.sex()) {
        score += w.sexEqual();
        evidence.add(Evidence.SEX);
      } else {
        score += w.sexDifferent();
        evidence.add(Evidence.SEX_DIFFERENT);
      }
    }

    // Without an identifier or given-name match, never "likely" (twins, relatives).
    if (!identifierMatch && !chosen.givenMatched()) {
      score = Math.min(score, config.likelyThreshold() - 1);
    }

    Band band =
        score >= config.likelyThreshold()
            ? Band.LIKELY
            : score >= config.possibleThreshold() ? Band.POSSIBLE : Band.NONE;
    return new Result(score, band, List.copyOf(evidence));
  }

  private record NamePoints(int points, boolean givenMatched, List<Evidence> evidence) {}

  private NamePoints names(String familyA, String familyB, String givenA, String givenB) {
    MatchingProperties.Weights w = config.weights();
    int points = 0;
    boolean givenMatched = false;
    List<Evidence> evidence = new ArrayList<>();
    if (familyA != null && familyB != null) {
      if (familyA.equals(familyB)) {
        points += w.familyNameEqual();
        evidence.add(Evidence.FAMILY_NAME);
      } else if (Trigrams.similarity(familyA, familyB) >= config.similarNameThreshold()) {
        points += w.familyNameSimilar();
        evidence.add(Evidence.FAMILY_NAME_SIMILAR);
      }
    }
    if (givenA != null && givenB != null) {
      if (givenA.equals(givenB)) {
        points += w.givenNameEqual();
        givenMatched = true;
        evidence.add(Evidence.GIVEN_NAME);
      } else if (Trigrams.similarity(givenA, givenB) >= config.similarNameThreshold()) {
        points += w.givenNameSimilar();
        givenMatched = true;
        evidence.add(Evidence.GIVEN_NAME_SIMILAR);
      }
    }
    return new NamePoints(points, givenMatched, evidence);
  }
}
