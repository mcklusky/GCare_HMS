package com.gcare.hms.encounter;

import com.gcare.hms.encounter.ClinicalApi.CreateNoteRequest;
import com.gcare.hms.encounter.ClinicalApi.DiagnosisRequest;
import com.gcare.hms.encounter.ClinicalApi.DiagnosisView;
import com.gcare.hms.encounter.ClinicalApi.ErrorRequest;
import com.gcare.hms.encounter.ClinicalApi.NoteView;
import com.gcare.hms.encounter.ClinicalApi.UpdateNoteRequest;
import com.gcare.hms.encounter.EncounterApi.VersionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Clinical notes and visit diagnoses (docs/18 §7). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Clinical notes")
class ClinicalController {

  private final ClinicalService service;

  ClinicalController(ClinicalService service) {
    this.service = service;
  }

  @GetMapping("/encounters/{id}/notes")
  @Operation(
      summary = "Notes of a visit",
      description = "Needs CLINICAL_NOTE_READ. Signed notes plus your own drafts.")
  List<NoteView> notes(@PathVariable UUID id) {
    return service.notes(id);
  }

  @PostMapping("/encounters/{id}/notes")
  @Operation(
      summary = "Start a note or an amendment",
      description =
          "Needs CLINICAL_NOTE_WRITE. A new note needs an open visit; an amendment needs amends_note_id and amendment_reason.")
  ResponseEntity<NoteView> create(
      @PathVariable UUID id, @Valid @RequestBody CreateNoteRequest request) {
    NoteView created = service.create(id, request);
    return ResponseEntity.created(URI.create("/api/v1/notes/" + created.id())).body(created);
  }

  @PatchMapping("/notes/{id}")
  @Operation(summary = "Save your draft", description = "Replaces the sections. Send version.")
  NoteView update(@PathVariable UUID id, @Valid @RequestBody UpdateNoteRequest request) {
    return service.update(id, request);
  }

  @PostMapping("/notes/{id}/sign")
  @Operation(
      summary = "Sign your draft",
      description = "After signing the note never changes; correct it with an amendment.")
  NoteView sign(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
    return service.sign(id, request);
  }

  @PostMapping("/notes/{id}/discard")
  @Operation(summary = "Discard your draft", description = "Kept for the record, no longer shown.")
  ResponseEntity<Void> discard(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
    service.discard(id, request);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/encounters/{id}/diagnoses")
  @Operation(summary = "Diagnoses of a visit", description = "Needs CLINICAL_NOTE_READ.")
  List<DiagnosisView> diagnoses(@PathVariable UUID id) {
    return service.diagnoses(id);
  }

  @PostMapping("/encounters/{id}/diagnoses")
  @Operation(
      summary = "Record a diagnosis",
      description = "Free text; codes come later (OD-09). Open visits only.")
  ResponseEntity<DiagnosisView> recordDiagnosis(
      @PathVariable UUID id, @Valid @RequestBody DiagnosisRequest request) {
    DiagnosisView created = service.recordDiagnosis(id, request);
    return ResponseEntity.created(URI.create("/api/v1/diagnoses/" + created.id())).body(created);
  }

  @PostMapping("/diagnoses/{id}/entered-in-error")
  @Operation(
      summary = "Mark a diagnosis entered in error",
      description = "Needs a reason. The entry stays visible.")
  DiagnosisView diagnosisInError(@PathVariable UUID id, @Valid @RequestBody ErrorRequest request) {
    return service.markDiagnosisInError(id, request);
  }
}
