package com.gcare.hms.encounter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.encounter.ClinicalApi.DiagnosisView;
import com.gcare.hms.encounter.ClinicalStore.DiagnosisRow;
import com.gcare.hms.encounter.ClinicalStore.DraftRow;
import com.gcare.hms.encounter.EncounterApi.EncounterStatus;
import com.gcare.hms.encounter.EncounterApi.EncounterSummary;
import com.gcare.hms.encounter.EncounterApi.ObservationView;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import com.gcare.hms.encounter.EncounterStore.EncounterRow;
import com.gcare.hms.encounter.EncounterStore.ObservationRow;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.FacilityScope;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patient chart and clinician workspace (task 3.4; FR-006–FR-008, docs/02 screen inventory). Read
 * only: brings together visits, diagnoses and triage that the user may already see one by one. Each
 * section is filled only with the matching permission and only for facilities in scope; {@code
 * null} means "not permitted", an empty list means "nothing recorded".
 */
@Service
public class ChartService {

  private static final int VISIT_LIMIT = 100;
  private static final int DIAGNOSIS_LIMIT = 50;
  private static final int OBSERVATION_LOOKBACK = 20;
  private static final int ALLERGY_NOTES = 5;

  public record AllergyNote(
      String note,
      @JsonProperty("encounter_id") UUID encounterId,
      @JsonProperty("recorded_by") UserRef recordedBy,
      @JsonProperty("recorded_at") Instant recordedAt) {}

  public record ChartView(
      @JsonProperty("patient_id") UUID patientId,
      List<EncounterSummary> visits,
      List<DiagnosisView> diagnoses,
      @JsonProperty("latest_observation") ObservationView latestObservation,
      @JsonProperty("reported_allergies") List<AllergyNote> reportedAllergies) {}

  public record DraftRef(
      @JsonProperty("note_id") UUID noteId,
      @JsonProperty("encounter_id") UUID encounterId,
      PatientSummary patient,
      boolean amendment,
      @JsonProperty("updated_at") Instant updatedAt) {}

  public record WorkspaceView(
      @JsonProperty("in_consultation") List<EncounterSummary> inConsultation,
      List<DraftRef> drafts) {}

  private final EncounterStore encounters;
  private final ClinicalStore clinical;
  private final EncounterService encounterService;
  private final TriageService triageService;
  private final EncounterAudit audit;

  ChartService(
      EncounterStore encounters,
      ClinicalStore clinical,
      EncounterService encounterService,
      TriageService triageService,
      EncounterAudit audit) {
    this.encounters = encounters;
    this.clinical = clinical;
    this.encounterService = encounterService;
    this.triageService = triageService;
    this.audit = audit;
  }

  /** The patient's visits (newest first) and, with permission, diagnoses and recent triage. */
  @PreAuthorize("hasAuthority('" + Permissions.ENCOUNTER_READ + "')")
  @Transactional(readOnly = true)
  public ChartView chart(UUID patientId) {
    CurrentUser user = CurrentUser.require();
    encounters.patientStatus(patientId).orElseThrow(() -> new ApiException.NotFound("Patient"));
    boolean clinicalView = user.hasPermission(Permissions.OBSERVATION_READ);

    FacilityScope visitScope = user.scopeOf(Permissions.ENCOUNTER_READ);
    List<EncounterRow> rows =
        new ArrayList<>(
            encounters.search(
                visitScope.facilityIds(),
                visitScope.allFacilities(),
                null,
                patientId,
                EnumSet.allOf(EncounterStatus.class),
                0,
                VISIT_LIMIT));
    rows.sort((a, b) -> b.arrivedAt().compareTo(a.arrivedAt()));
    List<EncounterSummary> visits =
        rows.stream().map(r -> encounterService.summary(r, clinicalView)).toList();

    List<DiagnosisView> diagnoses = null;
    if (user.hasPermission(Permissions.CLINICAL_NOTE_READ)) {
      FacilityScope scope = user.scopeOf(Permissions.CLINICAL_NOTE_READ);
      List<DiagnosisRow> found = clinical.diagnosesOfPatient(patientId, DIAGNOSIS_LIMIT);
      Map<UUID, UUID> facility =
          encounters.facilitiesOf(found.stream().map(DiagnosisRow::encounterId).toList());
      diagnoses =
          found.stream()
              .filter(d -> scope.includes(facility.get(d.encounterId())))
              .map(ClinicalService::view)
              .toList();
    }

    ObservationView latest = null;
    List<AllergyNote> allergies = null;
    if (clinicalView) {
      FacilityScope scope = user.scopeOf(Permissions.OBSERVATION_READ);
      List<ObservationRow> found =
          encounters.recentObservationsOfPatient(patientId, OBSERVATION_LOOKBACK);
      Map<UUID, UUID> facility =
          encounters.facilitiesOf(found.stream().map(ObservationRow::encounterId).toList());
      List<ObservationRow> visible =
          found.stream().filter(o -> scope.includes(facility.get(o.encounterId()))).toList();
      latest = visible.isEmpty() ? null : triageService.view(visible.getFirst());
      allergies =
          visible.stream()
              .filter(o -> o.allergyNote() != null)
              .limit(ALLERGY_NOTES)
              .map(o ->
                  new AllergyNote(o.allergyNote(), o.encounterId(), o.recordedBy(), o.recordedAt()))
              .toList();
    }

    audit.afterCommit(user, null, "PATIENT_CHART_VIEWED", "Patient", patientId, Map.of());
    return new ChartView(patientId, visits, diagnoses, latest, allergies);
  }

  /** What the signed-in clinician is working on: patients with them now and unsigned drafts. */
  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.ENCOUNTER_MANAGE
          + "', '"
          + Permissions.CLINICAL_NOTE_WRITE
          + "')")
  @Transactional(readOnly = true)
  public WorkspaceView workspace() {
    CurrentUser user = CurrentUser.require();
    boolean clinicalView = user.hasPermission(Permissions.OBSERVATION_READ);
    FacilityScope visitScope = user.scopeOf(Permissions.ENCOUNTER_READ);
    List<EncounterSummary> withMe =
        encounters.inConsultationWith(user.userId()).stream()
            .filter(r -> visitScope.includes(r.facilityId()))
            .map(r -> encounterService.summary(r, clinicalView))
            .toList();

    FacilityScope noteScope = user.scopeOf(Permissions.CLINICAL_NOTE_WRITE);
    List<DraftRef> drafts = new ArrayList<>();
    for (DraftRow d : clinical.draftsOf(user.userId())) {
      if (!noteScope.includes(d.facilityId())) {
        continue;
      }
      encounters
          .find(d.encounterId())
          .ifPresent(e ->
              drafts.add(
                  new DraftRef(
                      d.noteId(), d.encounterId(), e.patient(), d.amendment(), d.updatedAt())));
    }
    audit.afterCommit(user, null, "WORKSPACE_VIEWED", null, Map.of());
    return new WorkspaceView(withMe, drafts);
  }
}
