package com.gcare.hms.encounter;

import com.gcare.hms.encounter.EncounterApi.EncounterDetail;
import com.gcare.hms.encounter.EncounterApi.EncounterStatus;
import com.gcare.hms.encounter.EncounterApi.EncounterSummary;
import com.gcare.hms.encounter.EncounterApi.ErrorRequest;
import com.gcare.hms.encounter.EncounterApi.ObservationRequest;
import com.gcare.hms.encounter.EncounterApi.ObservationView;
import com.gcare.hms.encounter.EncounterApi.OpenEncounterRequest;
import com.gcare.hms.encounter.EncounterApi.Page;
import com.gcare.hms.encounter.EncounterApi.ReasonRequest;
import com.gcare.hms.encounter.EncounterApi.TransferRequest;
import com.gcare.hms.encounter.EncounterApi.TriageSettings;
import com.gcare.hms.encounter.EncounterApi.VersionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Encounters, queues and triage (docs/18-ENCOUNTER-WORKFLOW.md, PROVISIONAL). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Encounters")
class EncounterController {

  private final EncounterService encounters;
  private final TriageService triage;

  EncounterController(EncounterService encounters, TriageService triage) {
    this.encounters = encounters;
    this.triage = triage;
  }

  @PostMapping("/encounters")
  @Operation(
      summary = "Open a visit",
      description = "Needs ENCOUNTER_OPEN at the facility. The patient joins the department queue.")
  ResponseEntity<EncounterDetail> open(@Valid @RequestBody OpenEncounterRequest request) {
    EncounterDetail created = encounters.open(request);
    return ResponseEntity.created(URI.create("/api/v1/encounters/" + created.encounter().id()))
        .body(created);
  }

  @GetMapping("/encounters")
  @Operation(
      summary = "List encounters / department queue",
      description =
          "Needs ENCOUNTER_READ. Default status: WAITING and IN_CONSULTATION. Triage data only with OBSERVATION_READ.")
  Page<EncounterSummary> list(
      @RequestParam(name = "facility_id", required = false) UUID facilityId,
      @RequestParam(name = "department_id", required = false) UUID departmentId,
      @RequestParam(name = "patient_id", required = false) UUID patientId,
      @RequestParam(required = false) List<EncounterStatus> status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return encounters.list(facilityId, departmentId, patientId, status, page, size);
  }

  @GetMapping("/encounter-departments")
  @Operation(
      summary = "Departments for visits",
      description = "Active departments of a facility in the user's encounter scope.")
  List<EncounterApi.DepartmentRef> departments(
      @RequestParam(name = "facility_id") UUID facilityId) {
    return encounters.departments(facilityId);
  }

  @GetMapping("/encounters/{id}")
  @Operation(summary = "One visit with its history")
  EncounterDetail get(@PathVariable UUID id) {
    return encounters.get(id);
  }

  @PostMapping("/encounters/{id}/call")
  @Operation(
      summary = "Call the patient in",
      description = "Needs ENCOUNTER_MANAGE. WAITING -> IN_CONSULTATION.")
  EncounterDetail call(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
    return encounters.call(id, request);
  }

  @PostMapping("/encounters/{id}/transfer")
  @Operation(
      summary = "Transfer to another department",
      description = "Needs ENCOUNTER_MANAGE and a reason.")
  EncounterDetail transfer(@PathVariable UUID id, @Valid @RequestBody TransferRequest request) {
    return encounters.transfer(id, request);
  }

  @PostMapping("/encounters/{id}/close")
  @Operation(
      summary = "Close the visit",
      description = "Needs ENCOUNTER_MANAGE. IN_CONSULTATION -> CLOSED.")
  EncounterDetail close(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
    return encounters.close(id, request);
  }

  @PostMapping("/encounters/{id}/cancel")
  @Operation(
      summary = "Cancel before being seen",
      description = "Needs ENCOUNTER_OPEN and a reason. WAITING only.")
  EncounterDetail cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
    return encounters.cancel(id, request);
  }

  @PostMapping("/encounters/{id}/reopen")
  @Operation(
      summary = "Reopen a closed visit",
      description = "Needs ENCOUNTER_REOPEN and a reason.")
  EncounterDetail reopen(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
    return encounters.reopen(id, request);
  }

  // --- triage ----------------------------------------------------------------------------------

  @GetMapping("/triage/settings")
  @Operation(
      summary = "Configured urgency scale",
      description = "Empty until a clinical lead configures it.")
  TriageSettings settings() {
    return triage.settings();
  }

  @PostMapping("/encounters/{id}/observations")
  @Operation(
      summary = "Record triage observations",
      description = "Needs OBSERVATION_RECORD. Fixed units.")
  ResponseEntity<ObservationView> record(
      @PathVariable UUID id, @Valid @RequestBody ObservationRequest request) {
    ObservationView created = triage.record(id, request);
    return ResponseEntity.created(URI.create("/api/v1/observations/" + created.id())).body(created);
  }

  @GetMapping("/encounters/{id}/observations")
  @Operation(
      summary = "Observations of a visit",
      description = "Needs OBSERVATION_READ. Newest first.")
  List<ObservationView> observations(@PathVariable UUID id) {
    return triage.list(id);
  }

  @PostMapping("/observations/{id}/entered-in-error")
  @Operation(
      summary = "Mark observations entered in error",
      description = "Needs OBSERVATION_RECORD and a reason. Values are kept.")
  ObservationView enteredInError(@PathVariable UUID id, @Valid @RequestBody ErrorRequest request) {
    return triage.markEnteredInError(id, request);
  }
}
