package com.gcare.hms.encounter;

import com.gcare.hms.encounter.ClinicalApi.CreateNoteRequest;
import com.gcare.hms.encounter.ClinicalApi.DiagnosisRequest;
import com.gcare.hms.encounter.ClinicalApi.DiagnosisView;
import com.gcare.hms.encounter.ClinicalApi.ErrorRequest;
import com.gcare.hms.encounter.ClinicalApi.NoteView;
import com.gcare.hms.encounter.ClinicalApi.UpdateNoteRequest;
import com.gcare.hms.encounter.ClinicalStore.DiagnosisRow;
import com.gcare.hms.encounter.ClinicalStore.NoteRow;
import com.gcare.hms.encounter.EncounterApi.EncounterStatus;
import com.gcare.hms.encounter.EncounterApi.VersionRequest;
import com.gcare.hms.encounter.EncounterStore.EncounterRow;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Clinical notes and visit diagnoses (task 3.2; FR-008; docs/18 §7, PROVISIONAL). A note is a
 * private draft of its author until signed; once signed it never changes and is corrected only by a
 * signed amendment, which keeps its own author, time and reason. Diagnoses are free text (codes
 * later, OD-09) and are corrected by marking them entered in error.
 */
@Service
public class ClinicalService {

  private final ClinicalStore store;
  private final EncounterStore encounters;
  private final EncounterAudit audit;

  ClinicalService(ClinicalStore store, EncounterStore encounters, EncounterAudit audit) {
    this.store = store;
    this.encounters = encounters;
    this.audit = audit;
  }

  // --- notes -----------------------------------------------------------------------------------

  /** Signed notes of the visit plus the caller's own drafts, oldest first. */
  @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_NOTE_READ + "')")
  @Transactional(readOnly = true)
  public List<NoteView> notes(UUID encounterId) {
    CurrentUser user = CurrentUser.require();
    EncounterRow e = encounter(user, encounterId, Permissions.CLINICAL_NOTE_READ);
    List<NoteRow> rows = store.notes(encounterId, user.userId());
    Map<UUID, List<UUID>> amendedBy = new HashMap<>();
    rows.stream()
        .filter(n -> "SIGNED".equals(n.status()) && n.amendsNoteId() != null)
        .forEach(
            n -> amendedBy.computeIfAbsent(n.amendsNoteId(), k -> new ArrayList<>()).add(n.id()));
    List<NoteView> result =
        rows.stream().map(n -> view(n, amendedBy.getOrDefault(n.id(), List.of()))).toList();
    audit.afterCommit(user, e.facilityId(), "NOTES_VIEWED", encounterId, Map.of());
    return result;
  }

  /**
   * Starts a draft. A new note needs an open visit; an amendment needs a signed note of the same
   * visit and a reason, and may be written after the visit has closed.
   */
  @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_NOTE_WRITE + "')")
  @Transactional
  public NoteView create(UUID encounterId, CreateNoteRequest request) {
    CurrentUser user = CurrentUser.require();
    EncounterRow e = encounter(user, encounterId, Permissions.CLINICAL_NOTE_WRITE);
    String reason = trim(request.amendmentReason());
    if (request.amendsNoteId() != null) {
      NoteRow original =
          store
              .note(request.amendsNoteId())
              .filter(n -> n.encounterId().equals(encounterId))
              .orElseThrow(
                  () -> new ApiException.Invalid("amends_note_id", "is not a note of this visit"));
      if (!"SIGNED".equals(original.status())) {
        throw new ApiException.InvalidTransition("Only a signed note can be amended");
      }
      if (reason == null) {
        throw new ApiException.Invalid("amendment_reason", "is required for an amendment");
      }
    } else {
      if (reason != null) {
        throw new ApiException.Invalid("amendment_reason", "is only for amendments");
      }
      if (!e.status().open()) {
        throw new ApiException.InvalidTransition(
            "This visit is not open. Reopen it, or amend a signed note.");
      }
    }
    if (e.status() == EncounterStatus.CANCELLED) {
      throw new ApiException.InvalidTransition("This visit was cancelled");
    }
    UUID id = UUID.randomUUID();
    store.insertNote(
        id,
        user.organizationId(),
        encounterId,
        e.patient().id(),
        user.userId(),
        trim(request.history()),
        trim(request.examination()),
        trim(request.assessment()),
        trim(request.plan()),
        request.amendsNoteId(),
        reason);
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("note", id.toString());
    if (request.amendsNoteId() != null) {
      metadata.put("amends", request.amendsNoteId().toString());
    }
    audit.afterCommit(user, e.facilityId(), "NOTE_DRAFT_STARTED", encounterId, metadata);
    return view(store.note(id).orElseThrow(), List.of());
  }

  @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_NOTE_WRITE + "')")
  @Transactional
  public NoteView update(UUID noteId, UpdateNoteRequest request) {
    CurrentUser user = CurrentUser.require();
    NoteRow note = ownDraft(user, noteId);
    if (store.updateDraft(
            noteId,
            request.version(),
            trim(request.history()),
            trim(request.examination()),
            trim(request.assessment()),
            trim(request.plan()))
        == 0) {
      throw new ApiException.Conflict(
          "This draft was changed elsewhere (another tab?). Reload it.");
    }
    audit.afterCommit(
        user, null, "NOTE_DRAFT_SAVED", note.encounterId(), Map.of("note", noteId.toString()));
    return view(store.note(noteId).orElseThrow(), List.of());
  }

  /** Signs the caller's own draft. Afterwards it can never change. */
  @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_NOTE_WRITE + "')")
  @Transactional
  public NoteView sign(UUID noteId, VersionRequest request) {
    CurrentUser user = CurrentUser.require();
    NoteRow note = ownDraft(user, noteId);
    if (trim(note.history()) == null
        && trim(note.examination()) == null
        && trim(note.assessment()) == null
        && trim(note.plan()) == null) {
      throw new ApiException.Invalid("note", "An empty note cannot be signed");
    }
    if (store.sign(noteId, request.version()) == 0) {
      throw new ApiException.Conflict(
          "This draft was changed since you opened it. Reload before signing.");
    }
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("note", noteId.toString());
    if (note.amendsNoteId() != null) {
      metadata.put("amends", note.amendsNoteId().toString());
    }
    audit.afterCommit(
        user,
        null,
        note.amendsNoteId() == null ? "NOTE_SIGNED" : "NOTE_AMENDMENT_SIGNED",
        note.encounterId(),
        metadata);
    return view(store.note(noteId).orElseThrow(), List.of());
  }

  /** Discards the caller's own draft. It is kept (never deleted) but no longer shown. */
  @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_NOTE_WRITE + "')")
  @Transactional
  public void discard(UUID noteId, VersionRequest request) {
    CurrentUser user = CurrentUser.require();
    NoteRow note = ownDraft(user, noteId);
    if (store.discard(noteId, request.version()) == 0) {
      throw new ApiException.Conflict("This draft was changed since you opened it. Reload it.");
    }
    audit.afterCommit(
        user, null, "NOTE_DRAFT_DISCARDED", note.encounterId(), Map.of("note", noteId.toString()));
  }

  // --- diagnoses -------------------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_NOTE_READ + "')")
  @Transactional(readOnly = true)
  public List<DiagnosisView> diagnoses(UUID encounterId) {
    CurrentUser user = CurrentUser.require();
    EncounterRow e = encounter(user, encounterId, Permissions.CLINICAL_NOTE_READ);
    List<DiagnosisView> result =
        store.diagnoses(encounterId).stream().map(ClinicalService::view).toList();
    audit.afterCommit(user, e.facilityId(), "DIAGNOSES_VIEWED", encounterId, Map.of());
    return result;
  }

  @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_NOTE_WRITE + "')")
  @Transactional
  public DiagnosisView recordDiagnosis(UUID encounterId, DiagnosisRequest request) {
    CurrentUser user = CurrentUser.require();
    EncounterRow e = encounter(user, encounterId, Permissions.CLINICAL_NOTE_WRITE);
    if (!e.status().open()) {
      throw new ApiException.InvalidTransition("Diagnoses can only be added to an open visit");
    }
    UUID id = UUID.randomUUID();
    store.insertDiagnosis(
        id,
        user.organizationId(),
        encounterId,
        e.patient().id(),
        request.description().trim(),
        request.certainty(),
        user.userId());
    audit.afterCommit(
        user,
        e.facilityId(),
        "DIAGNOSIS_RECORDED",
        encounterId,
        Map.of("diagnosis", id.toString(), "certainty", request.certainty().name()));
    return view(store.diagnosis(id).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_NOTE_WRITE + "')")
  @Transactional
  public DiagnosisView markDiagnosisInError(UUID diagnosisId, ErrorRequest request) {
    CurrentUser user = CurrentUser.require();
    DiagnosisRow d =
        store.diagnosis(diagnosisId).orElseThrow(() -> new ApiException.NotFound("Diagnosis"));
    EncounterRow e = encounter(user, d.encounterId(), Permissions.CLINICAL_NOTE_WRITE);
    if (store.markDiagnosisInError(diagnosisId, request.reason().trim(), user.userId()) == 0) {
      throw new ApiException.InvalidTransition(
          "This diagnosis is already marked as entered in error");
    }
    audit.afterCommit(
        user,
        e.facilityId(),
        "DIAGNOSIS_ENTERED_IN_ERROR",
        e.id(),
        Map.of("diagnosis", diagnosisId.toString()));
    return view(store.diagnosis(diagnosisId).orElseThrow());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private EncounterRow encounter(CurrentUser user, UUID id, String permission) {
    EncounterRow e = encounters.find(id).orElseThrow(() -> new ApiException.NotFound("Encounter"));
    if (!user.scopeOf(permission).includes(e.facilityId())) {
      throw new ApiException.NotFound("Encounter");
    }
    return e;
  }

  /** The caller's own draft; other people's drafts answer 404 (drafts are private). */
  private NoteRow ownDraft(CurrentUser user, UUID noteId) {
    NoteRow note = store.note(noteId).orElseThrow(() -> new ApiException.NotFound("Note"));
    encounter(user, note.encounterId(), Permissions.CLINICAL_NOTE_WRITE);
    if (!note.author().id().equals(user.userId())) {
      throw new ApiException.NotFound("Note");
    }
    if (!"DRAFT".equals(note.status())) {
      throw new ApiException.InvalidTransition(
          "Only a draft can be changed. Signed notes are amended.");
    }
    return note;
  }

  private static String trim(String s) {
    return StringUtils.hasText(s) ? s.trim() : null;
  }

  private static NoteView view(NoteRow n, List<UUID> amendedBy) {
    return new NoteView(
        n.id(),
        n.encounterId(),
        n.status(),
        n.history(),
        n.examination(),
        n.assessment(),
        n.plan(),
        n.author(),
        n.createdAt(),
        n.updatedAt(),
        n.signedAt(),
        n.amendsNoteId(),
        n.amendmentReason(),
        amendedBy,
        n.version());
  }

  static DiagnosisView view(DiagnosisRow d) {
    return new DiagnosisView(
        d.id(),
        d.encounterId(),
        d.description(),
        d.certainty(),
        d.code(),
        d.codeSystem(),
        d.status(),
        d.recordedBy(),
        d.recordedAt(),
        d.errorReason(),
        d.errorBy(),
        d.errorAt());
  }
}
