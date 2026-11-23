package com.gcare.hms.radiology;

import com.gcare.hms.radiology.ImagingWorkApi.DecisionRequest;
import com.gcare.hms.radiology.ImagingWorkApi.ProposeLinkRequest;
import com.gcare.hms.radiology.ImagingWorkApi.StepRequest;
import com.gcare.hms.radiology.ImagingWorkApi.StudyEvent;
import com.gcare.hms.radiology.ImagingWorkApi.StudyView;
import com.gcare.hms.radiology.ImagingWorkApi.WorklistItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Imaging worklist, radiographer steps and study linking (task 6.3; docs/25). */
@RestController
@RequestMapping("/api/v1/imaging")
@Tag(name = "Imaging")
class ImagingController {

  private final ImagingService service;

  ImagingController(ImagingService service) {
    this.service = service;
  }

  @GetMapping("/worklist")
  @Operation(
      summary =
          "Imaging orders at facilities in your scope, with accession numbers and linked studies")
  List<WorklistItem> worklist(
      @RequestParam(name = "include_finished", defaultValue = "false") boolean includeFinished) {
    return service.worklist(includeFinished);
  }

  @PostMapping("/orders/{id}/arrived")
  @Operation(summary = "Record that the patient arrived (starts the order)")
  WorklistItem arrived(@PathVariable UUID id, @Valid @RequestBody StepRequest request) {
    return service.arrived(id, request);
  }

  @PostMapping("/orders/{id}/examined")
  @Operation(summary = "Record that the examination is done (completes the order)")
  WorklistItem examined(@PathVariable UUID id, @Valid @RequestBody StepRequest request) {
    return service.examined(id, request);
  }

  @PostMapping("/orders/{id}/check-archive")
  @Operation(summary = "Ask the archive now for studies with this order's accession number")
  WorklistItem checkArchive(@PathVariable UUID id) {
    return service.checkArchive(id);
  }

  @GetMapping("/studies/unlinked")
  @Operation(summary = "Studies that did not link automatically (the mismatch queue)")
  List<StudyView> queue() {
    return service.queue();
  }

  @GetMapping("/studies/{id}/history")
  @Operation(summary = "Link history of a study")
  List<StudyEvent> history(@PathVariable UUID id) {
    return service.history(id);
  }

  @PostMapping("/studies/{id}/propose")
  @Operation(
      summary = "Propose linking a study to an imaging order",
      description = "Another person approves.")
  StudyView propose(@PathVariable UUID id, @Valid @RequestBody ProposeLinkRequest request) {
    return service.propose(id, request);
  }

  @PostMapping("/studies/{id}/approve")
  @Operation(summary = "Approve another person's proposed link")
  StudyView approve(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.approve(id, request);
  }

  @PostMapping("/studies/{id}/send-back")
  @Operation(summary = "Send a proposed link back to the queue")
  StudyView sendBack(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.sendBack(id, request);
  }

  @PostMapping("/studies/{id}/unlink")
  @Operation(summary = "Undo a link, with a reason")
  StudyView unlink(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.unlink(id, request);
  }
}
