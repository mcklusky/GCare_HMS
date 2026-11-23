package com.gcare.hms.audit;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Redacts audit metadata before storage (docs/07-CLAUDE.md: never log clinical content or secrets).
 * Keys that look sensitive have their value replaced; every value is length-capped so free text
 * cannot be smuggled in.
 */
final class MetadataRedactor {

  static final String REDACTED = "[REDACTED]";
  static final int MAX_VALUE_LENGTH = 200;
  static final int MAX_ENTRIES = 50;

  /** Matched as substrings of the lower-cased key. */
  private static final Set<String> SENSITIVE_KEY_PARTS =
      Set.of(
          "password",
          "secret",
          "token",
          "authorization",
          "cookie",
          "credential",
          "apikey",
          "api_key",
          "content",
          "note",
          "text",
          "body",
          "diagnosis",
          "result",
          "report",
          "name",
          "phone",
          "address",
          "email",
          "dob",
          "birth",
          "national");

  private MetadataRedactor() {}

  static Map<String, String> redact(Map<String, String> metadata) {
    if (metadata.size() > MAX_ENTRIES) {
      throw new IllegalArgumentException("Audit metadata has too many entries");
    }
    Map<String, String> safe = new TreeMap<>();
    metadata.forEach((key, value) -> {
      if (key == null) {
        return;
      }
      safe.put(key, isSensitive(key) ? REDACTED : truncate(value));
    });
    return safe;
  }

  private static boolean isSensitive(String key) {
    String k = key.toLowerCase(Locale.ROOT);
    return SENSITIVE_KEY_PARTS.stream().anyMatch(k::contains);
  }

  private static String truncate(String value) {
    if (value == null || value.length() <= MAX_VALUE_LENGTH) {
      return value;
    }
    return value.substring(0, MAX_VALUE_LENGTH) + "…";
  }
}
