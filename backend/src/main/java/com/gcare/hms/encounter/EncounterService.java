package com.gcare.hms.encounter;

import com.gcare.hms.encounter.EncounterApi.DepartmentRef;
import com.gcare.hms.encounter.EncounterApi.EncounterDetail;
import com.gcare.hms.encounter.EncounterApi.EncounterEvent;
import com.gcare.hms.encounter.EncounterApi.EncounterStatus;
import com.gcare.hms.encounter.EncounterApi.EncounterSummary;
import com.gcare.hms.encounter.EncounterApi.OpenEncounterRequest;
import com.gcare.hms.encounter.EncounterApi.Page;
import com.gcare.hms.encounter.EncounterApi.ReasonRequest;
import com.gcare.hms.encounter.EncounterApi.TransferRequest;
import com.gcare.hms.encounter.EncounterApi.VersionRequest;
import com.gcare.hms.encounter.EncounterStore.EncounterRow;
import com.gcare.hms.patient.PatientStatus;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.FacilityScope;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Encounters and department queues (task 3.1; FR-006). Workflow: docs/18-ENCOUNTER-WORKFLOW.md,
 * PROVISIONAL. Every action is checked against the user's facility scope; an encounter outside it
 * answers 404 so its existence is not disclosed. Every status change is written to the encounter's
 * event history and audited.
 */
@Service
public class EncounterService {

  public static final int MAX_PAGE_SIZE = 100;
  private static final Set<EncounterStatus> OPEN =
      EnumSet.of(EncounterStatus.WAITING, EncounterStatus.IN_CONSULTATION);

  private final EncounterStore store;
  private final EncounterAudit audit;
  private final TriageProperties triage;
  private final JsonMapper json;

  EncounterService(
      EncounterStore store, EncounterAudit audit, TriageProperties triage, JsonMapper json) {
    this.store = store;
    this.audit = audit;
    this.triage = triage;
    this.json = json;
  }

  /**
   * Departments where visits can be opened or queues viewed, for users without FACILITY_READ
   * (clerks, nurses). Needs ENCOUNTER_READ or ENCOUNTER_OPEN at the facility.
   */
  @PreAuthorize(
      "hasAnyAuthority('" + Permissions.ENCOUNTER_READ + "', '" + Permissions.ENCOUNTER_OPEN + "')")
  @Transactional(readOnly = true)
  public List<DepartmentRef> departments(UUID facilityId) {
    CurrentUser user = CurrentUser.require();
    if (!user.scopeOf(Permissions.ENCOUNTER_READ).includes(facilityId)
        && !user.scopeOf(Permissions.ENCOUNTER_OPEN).includes(facilityId)) {
      throw new ApiException.NotFound("Facility");
    }
    return store.activeDepartments(facilityId);
  }

  /** Opens a visit and puts the patient in the department's queue (status WAITING). */
  @PreAuthorize("hasAuthority('" + Permissions.ENCOUNTER_OPEN + "')")
  @Transactional
  public EncounterDetail open(OpenEncounterRequest request) {
    CurrentUser user = CurrentUser.require();
    if (!user.scopeOf(Permissions.ENCOUNTER_OPEN).includes(request.facilityId())) {
      throw new ApiException.NotFound("Facility");
    }
    store
        .activeDepartment(request.facilityId(), request.departmentId())
        .orElseThrow(() ->
            new ApiException.Invalid(
                "department_id", "is not an active department of this facility"));
    PatientStatus patient =
        store
            .patientStatus(request.patientId())
            .orElseThrow(() -> new ApiException.NotFound("Patient"));
    if (patient != PatientStatus.ACTIVE) {
      throw new ApiException.InvalidTransition(
          "This patient record is linked to another record. Open the visit on the record in use.");
    }
    UUID id = UUID.randomUUID();
    try {
      store.insert(
          id,
          user.organizationId(),
          request.facilityId(),
          request.departmentId(),
          request.patientId(),
          request.encounterType(),
          user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict("This patient already has an open visit at this facility");
    }
    store.insertEvent(
        user.organizationId(),
        id,
        "OPENED",
        null,
        EncounterStatus.WAITING,
        request.departmentId(),
        null,
        user.userId());
    audit.afterCommit(
        user,
        request.facilityId(),
        "ENCOUNTER_OPENED",
        id,
        Map.of("patient", request.patientId().toString()));
    return detail(user, id);
  }

  /**
   * Encounters in the user's ENCOUNTER_READ scope. Default: open encounters (the queue). For open
   * encounters, those with a configured urgency are listed first (lower rank first), then by
   * arrival; there is no urgency ordering until a clinical lead configures the scale.
   */
  @PreAuthorize("hasAuthority('" + Permissions.ENCOUNTER_READ + "')")
  @Transactional(readOnly = true)
  public Page<EncounterSummary> list(
      UUID facilityId,
      UUID departmentId,
      UUID patientId,
      List<EncounterStatus> statuses,
      int page,
      int size) {
    CurrentUser user = CurrentUser.require();
    if (page < 0) {
      throw new ApiException.Invalid("page", "must be 0 or more");
    }
    int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    FacilityScope scope = user.scopeOf(Permissions.ENCOUNTER_READ);
    Set<UUID> facilities = scope.facilityIds();
    boolean all = scope.allFacilities();
    if (facilityId != null) {
      if (!scope.includes(facilityId)) {
        throw new ApiException.NotFound("Facility");
      }
      facilities = Set.of(facilityId);
      all = false;
    }
    Set<EncounterStatus> wanted =
        statuses == null || statuses.isEmpty() ? OPEN : EnumSet.copyOf(statuses);
    List<EncounterRow> rows =
        store.search(facilities, all, departmentId, patientId, wanted, page, pageSize);
    long total = store.count(facilities, all, departmentId, patientId, wanted);
    boolean clinical = user.hasPermission(Permissions.OBSERVATION_READ);
    List<EncounterSummary> items = rows.stream().map(r -> summary(r, clinical)).toList();
    if (OPEN.containsAll(wanted)) {
      Map<String, Integer> rank = new HashMap<>();
      triage.urgencyLevels().forEach(l -> rank.put(l.code(), l.rank()));
      items =
          items.stream()
              .sorted(
                  Comparator.comparing((EncounterSummary s) ->
                          s.urgency() == null
                              ? Integer.MAX_VALUE
                              : rank.getOrDefault(s.urgency(), Integer.MAX_VALUE))
                      .thenComparing(EncounterSummary::arrivedAt))
              .toList();
    }
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("results", Long.toString(total));
    if (patientId != null) {
      metadata.put("patient", patientId.toString());
    }
    audit.afterCommit(user, facilityId, "ENCOUNTERS_LISTED", null, metadata);
    return new Page<>(items, page, pageSize, total);
  }

  @PreAuthorize("hasAuthority('" + Permissions.ENCOUNTER_READ + "')")
  @Transactional(readOnly = true)
  public EncounterDetail get(UUID id) {
    CurrentUser user = CurrentUser.require();
    EncounterDetail detail = detail(user, id);
    audit.afterCommit(user, detail.encounter().facilityId(), "ENCOUNTER_VIEWED", id, Map.of());
    return detail;
  }

  /** A clinician calls the patient in: IN_CONSULTATION, with the caller as clinician. */
  @PreAuthorize("hasAuthority('" + Permissions.ENCOUNTER_MANAGE + "')")
  @Transactional
  public EncounterDetail call(UUID id, VersionRequest request) {
    CurrentUser user = CurrentUser.require();
    EncounterRow e = locked(user, id, Permissions.ENCOUNTER_MANAGE);
    if (e.status() != EncounterStatus.WAITING) {
      throw new ApiException.InvalidTransition("Only a waiting patient can be called");
    }
    move(
        user,
        e,
        request.version(),
        "CALLED",
        EncounterStatus.IN_CONSULTATION,
        e.department().id(),
        user.userId(),
        null);
    return detail(user, id);
  }

  /**
   * Sends the patient to another department of the same facility, back into its queue (WAITING, no
   * clinician). Needs a reason.
   */
  @PreAuthorize("hasAuthority('" + Permissions.ENCOUNTER_MANAGE + "')")
  @Transactional
  public EncounterDetail transfer(UUID id, TransferRequest request) {
    CurrentUser user = CurrentUser.require();
    EncounterRow e = locked(user, id, Permissions.ENCOUNTER_MANAGE);
    if (!e.status().open()) {
      throw new ApiException.InvalidTransition("Only an open visit can be transferred");
    }
    if (e.department().id().equals(request.departmentId())) {
      throw new ApiException.Invalid("department_id", "is already this visit's department");
    }
    store
        .activeDepartment(e.facilityId(), request.departmentId())
        .orElseThrow(() ->
            new ApiException.Invalid(
                "department_id", "is not an active department of this facility"));
    move(
        user,
        e,
        request.version(),
        "TRANSFERRED",
        EncounterStatus.WAITING,
        request.departmentId(),
        null,
        request.reason().trim());
    return detail(user, id);
  }

  @PreAuthorize("hasAuthority('" + Permissions.ENCOUNTER_MANAGE + "')")
  @Transactional
  public EncounterDetail close(UUID id, VersionRequest request) {
    CurrentUser user = CurrentUser.require();
    EncounterRow e = locked(user, id, Permissions.ENCOUNTER_MANAGE);
    if (e.status() != EncounterStatus.IN_CONSULTATION) {
      throw new ApiException.InvalidTransition("Only a visit in consultation can be closed");
    }
    move(
        user,
        e,
        request.version(),
        "CLOSED",
        EncounterStatus.CLOSED,
        e.department().id(),
        e.clinician().id(),
        null);
    return detail(user, id);
  }

  /** Before the patient is seen: left without being seen, or opened in error. Needs a reason. */
  @PreAuthorize("hasAuthority('" + Permissions.ENCOUNTER_OPEN + "')")
  @Transactional
  public EncounterDetail cancel(UUID id, ReasonRequest request) {
    CurrentUser user = CurrentUser.require();
    EncounterRow e = locked(user, id, Permissions.ENCOUNTER_OPEN);
    if (e.status() != EncounterStatus.WAITING) {
      throw new ApiException.InvalidTransition(
          "Only a visit that is still waiting can be cancelled");
    }
    move(
        user,
        e,
        request.version(),
        "CANCELLED",
        EncounterStatus.CANCELLED,
        e.department().id(),
        null,
        request.reason().trim());
    return detail(user, id);
  }

  /** Reopens a closed visit with a reason; the reopener becomes the clinician. */
  @PreAuthorize("hasAuthority('" + Permissions.ENCOUNTER_REOPEN + "')")
  @Transactional
  public EncounterDetail reopen(UUID id, ReasonRequest request) {
    CurrentUser user = CurrentUser.require();
    EncounterRow e = locked(user, id, Permissions.ENCOUNTER_REOPEN);
    if (e.status() != EncounterStatus.CLOSED) {
      throw new ApiException.InvalidTransition("Only a closed visit can be reopened");
    }
    try {
      move(
          user,
          e,
          request.version(),
          "REOPENED",
          EncounterStatus.IN_CONSULTATION,
          e.department().id(),
          user.userId(),
          request.reason().trim());
    } catch (DuplicateKeyException ex) {
      throw new ApiException.Conflict(
          "This patient has another open visit at this facility. Close it first.");
    }
    return detail(user, id);
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** Locks and returns the encounter if it is in the user's scope for {@code permission}. */
  private EncounterRow locked(CurrentUser user, UUID id, String permission) {
    EncounterRow e = store.lock(id).orElseThrow(() -> new ApiException.NotFound("Encounter"));
    if (!user.scopeOf(permission).includes(e.facilityId())) {
      throw new ApiException.NotFound("Encounter");
    }
    return e;
  }

  private void move(
      CurrentUser user,
      EncounterRow e,
      long version,
      String event,
      EncounterStatus to,
      UUID departmentId,
      UUID clinicianId,
      String reason) {
    if (store.transition(e.id(), version, to, departmentId, clinicianId) == 0) {
      throw new ApiException.Conflict(
          "Someone else changed this visit since you opened it. Reload and try again.");
    }
    store.insertEvent(
        user.organizationId(), e.id(), event, e.status(), to, departmentId, reason, user.userId());
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("from", e.status().name());
    metadata.put("to", to.name());
    if (!departmentId.equals(e.department().id())) {
      metadata.put("department", departmentId.toString());
    }
    audit.afterCommit(user, e.facilityId(), "ENCOUNTER_" + event, e.id(), metadata);
  }

  private EncounterDetail detail(CurrentUser user, UUID id) {
    EncounterRow e = store.find(id).orElseThrow(() -> new ApiException.NotFound("Encounter"));
    if (!user.scopeOf(Permissions.ENCOUNTER_READ).includes(e.facilityId())
        && !user.scopeOf(Permissions.ENCOUNTER_OPEN).includes(e.facilityId())) {
      throw new ApiException.NotFound("Encounter");
    }
    List<EncounterEvent> events =
        store.events(id).stream()
            .map(v ->
                new EncounterEvent(
                    v.id(),
                    v.eventType(),
                    v.fromStatus(),
                    v.toStatus(),
                    v.department(),
                    v.reason(),
                    v.actor(),
                    v.occurredAt()))
            .toList();
    return new EncounterDetail(
        summary(e, user.hasPermission(Permissions.OBSERVATION_READ)), events);
  }

  EncounterSummary summary(EncounterRow r, boolean clinical) {
    List<String> flags = List.of();
    if (clinical && r.flagsJson() != null) {
      List<EncounterApi.Flag> parsed =
          json.readValue(r.flagsJson(), new TypeReference<List<EncounterApi.Flag>>() {});
      flags = parsed.stream().map(f -> f.measure() + "_" + f.direction()).toList();
    }
    DepartmentRef department = r.department();
    return new EncounterSummary(
        r.id(),
        r.facilityId(),
        department,
        r.patient(),
        r.type(),
        r.status(),
        r.clinician(),
        r.arrivedAt(),
        r.consultationStartedAt(),
        r.endedAt(),
        clinical ? r.triaged() : null,
        clinical ? r.urgency() : null,
        clinical ? flags : null,
        r.version());
  }
}
