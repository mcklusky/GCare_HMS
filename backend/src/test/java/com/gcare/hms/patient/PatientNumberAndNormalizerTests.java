package com.gcare.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PatientNumberAndNormalizerTests {

  @Test
  void formatsWithLuhnCheckDigit() {
    // Luhn of 1000001: doubled digits (from the right) 1 and 1 give 2+2 = 4, so check digit 6
    assertThat(PatientNumber.format(1_000_001)).isEqualTo("P1000001-6");
    assertThat(PatientNumber.parse(PatientNumber.format(4_567_890))).isPresent();
  }

  @Test
  void parsesWhatPeopleType() {
    String number = PatientNumber.format(1_234_567);
    String digits = number.replaceAll("[^0-9]", "");

    assertThat(PatientNumber.parse(digits)).contains(number);
    assertThat(PatientNumber.parse("p " + digits.substring(0, 7) + " " + digits.charAt(7)))
        .contains(number);
    assertThat(PatientNumber.parse(number.toLowerCase())).contains(number);
  }

  @Test
  void rejectsEverySingleDigitTypo() {
    String digits = PatientNumber.format(1_234_567).replaceAll("[^0-9]", "");
    for (int pos = 0; pos < digits.length(); pos++) {
      for (char c = '0'; c <= '9'; c++) {
        if (c == digits.charAt(pos)) {
          continue;
        }
        String typo = digits.substring(0, pos) + c + digits.substring(pos + 1);
        assertThat(PatientNumber.parse(typo)).as(typo).isEmpty();
      }
    }
  }

  @Test
  void rejectsMostAdjacentSwaps() {
    String digits = PatientNumber.format(1_234_567).replaceAll("[^0-9]", "");
    int caught = 0;
    int swaps = 0;
    for (int i = 0; i + 1 < digits.length(); i++) {
      if (digits.charAt(i) == digits.charAt(i + 1)) {
        continue;
      }
      swaps++;
      String swapped =
          digits.substring(0, i)
              + digits.charAt(i + 1)
              + digits.charAt(i)
              + digits.substring(i + 2);
      if (PatientNumber.parse(swapped).isEmpty()) {
        caught++;
      }
    }
    assertThat(caught).isEqualTo(swaps); // Luhn misses only 0<->9 swaps, absent here
  }

  @Test
  void rejectsOutOfRangeAndGarbage() {
    assertThatThrownBy(() -> PatientNumber.format(999_999))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(PatientNumber.parse("hello")).isEmpty();
    assertThat(PatientNumber.parse(null)).isEmpty();
    assertThat(PatientNumber.parse("P0000001-0")).isEmpty();
  }

  @Test
  void normalizesNames() {
    assertThat(PatientNormalizer.name("  Ọlá-Mary  O'Neil. ")).isEqualTo("ola mary oneil");
    assertThat(PatientNormalizer.name("Mary-Jane")).isEqualTo(PatientNormalizer.name("mary jane"));
    assertThat(PatientNormalizer.name(" - ")).isNull();
  }

  @Test
  void normalizesPhonesAndIdentifiers() {
    assertThat(PatientNormalizer.phone("+231 77-012-3456", 9)).isEqualTo("770123456");
    assertThat(PatientNormalizer.phone("0770123456", 9)).isEqualTo("770123456");
    assertThat(PatientNormalizer.phone("12345", 9)).isNull();
    assertThat(PatientNormalizer.identifier(" lr-123.456/7 ")).isEqualTo("LR1234567");
  }

  @Test
  void trigramSimilarityMatchesPgTrgmBehaviour() {
    assertThat(Trigrams.similarity("mensah", "mensah")).isEqualTo(1.0);
    assertThat(Trigrams.similarity("kofi mensah", "mensah kofi")).isEqualTo(1.0);
    assertThat(Trigrams.similarity("mensah", "mensa")).isGreaterThan(0.6);
    assertThat(Trigrams.similarity("kemah", "musu")).isLessThan(0.2);
  }
}
