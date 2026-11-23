package com.gcare.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;

import com.gcare.hms.patient.DuplicateScorer.Band;
import com.gcare.hms.patient.DuplicateScorer.Evidence;
import com.gcare.hms.patient.DuplicateScorer.Facts;
import com.gcare.hms.patient.DuplicateScorer.IdentifierFact;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The acceptance scenarios of docs/17-PATIENT-IDENTITY-POLICY.md §4 that are pure scoring
 * (scenarios 1–7). Scenarios 8–10 are API behaviour (PatientApiTests). All names are fictional.
 *
 * <p>If a scenario here changes, the policy document must change with it, and registration
 * leadership must re-approve it.
 */
class DuplicateScoringScenarioTests {

  private final DuplicateScorer scorer = new DuplicateScorer(MatchingProperties.defaults());

  private static final LocalDate DOB = LocalDate.of(1990, 5, 14);

  private static Facts person(
      String family, String given, LocalDate dob, Sex sex, String phone, IdentifierFact... ids) {
    return new Facts(
        PatientNormalizer.name(family),
        PatientNormalizer.name(given),
        dob,
        false,
        sex,
        PatientNormalizer.phone(phone, 9),
        List.of(ids));
  }

  private static IdentifierFact nationalId(String value, boolean verified) {
    return new IdentifierFact(
        IdentifierType.NATIONAL_ID, PatientNormalizer.identifier(value), verified);
  }

  @Test
  void scenario1_sameNationalIdDifferentSpellingIsLikely() {
    var a = person("Mensah", "Abena", DOB, Sex.FEMALE, null, nationalId("LR-123-456", true));
    var b = person("Mensa", "Abenah", null, Sex.FEMALE, null, nationalId("lr 123456", false));

    var result = scorer.score(a, b);

    assertThat(result.band()).isEqualTo(Band.LIKELY);
    assertThat(result.evidence()).contains(Evidence.IDENTIFIER);
  }

  @Test
  void scenario2_sameNameDobAndPhoneIsLikely() {
    var a = person("Doe", "Testa", DOB, Sex.FEMALE, "+231 77 012 3456");
    var b = person("DOE", "Testa", DOB, Sex.FEMALE, "0770123456");

    assertThat(scorer.score(a, b).band()).isEqualTo(Band.LIKELY);
  }

  @Test
  void scenario3_sameNameAndDobDifferentPhoneIsPossible() {
    var a = person("Doe", "Testa", DOB, Sex.FEMALE, "0770123456");
    var b = person("Doe", "Testa", DOB, Sex.FEMALE, "0880999999");

    assertThat(scorer.score(a, b).band()).isEqualTo(Band.POSSIBLE);
  }

  @Test
  void scenario4_swappedNamesWithSameDobAreSuggested() {
    var a = person("Kollie", "Varney", DOB, Sex.MALE, null);
    var b = person("Varney", "Kollie", DOB, Sex.MALE, null);

    var result = scorer.score(a, b);

    assertThat(result.band()).isIn(Band.POSSIBLE, Band.LIKELY);
    assertThat(result.evidence()).contains(Evidence.NAMES_SWAPPED);
  }

  @Test
  void scenario5_parentAndChildWithSameNameAreNeverLikely() {
    var parent = person("Doe", "Sam", LocalDate.of(1965, 2, 1), Sex.MALE, "0770123456");
    var child = person("Doe", "Sam", LocalDate.of(2001, 9, 9), Sex.FEMALE, "0770123456");

    assertThat(scorer.score(parent, child).band()).isNotEqualTo(Band.LIKELY);
  }

  @Test
  void scenario6_twinsAreNeverLikelyWithoutIdentifierEvidence() {
    var twinA = person("Toe", "Kemah", DOB, Sex.FEMALE, "0770123456");
    var twinB = person("Toe", "Musu", DOB, Sex.FEMALE, "0770123456");

    var result = scorer.score(twinA, twinB);

    assertThat(result.band()).isEqualTo(Band.POSSIBLE);
    assertThat(result.score()).isLessThan(80);
  }

  @Test
  void scenario7_differentVerifiedIdentifiersOfSameTypeAreNotLikely() {
    var a = person("Doe", "Testa", DOB, Sex.FEMALE, "0770123456", nationalId("LR111", true));
    var b = person("Doe", "Testa", DOB, Sex.FEMALE, "0770123456", nationalId("LR222", true));

    var result = scorer.score(a, b);

    assertThat(result.band()).isNotEqualTo(Band.LIKELY);
    assertThat(result.evidence()).contains(Evidence.IDENTIFIER_CONFLICT);
  }

  @Test
  void unknownPatientMatchesNothing() {
    var unknown = new Facts(null, null, null, false, Sex.UNKNOWN, null, List.of());
    var other = person("Doe", "Testa", DOB, Sex.FEMALE, "0770123456");

    assertThat(scorer.score(unknown, other).band()).isEqualTo(Band.NONE);
  }

  @Test
  void estimatedBirthDateMatchesOnYear() {
    var a = new Facts("doe", "testa", LocalDate.of(1990, 1, 1), true, Sex.FEMALE, null, List.of());
    var b = person("Doe", "Testa", DOB, Sex.FEMALE, null);

    assertThat(scorer.score(a, b).evidence()).contains(Evidence.BIRTH_YEAR);
  }

  @Test
  void scoringIsSymmetric() {
    var a = person("Kollie", "Varney", DOB, Sex.MALE, "0770123456", nationalId("LR1", true));
    var b = person("Varney", "Kolie", DOB, Sex.MALE, "0770123457");

    assertThat(scorer.score(a, b).score()).isEqualTo(scorer.score(b, a).score());
  }
}
