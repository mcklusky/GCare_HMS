package com.gcare.hms.patient;

import java.util.Optional;

/**
 * Patient numbers: {@code P} + 7-digit sequence + Luhn check digit, written {@code P1234567-3}
 * (docs/17-PATIENT-IDENTITY-POLICY.md §1, FR-005). The check digit catches every single-digit typo
 * and most swaps of neighbouring digits.
 */
public final class PatientNumber {

  public static final long FIRST = 1_000_001L;
  public static final long LAST = 9_999_999L;

  private PatientNumber() {}

  /** Formats a sequence value as a patient number. */
  public static String format(long sequence) {
    if (sequence < FIRST || sequence > LAST) {
      throw new IllegalArgumentException("Patient number sequence out of range: " + sequence);
    }
    String digits = Long.toString(sequence);
    return "P" + digits + "-" + checkDigit(digits);
  }

  /**
   * Accepts what a person might type ({@code P1234567-3}, {@code p 1234567 3}, {@code 12345673})
   * and returns the canonical form, or empty if it is not a valid patient number.
   */
  public static Optional<String> parse(String input) {
    if (input == null) {
      return Optional.empty();
    }
    String compact = input.replaceAll("[\\s-]", "").toUpperCase();
    if (compact.startsWith("P")) {
      compact = compact.substring(1);
    }
    if (!compact.matches("[0-9]{8}")) {
      return Optional.empty();
    }
    String digits = compact.substring(0, 7);
    int check = compact.charAt(7) - '0';
    if (digits.charAt(0) == '0' || checkDigit(digits) != check) {
      return Optional.empty();
    }
    return Optional.of("P" + digits + "-" + check);
  }

  /** Luhn (mod 10) check digit for a string of digits. */
  static int checkDigit(String digits) {
    int sum = 0;
    boolean doubleIt = true; // rightmost payload digit is doubled
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (doubleIt) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      doubleIt = !doubleIt;
    }
    return (10 - sum % 10) % 10;
  }
}
