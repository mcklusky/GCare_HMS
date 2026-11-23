package com.gcare.hms.downtime;

import com.gcare.hms.downtime.DowntimeApi.CandidatesView;
import com.gcare.hms.downtime.DowntimeApi.CloseRequest;
import com.gcare.hms.downtime.DowntimeApi.DecisionRequest;
import com.gcare.hms.downtime.DowntimeApi.DeclareRequest;
import com.gcare.hms.downtime.DowntimeApi.DowntimeSource;
import com.gcare.hms.downtime.DowntimeApi.EndRequest;
import com.gcare.hms.downtime.DowntimeApi.EntryRequest;
import com.gcare.hms.downtime.DowntimeApi.EntryView;
import com.gcare.hms.downtime.DowntimeApi.EpisodeView;
import com.gcare.hms.downtime.DowntimeApi.FormBatchView;
import com.gcare.hms.downtime.DowntimeApi.FormOptions;
import com.gcare.hms.downtime.DowntimeApi.IssueFormsRequest;
import com.gcare.hms.downtime.DowntimeApi.ProposeRequest;
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

/** Downtime episodes, numbered paper forms and reconciliation (task 5.3; docs/22). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Downtime")
class DowntimeController {

  private final DowntimeService service;

  DowntimeController(DowntimeService service) {
    this.service = service;
  }

  @GetMapping("/downtime/episodes")
  @Operation(summary = "Downtime episodes of facilities in your scope")
  List<EpisodeView> episodes() {
    return service.episodes();
  }

  @PostMapping("/downtime/episodes")
  @Operation(
      summary = "Declare a downtime episode",
      description = "Needs DOWNTIME_MANAGE. May be declared after recovery with past times.")
  ResponseEntity<EpisodeView> declare(@Valid @RequestBody DeclareRequest request) {
    EpisodeView e = service.declare(request);
    return ResponseEntity.created(URI.create("/api/v1/downtime/episodes/" + e.id())).body(e);
  }

  @GetMapping("/downtime/episodes/{id}")
  @Operation(summary = "One downtime episode")
  EpisodeView episode(@PathVariable UUID id) {
    return service.episode(id);
  }

  @PostMapping("/downtime/episodes/{id}/end")
  @Operation(summary = "Record when the downtime ended")
  EpisodeView end(@PathVariable UUID id, @Valid @RequestBody EndRequest request) {
    return service.end(id, request);
  }

  @PostMapping("/downtime/episodes/{id}/close")
  @Operation(
      summary = "Close an ended episode",
      description = "Only when every keyed form is reconciled or voided.")
  EpisodeView close(@PathVariable UUID id, @Valid @RequestBody CloseRequest request) {
    return service.close(id, request);
  }

  @PostMapping("/downtime/forms")
  @Operation(summary = "Issue a block of numbered paper forms to print")
  ResponseEntity<FormBatchView> issue(@Valid @RequestBody IssueFormsRequest request) {
    FormBatchView b = service.issueForms(request);
    return ResponseEntity.created(URI.create("/api/v1/downtime/forms/" + b.id())).body(b);
  }

  @GetMapping("/downtime/forms")
  @Operation(summary = "Issued form blocks of a facility")
  List<FormBatchView> batches(@RequestParam("facility_id") UUID facilityId) {
    return service.batches(facilityId);
  }

  @GetMapping("/downtime/forms/{id}")
  @Operation(summary = "One issued form block (to reprint)")
  FormBatchView batch(@PathVariable UUID id) {
    return service.batch(id);
  }

  @GetMapping("/downtime/form-options")
  @Operation(summary = "Departments, order items and payment methods for keying forms")
  FormOptions options(@RequestParam("facility_id") UUID facilityId) {
    return service.formOptions(facilityId);
  }

  @GetMapping("/downtime/episodes/{id}/entries")
  @Operation(summary = "Keyed paper forms of an episode (the reconciliation queue)")
  List<EntryView> entries(@PathVariable UUID id) {
    return service.entries(id);
  }

  @PostMapping("/downtime/episodes/{id}/entries")
  @Operation(
      summary = "Key a paper form",
      description =
          "201 when keyed; 200 with the existing entry when exactly the same keying is"
              + " repeated; 409 when the form number was keyed differently.")
  ResponseEntity<EntryView> record(
      @PathVariable UUID id, @Valid @RequestBody EntryRequest request) {
    DowntimeService.Recorded r = service.record(id, request);
    return r.created()
        ? ResponseEntity.created(URI.create("/api/v1/downtime/entries/" + r.entry().id()))
            .body(r.entry())
        : ResponseEntity.ok(r.entry());
  }

  @GetMapping("/downtime/entries/{id}")
  @Operation(summary = "One keyed form with its history")
  EntryView entry(@PathVariable UUID id) {
    return service.entry(id);
  }

  @GetMapping("/downtime/entries/{id}/candidates")
  @Operation(summary = "Possible existing patients for a registration form (PATIENT_READ)")
  CandidatesView candidates(@PathVariable UUID id) {
    return service.candidates(id);
  }

  @PostMapping("/downtime/entries/{id}/propose")
  @Operation(
      summary = "Propose how to reconcile a registration, order or payment form",
      description = "A different person approves it.")
  EntryView propose(@PathVariable UUID id, @Valid @RequestBody ProposeRequest request) {
    return service.propose(id, request);
  }

  @PostMapping("/downtime/entries/{id}/approve")
  @Operation(
      summary = "Approve another person's proposal",
      description = "Creates the records through the normal services, so their permissions apply.")
  EntryView approve(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.approve(id, request);
  }

  @PostMapping("/downtime/entries/{id}/send-back")
  @Operation(summary = "Send a proposal back to the queue")
  EntryView sendBack(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.sendBack(id, request);
  }

  @PostMapping("/downtime/entries/{id}/reconcile")
  @Operation(summary = "Reconcile a triage form (one person; OBSERVATION_RECORD)")
  EntryView reconcile(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.reconcile(id, request);
  }

  @PostMapping("/downtime/entries/{id}/void")
  @Operation(summary = "Void a triage form keyed wrongly (one person)")
  EntryView voidEntry(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.voidEntry(id, request);
  }

  @GetMapping("/encounters/{id}/downtime-sources")
  @Operation(summary = "Paper downtime forms reconciled into this visit, with paper times")
  List<DowntimeSource> sources(@PathVariable UUID id) {
    return service.sources(id);
  }
}
