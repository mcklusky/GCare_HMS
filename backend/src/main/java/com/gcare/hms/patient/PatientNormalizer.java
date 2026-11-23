package com.gcare.hms.patient;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalized forms used for search and duplicate detection (docs/17-PATIENT-IDENTITY-POLICY.md §2).
 * Display values are stored unchanged; only these derived values are compared.
 */
public final class PatientNormalizer {

  private PatientNormalizer() {}

  /**
   * Accents removed, lower case, apostrophes and full stops removed, hyphens become spaces,
   * whitespace collapsed. {@code null} when nothing is left.
   */
  public static String name(String value) {
    if (value == null) {
      return null;
    }
    String s = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
    s = s.toLowerCase(Locale.ROOT);
    s = s.replaceAll("['’.`]", "");
    s = s.replaceAll("[-‐‑–—_]", " ");
    s = s.replaceAll("[^\\p{L}\\p{N} ]", " ");
    s = s.trim().replaceAll("\\s+", " ");
    return s.isEmpty() ? null : s;
  }

  /**
   * Digits only, keeping the last {@code significantDigits} (drops country code and trunk 0).
   * {@code null} if fewer than 7 digits remain, which cannot identify a phone.
   */
  public static String phone(String value, int significantDigits) {
    if (value == null) {
      return null;
    }
    String digits = value.replaceAll("[^0-9]", "");
    if (digits.length() < 7) {
      return null;
    }
    return digits.length() > significantDigits
        ? digits.substring(digits.length() - significantDigits)
        : digits;
  }

  /** Upper case with spaces, hyphens, full stops and slashes removed. */
  public static String identifier(String value) {
    if (value == null) {
      return null;
    }
    String s = value.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-./]", "");
    return s.isEmpty() ? null : s;
  }
}
