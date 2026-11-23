package com.gcare.hms.patient;

import com.gcare.hms.patient.PatientApi.DuplicateCandidate;
import java.util.List;

/** Registration stopped because these candidates must be reviewed first (409). */
class DuplicateCandidatesException extends RuntimeException {

  private final transient List<DuplicateCandidate> candidates;

  DuplicateCandidatesException(List<DuplicateCandidate> candidates) {
    super("Possible duplicate patients must be reviewed before registering");
    this.candidates = List.copyOf(candidates);
  }

  List<DuplicateCandidate> candidates() {
    return candidates;
  }
}
