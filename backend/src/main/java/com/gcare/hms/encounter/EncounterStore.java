package com.gcare.hms.encounter;

import com.gcare.hms.encounter.EncounterApi.DepartmentRef;
import com.gcare.hms.encounter.EncounterApi.EncounterStatus;
import com.gcare.hms.encounter.EncounterApi.EncounterType;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import com.gcare.hms.patient.PatientStatus;
import com.gcare.hms.patient.Sex;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Encounter and triage persistence. Row-level security limits every statement to the current
 * organization; nothing is deleted. Reads join patients, departments and users for display only
 * (read-only, same database; see docs/18 §6).
 */
@Repository
class EncounterStore {

  /** Raw encounter row plus display data. Latest triage data is null when there is none. */
  record EncounterRow(
      UUID id,
      UUID facilityId,
      DepartmentRef department,
      PatientSummary patient,
      EncounterType type,
      EncounterStatus status,
      UserRef clinician,
      Instant arrivedAt,
      Instant consultationStartedAt,
      Instant endedAt,
      boolean triaged,
      String urgency,
      String flagsJson,
      long version) {}

  record EventRow(
      UUID id,
      String eventType,
      EncounterStatus fromStatus,
      EncounterStatus toStatus,
      DepartmentRef department,
      String reason,
      UserRef actor,
      Instant occurredAt) {}

  record ObservationRow(
      UUID id,
      UUID encounterId,
      BigDecimal temperatureC,
      Integer pulseBpm,
      Integer respiratoryRate,
      Integer systolicMmhg,
      Integer diastolicMmhg,
      Integer spo2Pct,
      BigDecimal weightKg,
      BigDecimal heightCm,
      Integer painScore,
      String urgency,
      String allergyNote,
      String notes,
      String flagsJson,
      String status,
      UserRef recordedBy,
      Instant recordedAt,
      String errorReason,
      UserRef errorBy,
      Instant errorAt) {}

  private static final String SELECT_ENCOUNTER =
      """
      SELECT e.*, d.code AS d_code, d.name AS d_name,
             p.patient_number, p.status AS p_status, p.family_name, p.given_names, p.birth_date,
             p.birth_date_estimated, p.sex, p.details_incomplete,
             u.display_name AS clinician_name,
             EXISTS (SELECT 1 FROM triage_observations t
                     WHERE t.encounter_id = e.id AND t.status = 'RECORDED') AS triaged,
             (SELECT t.urgency FROM triage_observations t
               WHERE t.encounter_id = e.id AND t.status = 'RECORDED' AND t.urgency IS NOT NULL
               ORDER BY t.recorded_at DESC LIMIT 1) AS latest_urgency,
             (SELECT t.flags::text FROM triage_observations t
               WHERE t.encounter_id = e.id AND t.status = 'RECORDED'
               ORDER BY t.recorded_at DESC LIMIT 1) AS latest_flags
      FROM encounters e
      JOIN departments d ON d.id = e.department_id
      JOIN patients p ON p.id = e.patient_id
      LEFT JOIN users u ON u.id = e.clinician_id
      """;

  private final JdbcClient jdbc;

  EncounterStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // --- lookups ---------------------------------------------------------------------------------

  /** Active departments of an active facility, by code. */
  List<DepartmentRef> activeDepartments(UUID facilityId) {
    return jdbc.sql(
            """
            SELECT d.id, d.code, d.name FROM departments d
            JOIN facilities f ON f.id = d.facility_id
            WHERE d.facility_id = :f AND d.status = 'ACTIVE' AND f.status = 'ACTIVE'
            ORDER BY d.code
            """)
        .param("f", facilityId)
        .query((rs, i) ->
            new DepartmentRef(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")))
        .list();
  }

  /** The department if it is active and belongs to an active facility. */
  Optional<DepartmentRef> activeDepartment(UUID facilityId, UUID departmentId) {
    return jdbc.sql(
            """
            SELECT d.id, d.code, d.name FROM departments d
            JOIN facilities f ON f.id = d.facility_id
            WHERE d.id = :d AND d.facility_id = :f AND d.status = 'ACTIVE' AND f.status = 'ACTIVE'
            """)
        .param("d", departmentId)
        .param("f", facilityId)
        .query((rs, i) ->
            new DepartmentRef(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")))
        .optional();
  }

  Optional<PatientStatus> patientStatus(UUID patientId) {
    return jdbc.sql("SELECT status FROM patients WHERE id = :id")
        .param("id", patientId)
        .query((rs, i) -> PatientStatus.valueOf(rs.getString(1)))
        .optional();
  }

  /** Locks the encounter row until the transaction ends. */
  Optional<EncounterRow> lock(UUID id) {
    jdbc.sql("SELECT id FROM encounters WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(UUID.class)
        .optional();
    return find(id);
  }

  Optional<EncounterRow> find(UUID id) {
    return jdbc.sql(SELECT_ENCOUNTER + " WHERE e.id = :id")
        .param("id", id)
        .query(EncounterStore::encounterRow)
        .optional();
  }

  /** Encounters at the given facilities, filtered; oldest arrival first. */
  List<EncounterRow> search(
      Collection<UUID> facilityIds,
      boolean allFacilities,
      UUID departmentId,
      UUID patientId,
      Collection<EncounterStatus> statuses,
      int page,
      int size) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(facilityIds, allFacilities, departmentId, patientId, statuses, params);
    var q =
        jdbc.sql(
            SELECT_ENCOUNTER + where + " ORDER BY e.arrived_at, e.id LIMIT :limit OFFSET :offset");
    for (var e : params.entrySet()) {
      q = q.param(e.getKey(), e.getValue());
    }
    return q.param("limit", size)
        .param("offset", (long) page * size)
        .query(EncounterStore::encounterRow)
        .list();
  }

  long count(
      Collection<UUID> facilityIds,
      boolean allFacilities,
      UUID departmentId,
      UUID patientId,
      Collection<EncounterStatus> statuses) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(facilityIds, allFacilities, departmentId, patientId, statuses, params);
    var q = jdbc.sql("SELECT count(*) FROM encounters e" + where);
    for (var e : params.entrySet()) {
      q = q.param(e.getKey(), e.getValue());
    }
    return q.query(Long.class).single();
  }

  private static String where(
      Collection<UUID> facilityIds,
      boolean allFacilities,
      UUID departmentId,
      UUID patientId,
      Collection<EncounterStatus> statuses,
      Map<String, Object> params) {
    List<String> where = new ArrayList<>();
    if (!allFacilities) {
      where.add("e.facility_id IN (:facilities)");
      params.put("facilities", facilityIds);
    }
    if (departmentId != null) {
      where.add("e.department_id = :department");
      params.put("department", departmentId);
    }
    if (patientId != null) {
      where.add("e.patient_id = :patient");
      params.put("patient", patientId);
    }
    where.add("e.status IN (:statuses)");
    params.put("statuses", statuses.stream().map(Enum::name).toList());
    return " WHERE " + String.join(" AND ", where);
  }

  /** Visits where {@code clinicianId} is seeing the patient now (workspace). */
  List<EncounterRow> inConsultationWith(UUID clinicianId) {
    return jdbc.sql(
            SELECT_ENCOUNTER
                + " WHERE e.clinician_id = :u AND e.status = 'IN_CONSULTATION'"
                + " ORDER BY e.consultation_started_at, e.id")
        .param("u", clinicianId)
        .query(EncounterStore::encounterRow)
        .list();
  }

  /** The patient's most recent observation sets not marked entered in error, newest first. */
  List<ObservationRow> recentObservationsOfPatient(UUID patientId, int limit) {
    return jdbc.sql(
            SELECT_OBSERVATION
                + " WHERE t.patient_id = :p AND t.status = 'RECORDED'"
                + " ORDER BY t.recorded_at DESC, t.id LIMIT :limit")
        .param("p", patientId)
        .param("limit", limit)
        .query(EncounterStore::observationRow)
        .list();
  }

  /** Facility of each encounter, for scope checks on rows that do not carry it. */
  Map<UUID, UUID> facilitiesOf(Collection<UUID> encounterIds) {
    Map<UUID, UUID> result = new LinkedHashMap<>();
    if (encounterIds.isEmpty()) {
      return result;
    }
    jdbc.sql("SELECT id, facility_id FROM encounters WHERE id IN (:ids)")
        .param("ids", encounterIds)
        .query((rs, i) ->
            result.put(rs.getObject("id", UUID.class), rs.getObject("facility_id", UUID.class)))
        .list();
    return result;
  }

  // --- writes ----------------------------------------------------------------------------------

  void insert(
      UUID id,
      UUID organizationId,
      UUID facilityId,
      UUID departmentId,
      UUID patientId,
      EncounterType type,
      UUID createdBy) {
    jdbc.sql(
            """
            INSERT INTO encounters (id, organization_id, facility_id, department_id, patient_id,
                encounter_type, status, created_by)
            VALUES (:id, :org, :facility, :department, :patient, :type, 'WAITING', :by)
            """)
        .param("id", id)
        .param("org", organizationId)
        .param("facility", facilityId)
        .param("department", departmentId)
        .param("patient", patientId)
        .param("type", type.name())
        .param("by", createdBy)
        .update();
  }

  /**
   * Moves the encounter to {@code status}. Timestamps follow the status: consultation start is set
   * when first called; {@code ended_at} is set for CLOSED/CANCELLED and cleared otherwise.
   */
  int transition(
      UUID id, long expectedVersion, EncounterStatus status, UUID departmentId, UUID clinicianId) {
    return jdbc.sql(
            """
            UPDATE encounters SET status = :status, department_id = :department,
                clinician_id = :clinician,
                consultation_started_at = CASE WHEN :status = 'IN_CONSULTATION'
                    THEN coalesce(consultation_started_at, now()) ELSE consultation_started_at END,
                ended_at = CASE WHEN :status IN ('CLOSED', 'CANCELLED') THEN now() ELSE NULL END,
                updated_at = now(), version = version + 1
            WHERE id = :id AND version = :version
            """)
        .param("status", status.name())
        .param("department", departmentId)
        .param("clinician", clinicianId)
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }

  void insertEvent(
      UUID organizationId,
      UUID encounterId,
      String type,
      EncounterStatus from,
      EncounterStatus to,
      UUID departmentId,
      String reason,
      UUID actor) {
    jdbc.sql(
            """
            INSERT INTO encounter_events (id, organization_id, encounter_id, event_type,
                from_status, to_status, department_id, reason, actor_id)
            VALUES (:id, :org, :encounter, :type, :from, :to, :department, :reason, :actor)
            """)
        .param("id", UUID.randomUUID())
        .param("org", organizationId)
        .param("encounter", encounterId)
        .param("type", type)
        .param("from", from == null ? null : from.name())
        .param("to", to.name())
        .param("department", departmentId)
        .param("reason", reason)
        .param("actor", actor)
        .update();
  }

  List<EventRow> events(UUID encounterId) {
    return jdbc.sql(
            """
            SELECT ev.*, d.code AS d_code, d.name AS d_name, u.display_name AS actor_name
            FROM encounter_events ev
            JOIN departments d ON d.id = ev.department_id
            JOIN users u ON u.id = ev.actor_id
            WHERE ev.encounter_id = :id ORDER BY ev.occurred_at, ev.id
            """)
        .param("id", encounterId)
        .query((rs, i) -> {
          String from = rs.getString("from_status");
          return new EventRow(
              rs.getObject("id", UUID.class),
              rs.getString("event_type"),
              from == null ? null : EncounterStatus.valueOf(from),
              EncounterStatus.valueOf(rs.getString("to_status")),
              new DepartmentRef(
                  rs.getObject("department_id", UUID.class),
                  rs.getString("d_code"),
                  rs.getString("d_name")),
              rs.getString("reason"),
              new UserRef(rs.getObject("actor_id", UUID.class), rs.getString("actor_name")),
              instant(rs, "occurred_at"));
        })
        .list();
  }

  // --- observations ----------------------------------------------------------------------------

  void insertObservation(
      UUID id,
      UUID organizationId,
      UUID encounterId,
      UUID patientId,
      EncounterApi.ObservationRequest r,
      String urgency,
      String flagsJson,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO triage_observations (id, organization_id, encounter_id, patient_id,
                temperature_c, pulse_bpm, respiratory_rate, systolic_mmhg, diastolic_mmhg,
                spo2_pct, weight_kg, height_cm, pain_score, urgency, allergy_note, notes, flags,
                recorded_by)
            VALUES (:id, :org, :encounter, :patient, :temp, :pulse, :resp, :sys, :dia, :spo2,
                :weight, :height, :pain, :urgency, :allergy, :notes, CAST(:flags AS jsonb), :by)
            """)
        .param("id", id)
        .param("org", organizationId)
        .param("encounter", encounterId)
        .param("patient", patientId)
        .param("temp", r.temperatureC())
        .param("pulse", r.pulseBpm())
        .param("resp", r.respiratoryRate())
        .param("sys", r.systolicMmhg())
        .param("dia", r.diastolicMmhg())
        .param("spo2", r.spo2Pct())
        .param("weight", r.weightKg())
        .param("height", r.heightCm())
        .param("pain", r.painScore())
        .param("urgency", urgency)
        .param("allergy", r.allergyNote())
        .param("notes", r.notes())
        .param("flags", flagsJson)
        .param("by", by)
        .update();
  }

  private static final String SELECT_OBSERVATION =
      """
      SELECT t.*, t.flags::text AS flags_text, r.display_name AS recorded_name,
             x.display_name AS error_name
      FROM triage_observations t
      JOIN users r ON r.id = t.recorded_by
      LEFT JOIN users x ON x.id = t.error_by
      """;

  List<ObservationRow> observations(UUID encounterId) {
    return jdbc.sql(
            SELECT_OBSERVATION + " WHERE t.encounter_id = :id ORDER BY t.recorded_at DESC, t.id")
        .param("id", encounterId)
        .query(EncounterStore::observationRow)
        .list();
  }

  Optional<ObservationRow> observation(UUID id) {
    return jdbc.sql(SELECT_OBSERVATION + " WHERE t.id = :id")
        .param("id", id)
        .query(EncounterStore::observationRow)
        .optional();
  }

  int markEnteredInError(UUID id, String reason, UUID by) {
    return jdbc.sql(
            """
            UPDATE triage_observations SET status = 'ENTERED_IN_ERROR', error_reason = :reason,
                error_by = :by, error_at = now()
            WHERE id = :id AND status = 'RECORDED'
            """)
        .param("reason", reason)
        .param("by", by)
        .param("id", id)
        .update();
  }

  // --- mapping ---------------------------------------------------------------------------------

  private static EncounterRow encounterRow(ResultSet rs, int i) throws SQLException {
    UUID clinicianId = rs.getObject("clinician_id", UUID.class);
    PatientSummary patient =
        new PatientSummary(
            rs.getObject("patient_id", UUID.class),
            rs.getString("patient_number"),
            PatientStatus.valueOf(rs.getString("p_status")),
            rs.getString("family_name"),
            rs.getString("given_names"),
            rs.getObject("birth_date", LocalDate.class),
            rs.getBoolean("birth_date_estimated"),
            Sex.valueOf(rs.getString("sex")),
            rs.getBoolean("details_incomplete"));
    return new EncounterRow(
        rs.getObject("id", UUID.class),
        rs.getObject("facility_id", UUID.class),
        new DepartmentRef(
            rs.getObject("department_id", UUID.class),
            rs.getString("d_code"),
            rs.getString("d_name")),
        patient,
        EncounterType.valueOf(rs.getString("encounter_type")),
        EncounterStatus.valueOf(rs.getString("status")),
        clinicianId == null ? null : new UserRef(clinicianId, rs.getString("clinician_name")),
        instant(rs, "arrived_at"),
        instant(rs, "consultation_started_at"),
        instant(rs, "ended_at"),
        rs.getBoolean("triaged"),
        rs.getString("latest_urgency"),
        rs.getString("latest_flags"),
        rs.getLong("version"));
  }

  private static ObservationRow observationRow(ResultSet rs, int i) throws SQLException {
    UUID errorBy = rs.getObject("error_by", UUID.class);
    return new ObservationRow(
        rs.getObject("id", UUID.class),
        rs.getObject("encounter_id", UUID.class),
        rs.getBigDecimal("temperature_c"),
        (Integer) rs.getObject("pulse_bpm"),
        (Integer) rs.getObject("respiratory_rate"),
        (Integer) rs.getObject("systolic_mmhg"),
        (Integer) rs.getObject("diastolic_mmhg"),
        (Integer) rs.getObject("spo2_pct"),
        rs.getBigDecimal("weight_kg"),
        rs.getBigDecimal("height_cm"),
        (Integer) rs.getObject("pain_score"),
        rs.getString("urgency"),
        rs.getString("allergy_note"),
        rs.getString("notes"),
        rs.getString("flags_text"),
        rs.getString("status"),
        new UserRef(rs.getObject("recorded_by", UUID.class), rs.getString("recorded_name")),
        instant(rs, "recorded_at"),
        rs.getString("error_reason"),
        errorBy == null ? null : new UserRef(errorBy, rs.getString("error_name")),
        instant(rs, "error_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
