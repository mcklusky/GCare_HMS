package com.gcare.hms.encounter;

import com.gcare.hms.encounter.ChartService.ChartView;
import com.gcare.hms.encounter.ChartService.WorkspaceView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Patient chart and clinician workspace (task 3.4). Read only. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Chart")
class ChartController {

  private final ChartService service;

  ChartController(ChartService service) {
    this.service = service;
  }

  @GetMapping("/patients/{id}/chart")
  @Operation(
      summary = "Patient chart",
      description =
          "Needs ENCOUNTER_READ. Visits in scope; diagnoses with CLINICAL_NOTE_READ; latest"
              + " triage and reported allergies with OBSERVATION_READ (null = not permitted).")
  ChartView chart(@PathVariable UUID id) {
    return service.chart(id);
  }

  @GetMapping("/workspace")
  @Operation(
      summary = "My workspace",
      description = "Patients in consultation with you and your unsigned note drafts.")
  WorkspaceView workspace() {
    return service.workspace();
  }
}
