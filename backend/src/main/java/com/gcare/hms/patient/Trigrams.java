package com.gcare.hms.patient;

import java.util.HashSet;
import java.util.Set;

/**
 * Trigram similarity computed the way PostgreSQL pg_trgm does (each word padded with two leading
 * spaces and one trailing space; similarity = shared / total distinct trigrams). The database uses
 * pg_trgm to find candidates; scoring uses this so results are the same and unit-testable.
 */
final class Trigrams {

  private Trigrams() {}

  static double similarity(String a, String b) {
    if (a == null || b == null) {
      return 0;
    }
    Set<String> ta = trigrams(a);
    Set<String> tb = trigrams(b);
    if (ta.isEmpty() || tb.isEmpty()) {
      return 0;
    }
    Set<String> shared = new HashSet<>(ta);
    shared.retainAll(tb);
    return (double) shared.size() / (ta.size() + tb.size() - shared.size());
  }

  static Set<String> trigrams(String value) {
    Set<String> result = new HashSet<>();
    for (String word : value.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
      if (word.isEmpty()) {
        continue;
      }
      String padded = "  " + word + " ";
      for (int i = 0; i + 3 <= padded.length(); i++) {
        result.add(padded.substring(i, i + 3));
      }
    }
    return result;
  }
}
