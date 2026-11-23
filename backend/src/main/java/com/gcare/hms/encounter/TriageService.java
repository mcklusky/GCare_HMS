package com.gcare.hms.encounter;

import com.gcare.hms.encounter.EncounterApi.ErrorRequest;
import com.gcare.hms.encounter.EncounterApi.Flag;
import com.gcare.hms.encounter.EncounterApi.ObservationRequest;
import com.gcare.hms.encounter.EncounterApi.ObservationView;
import com.gcare.hms.encounter.EncounterApi.TriageSettings;
import com.gcare.hms.encounter.EncounterStore.EncounterRow;
import com.gcare.hms.encounter.EncounterStore.ObservationRow;
import com.gcare.hms.encounter.TriageProperties.Limit;
import com.gcare.hms.encounter.TriageProperties.Measure;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Triage observations (task 3.1; FR-007). Values are recorded with fixed units, time and author and
 * never changed; a wrong entry is marked "entered in error" with a reason. Urgency and alarm flags
 * use only the configured scale and limits ({@link TriageProperties}), which are empty until a
 * clinical lead supplies them.
 */
@Service
public class TriageService {

  private final EncounterStore store;
  private final EncounterAudit audit;
  private final TriageProperties triage;
  private final JsonMapper json;

  TriageService(
      EncounterStore store, EncounterAudit audit, TriageProperties triage, JsonMapper json) {
    this.store = store;
    this.audit = audit;
    this.triage = triage;
    this.json = json;
  }

  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.OBSERVATION_READ
          + "', '"
          + Permissions.OBSERVATION_RECORD
          + "')")
  public TriageSettings settings() {
    return new TriageSettings(triage.urgencyLevels(), !triage.alarmLimits().isEmpty());
  }

  @PreAuthorize("hasAuthority('" + Permissions.OBSERVATION_RECORD + "')")
  @Transactional
  public ObservationView record(UUID encounterId, ObservationRequest request) {
    CurrentUser user = CurrentUser.require();
    EncounterRow e = encounter(user, encounterId, Permissions.OBSERVATION_RECORD);
    if (!e.status().open()) {
      throw new ApiException.InvalidTransition("Observations can only be added to an open visit");
    }
    String urgency = StringUtils.hasText(request.urgency()) ? request.urgency().trim() : null;
    if (urgency != null
        && triage.urgencyLevels().stream().noneMatch(l -> l.code().equals(urgency))) {
      throw new ApiException.Invalid(
          "urgency",
          triage.urgencyLevels().isEmpty()
              ? "No urgency scale is configured yet; leave urgency empty"
              : "must be one of the configured urgency levels");
    }
    ObservationRequest clean = withTrimmedText(request);
    List<String> recorded = recordedFields(clean, urgency);
    if (recorded.isEmpty()) {
      throw new ApiException.Invalid("observation", "Record at least one value");
    }
    UUID id = UUID.randomUUID();
    store.insertObservation(
        id,
        user.organizationId(),
        encounterId,
        e.patient().id(),
        clean,
        urgency,
        json.writeValueAsString(flags(clean)),
        user.userId());
    audit.afterCommit(
        user,
        e.facilityId(),
        "OBSERVATIONS_RECORDED",
        encounterId,
        Map.of("fields", String.join(",", recorded)));
    return view(store.observation(id).orElseThrow());
  }

  /** All observation sets of the encounter, newest first, including those entered in error. */
  @PreAuthorize("hasAuthority('" + Permissions.OBSERVATION_READ + "')")
  @Transactional(readOnly = true)
  public List<ObservationView> list(UUID encounterId) {
    CurrentUser user = CurrentUser.require();
    EncounterRow e = encounter(user, encounterId, Permissions.OBSERVATION_READ);
    List<ObservationView> result =
        store.observations(encounterId).stream().map(this::view).toList();
    audit.afterCommit(user, e.facilityId(), "OBSERVATIONS_VIEWED", encounterId, Map.of());
    return result;
  }

  @PreAuthorize("hasAuthority('" + Permissions.OBSERVATION_RECORD + "')")
  @Transactional
  public ObservationView markEnteredInError(UUID observationId, ErrorRequest request) {
    CurrentUser user = CurrentUser.require();
    ObservationRow o =
        store
            .observation(observationId)
            .orElseThrow(() -> new ApiException.NotFound("Observation"));
    EncounterRow e = encounter(user, o.encounterId(), Permissions.OBSERVATION_RECORD);
    if (store.markEnteredInError(observationId, request.reason().trim(), user.userId()) == 0) {
      throw new ApiException.InvalidTransition(
          "This observation is already marked as entered in error");
    }
    audit.afterCommit(
        user,
        e.facilityId(),
        "OBSERVATION_ENTERED_IN_ERROR",
        e.id(),
        Map.of("observation", observationId.toString()));
    return view(store.observation(observationId).orElseThrow());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private EncounterRow encounter(CurrentUser user, UUID id, String permission) {
    EncounterRow e = store.find(id).orElseThrow(() -> new ApiException.NotFound("Encounter"));
    if (!user.scopeOf(permission).includes(e.facilityId())) {
      throw new ApiException.NotFound("Encounter");
    }
    return e;
  }

  /** Flags from the configured alarm limits only. No limits configured: no flags. */
  private List<Flag> flags(ObservationRequest r) {
    Map<Measure, BigDecimal> values = new EnumMap<>(Measure.class);
    put(values, Measure.TEMPERATURE_C, r.temperatureC());
    put(values, Measure.PULSE_BPM, r.pulseBpm());
    put(values, Measure.RESPIRATORY_RATE, r.respiratoryRate());
    put(values, Measure.SYSTOLIC_MMHG, r.systolicMmhg());
    put(values, Measure.DIASTOLIC_MMHG, r.diastolicMmhg());
    put(values, Measure.SPO2_PCT, r.spo2Pct());
    List<Flag> flags = new ArrayList<>();
    values.forEach((measure, value) -> {
      Limit limit = triage.alarmLimits().get(measure.name());
      if (limit == null) {
        return;
      }
      if (limit.low() != null && value.compareTo(limit.low()) < 0) {
        flags.add(new Flag(measure.name(), "LOW", value, limit.low()));
      } else if (limit.high() != null && value.compareTo(limit.high()) > 0) {
        flags.add(new Flag(measure.name(), "HIGH", value, limit.high()));
      }
    });
    return flags;
  }

  private static void put(Map<Measure, BigDecimal> values, Measure m, Object v) {
    if (v instanceof BigDecimal d) {
      values.put(m, d);
    } else if (v instanceof Integer i) {
      values.put(m, BigDecimal.valueOf(i));
    }
  }

  private static ObservationRequest withTrimmedText(ObservationRequest r) {
    return new ObservationRequest(
        r.temperatureC(),
        r.pulseBpm(),
        r.respiratoryRate(),
        r.systolicMmhg(),
        r.diastolicMmhg(),
        r.spo2Pct(),
        r.weightKg(),
        r.heightCm(),
        r.painScore(),
        r.urgency(),
        StringUtils.hasText(r.allergyNote()) ? r.allergyNote().trim() : null,
        StringUtils.hasText(r.notes()) ? r.notes().trim() : null);
  }

  /** Field names with a value (audit metadata; never the values). */
  private static List<String> recordedFields(ObservationRequest r, String urgency) {
    return Stream.of(
            r.temperatureC() != null ? "temperature_c" : null,
            r.pulseBpm() != null ? "pulse_bpm" : null,
            r.respiratoryRate() != null ? "respiratory_rate" : null,
            r.systolicMmhg() != null ? "systolic_mmhg" : null,
            r.diastolicMmhg() != null ? "diastolic_mmhg" : null,
            r.spo2Pct() != null ? "spo2_pct" : null,
            r.weightKg() != null ? "weight_kg" : null,
            r.heightCm() != null ? "height_cm" : null,
            r.painScore() != null ? "pain_score" : null,
            urgency != null ? "urgency" : null,
            r.allergyNote() != null ? "allergy_note" : null,
            r.notes() != null ? "notes" : null)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  ObservationView view(ObservationRow o) {
    List<Flag> flags = json.readValue(o.flagsJson(), new TypeReference<List<Flag>>() {});
    return new ObservationView(
        o.id(),
        o.encounterId(),
        o.temperatureC(),
        o.pulseBpm(),
        o.respiratoryRate(),
        o.systolicMmhg(),
        o.diastolicMmhg(),
        o.spo2Pct(),
        o.weightKg(),
        o.heightCm(),
        o.painScore(),
        o.urgency(),
        o.allergyNote(),
        o.notes(),
        flags,
        o.status(),
        o.recordedBy(),
        o.recordedAt(),
        o.errorReason(),
        o.errorBy(),
        o.errorAt());
  }
}
