package com.gcare.hms.patient;

import com.gcare.hms.patient.DuplicateScorer.Facts;
import com.gcare.hms.patient.DuplicateScorer.IdentifierFact;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Patient persistence. Row-level security limits every statement to the current organization; no
 * statement deletes anything. Call inside the service's transaction.
 */
@Repository
class PatientStore {

  /** Upper bound of candidates fetched per evidence type before scoring. */
  private static final int CANDIDATE_LIMIT = 200;

  record PatientRow(
      UUID id,
      UUID organizationId,
      String patientNumber,
      UUID registrationFacilityId,
      PatientStatus status,
      String familyName,
      String givenNames,
      String familyNorm,
      String givenNorm,
      LocalDate birthDate,
      boolean birthDateEstimated,
      Sex sex,
      String phone,
      String phoneNorm,
      boolean detailsIncomplete,
      RegistrationSource registrationSource,
      Instant createdAt,
      UUID createdBy,
      long version,
      UUID linkedToId) {}

  record IdentifierRow(
      UUID id,
      UUID patientId,
      IdentifierType type,
      String value,
      String valueNorm,
      String issuer,
      boolean verified,
      String status,
      String source,
      Instant createdAt) {}

  /** Search filters, already normalized. Null fields are not applied. */
  record SearchFilter(
      String patientNumber,
      IdentifierType identifierType,
      String identifierNorm,
      String phoneNorm,
      String nameNorm,
      LocalDate birthDate,
      Integer birthYear,
      Sex sex) {}

  private final JdbcClient jdbc;

  PatientStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // --- numbers ---------------------------------------------------------------------------------

  long nextNumber(UUID organizationId) {
    jdbc.sql(
            "INSERT INTO patient_number_counters (organization_id, next_value) VALUES (:org, :first)"
                + " ON CONFLICT (organization_id) DO NOTHING")
        .param("org", organizationId)
        .param("first", PatientNumber.FIRST)
        .update();
    return jdbc.sql(
            "UPDATE patient_number_counters SET next_value = next_value + 1"
                + " WHERE organization_id = :org RETURNING next_value - 1")
        .param("org", organizationId)
        .query(Long.class)
        .single();
  }

  // --- writes ----------------------------------------------------------------------------------

  void insert(PatientRow p) {
    jdbc.sql(
            """
            INSERT INTO patients (id, organization_id, patient_number, registration_facility_id,
                status, family_name, given_names, family_name_norm, given_names_norm, birth_date,
                birth_date_estimated, sex, phone, phone_norm, details_incomplete,
                registration_source, created_by)
            VALUES (:id, :org, :number, :facility, :status, :family, :given, :familyNorm,
                :givenNorm, :birthDate, :estimated, :sex, :phone, :phoneNorm, :incomplete,
                :source, :createdBy)
            """)
        .param("id", p.id())
        .param("org", p.organizationId())
        .param("number", p.patientNumber())
        .param("facility", p.registrationFacilityId())
        .param("status", p.status().name())
        .param("family", p.familyName())
        .param("given", p.givenNames())
        .param("familyNorm", p.familyNorm())
        .param("givenNorm", p.givenNorm())
        .param("birthDate", p.birthDate())
        .param("estimated", p.birthDateEstimated())
        .param("sex", p.sex().name())
        .param("phone", p.phone())
        .param("phoneNorm", p.phoneNorm())
        .param("incomplete", p.detailsIncomplete())
        .param("source", p.registrationSource().name())
        .param("createdBy", p.createdBy())
        .update();
  }

  void insertIdentifier(UUID organizationId, UUID patientId, IdentifierRow row, UUID createdBy) {
    jdbc.sql(
            """
            INSERT INTO patient_identifiers (id, organization_id, patient_id, identifier_type,
                value, value_norm, issuer, verified, status, source, created_by)
            VALUES (:id, :org, :patient, :type, :value, :norm, :issuer, :verified, 'ACTIVE',
                :source, :createdBy)
            """)
        .param("id", row.id())
        .param("org", organizationId)
        .param("patient", patientId)
        .param("type", row.type().name())
        .param("value", row.value())
        .param("norm", row.valueNorm())
        .param("issuer", row.issuer())
        .param("verified", row.verified())
        .param("source", row.source())
        .param("createdBy", createdBy)
        .update();
  }

  void insertDuplicateFlag(
      UUID organizationId,
      UUID patientId,
      UUID candidateId,
      int score,
      String reason,
      UUID createdBy) {
    jdbc.sql(
            """
            INSERT INTO patient_duplicate_flags (id, organization_id, patient_id,
                candidate_patient_id, score, origin, reason, status, created_by)
            VALUES (:id, :org, :patient, :candidate, :score, 'REGISTERED_DESPITE_MATCH', :reason,
                'OPEN', :createdBy)
            """)
        .param("id", UUID.randomUUID())
        .param("org", organizationId)
        .param("patient", patientId)
        .param("candidate", candidateId)
        .param("score", score)
        .param("reason", reason)
        .param("createdBy", createdBy)
        .update();
  }

  /**
   * Applies a correction if the record is still at {@code expectedVersion}; returns rows updated.
   */
  int update(PatientRow p, long expectedVersion) {
    return jdbc.sql(
            """
            UPDATE patients SET family_name = :family, given_names = :given,
                family_name_norm = :familyNorm, given_names_norm = :givenNorm,
                birth_date = :birthDate, birth_date_estimated = :estimated, sex = :sex,
                phone = :phone, phone_norm = :phoneNorm, details_incomplete = :incomplete,
                updated_at = now(), version = version + 1
            WHERE id = :id AND version = :version AND status = 'ACTIVE'
            """)
        .param("family", p.familyName())
        .param("given", p.givenNames())
        .param("familyNorm", p.familyNorm())
        .param("givenNorm", p.givenNorm())
        .param("birthDate", p.birthDate())
        .param("estimated", p.birthDateEstimated())
        .param("sex", p.sex().name())
        .param("phone", p.phone())
        .param("phoneNorm", p.phoneNorm())
        .param("incomplete", p.detailsIncomplete())
        .param("id", p.id())
        .param("version", expectedVersion)
        .update();
  }

  void insertChange(
      UUID organizationId,
      UUID patientId,
      long patientVersion,
      String changesJson,
      String reason,
      UUID changedBy) {
    jdbc.sql(
            """
            INSERT INTO patient_changes (id, organization_id, patient_id, patient_version, changes,
                reason, changed_by)
            VALUES (:id, :org, :patient, :version, CAST(:changes AS jsonb), :reason, :by)
            """)
        .param("id", UUID.randomUUID())
        .param("org", organizationId)
        .param("patient", patientId)
        .param("version", patientVersion)
        .param("changes", changesJson)
        .param("reason", reason)
        .param("by", changedBy)
        .update();
  }

  record ChangeRow(
      UUID id,
      long patientVersion,
      String changesJson,
      String reason,
      UUID changedBy,
      Instant changedAt) {}

  List<ChangeRow> changes(UUID patientId) {
    return jdbc.sql(
            "SELECT id, patient_version, changes::text AS changes, reason, changed_by, changed_at"
                + " FROM patient_changes WHERE patient_id = :id ORDER BY patient_version DESC")
        .param("id", patientId)
        .query((rs, i) ->
            new ChangeRow(
                rs.getObject("id", UUID.class),
                rs.getLong("patient_version"),
                rs.getString("changes"),
                rs.getString("reason"),
                rs.getObject("changed_by", UUID.class),
                rs.getObject("changed_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  // --- duplicate flags -------------------------------------------------------------------------

  record FlagRow(
      UUID id,
      UUID patientId,
      UUID candidateId,
      int score,
      String origin,
      String reason,
      String status,
      UUID createdBy,
      Instant createdAt,
      UUID resolvedBy,
      Instant resolvedAt,
      String resolutionNote,
      long version) {}

  boolean openFlagExists(UUID a, UUID b) {
    return jdbc.sql(
                "SELECT count(*) FROM patient_duplicate_flags WHERE status = 'OPEN'"
                    + " AND ((patient_id = :a AND candidate_patient_id = :b)"
                    + " OR (patient_id = :b AND candidate_patient_id = :a))")
            .param("a", a)
            .param("b", b)
            .query(Long.class)
            .single()
        > 0;
  }

  void insertStaffReport(
      UUID organizationId, UUID patientId, UUID candidateId, int score, String reason, UUID by) {
    jdbc.sql(
            """
            INSERT INTO patient_duplicate_flags (id, organization_id, patient_id,
                candidate_patient_id, score, origin, reason, status, created_by)
            VALUES (:id, :org, :patient, :candidate, :score, 'STAFF_REPORT', :reason, 'OPEN', :by)
            """)
        .param("id", UUID.randomUUID())
        .param("org", organizationId)
        .param("patient", patientId)
        .param("candidate", candidateId)
        .param("score", score)
        .param("reason", reason)
        .param("by", by)
        .update();
  }

  List<FlagRow> flags(String status, int page, int size) {
    return jdbc.sql(
            "SELECT * FROM patient_duplicate_flags WHERE status = :status"
                + " ORDER BY score DESC, created_at LIMIT :limit OFFSET :offset")
        .param("status", status)
        .param("limit", size)
        .param("offset", (long) page * size)
        .query(PatientStore::flagRow)
        .list();
  }

  long countFlags(String status) {
    return jdbc.sql("SELECT count(*) FROM patient_duplicate_flags WHERE status = :status")
        .param("status", status)
        .query(Long.class)
        .single();
  }

  Optional<FlagRow> flag(UUID id) {
    return jdbc.sql("SELECT * FROM patient_duplicate_flags WHERE id = :id")
        .param("id", id)
        .query(PatientStore::flagRow)
        .optional();
  }

  int resolveFlag(UUID id, long expectedVersion, String status, String note, UUID by) {
    return jdbc.sql(
            """
            UPDATE patient_duplicate_flags SET status = :status, resolution_note = :note,
                resolved_by = :by, resolved_at = now(), version = version + 1
            WHERE id = :id AND version = :version AND status = 'OPEN'
            """)
        .param("status", status)
        .param("note", note)
        .param("by", by)
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }

  /** Patients already judged "not a duplicate" of {@code patientId}; not suggested again. */
  Set<UUID> notDuplicateOf(UUID patientId) {
    return new LinkedHashSet<>(
        jdbc.sql(
                "SELECT CASE WHEN patient_id = :id THEN candidate_patient_id ELSE patient_id END"
                    + " FROM patient_duplicate_flags WHERE status = 'NOT_DUPLICATE'"
                    + " AND (patient_id = :id OR candidate_patient_id = :id)")
            .param("id", patientId)
            .query(UUID.class)
            .list());
  }

  private static FlagRow flagRow(ResultSet rs, int i) throws SQLException {
    OffsetDateTime resolvedAt = rs.getObject("resolved_at", OffsetDateTime.class);
    return new FlagRow(
        rs.getObject("id", UUID.class),
        rs.getObject("patient_id", UUID.class),
        rs.getObject("candidate_patient_id", UUID.class),
        rs.getInt("score"),
        rs.getString("origin"),
        rs.getString("reason"),
        rs.getString("status"),
        rs.getObject("created_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("resolved_by", UUID.class),
        resolvedAt == null ? null : resolvedAt.toInstant(),
        rs.getString("resolution_note"),
        rs.getLong("version"));
  }

  // --- reads -----------------------------------------------------------------------------------

  Optional<PatientRow> find(UUID id) {
    return jdbc.sql("SELECT * FROM patients WHERE id = :id")
        .param("id", id)
        .query(PatientStore::patientRow)
        .optional();
  }

  List<PatientRow> findAll(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT * FROM patients WHERE id IN (:ids)")
        .param("ids", ids)
        .query(PatientStore::patientRow)
        .list();
  }

  Map<UUID, List<IdentifierRow>> identifiers(Collection<UUID> patientIds) {
    if (patientIds.isEmpty()) {
      return Map.of();
    }
    return jdbc
        .sql(
            "SELECT * FROM patient_identifiers WHERE patient_id IN (:ids) AND status = 'ACTIVE'"
                + " ORDER BY created_at, id")
        .param("ids", patientIds)
        .query(PatientStore::identifierRow)
        .list()
        .stream()
        .collect(
            Collectors.groupingBy(
                IdentifierRow::patientId, LinkedHashMap::new, Collectors.toList()));
  }

  /**
   * Active patients that share any evidence with {@code facts}: an identifier, the phone, a similar
   * full name (pg_trgm), or the birth date with a similar family name. Scoring decides.
   */
  Set<UUID> candidateIds(Facts facts, UUID excludeId) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (IdentifierFact id : facts.identifiers()) {
      ids.addAll(
          jdbc.sql(
                  """
                  SELECT DISTINCT i.patient_id FROM patient_identifiers i
                  JOIN patients p ON p.id = i.patient_id AND p.status = 'ACTIVE'
                  WHERE i.status = 'ACTIVE' AND i.identifier_type = :type AND i.value_norm = :value
                  LIMIT :limit
                  """)
              .param("type", id.type().name())
              .param("value", id.valueNorm())
              .param("limit", CANDIDATE_LIMIT)
              .query(UUID.class)
              .list());
    }
    if (facts.phoneNorm() != null) {
      ids.addAll(
          jdbc.sql(
                  "SELECT id FROM patients WHERE status = 'ACTIVE' AND phone_norm = :phone"
                      + " LIMIT :limit")
              .param("phone", facts.phoneNorm())
              .param("limit", CANDIDATE_LIMIT)
              .query(UUID.class)
              .list());
    }
    String fullName = fullName(facts.givenNorm(), facts.familyNorm());
    if (fullName != null) {
      ids.addAll(
          jdbc.sql(
                  "SELECT id FROM patients WHERE status = 'ACTIVE' AND full_name_norm % :name"
                      + " ORDER BY similarity(full_name_norm, :name) DESC LIMIT :limit")
              .param("name", fullName)
              .param("limit", CANDIDATE_LIMIT)
              .query(UUID.class)
              .list());
    }
    if (facts.birthDate() != null && facts.familyNorm() != null) {
      ids.addAll(
          jdbc.sql(
                  "SELECT id FROM patients WHERE status = 'ACTIVE'"
                      + " AND date_part('year', birth_date) = :year"
                      + " AND (family_name_norm % :family OR given_names_norm % :family)"
                      + " LIMIT :limit")
              .param("year", facts.birthDate().getYear())
              .param("family", facts.familyNorm())
              .param("limit", CANDIDATE_LIMIT)
              .query(UUID.class)
              .list());
    }
    if (excludeId != null) {
      ids.remove(excludeId);
    }
    return ids;
  }

  /** One page of search results and the total. */
  record SearchPage(List<PatientRow> rows, long total) {}

  SearchPage search(SearchFilter filter, int page, int size) {
    List<String> where = new ArrayList<>();
    Map<String, Object> params = new LinkedHashMap<>();
    if (filter.patientNumber() != null) {
      where.add("p.patient_number = :number"); // any status: old numbers must still be found
      params.put("number", filter.patientNumber());
    } else {
      where.add("p.status = 'ACTIVE'");
    }
    if (filter.identifierNorm() != null) {
      where.add(
          "EXISTS (SELECT 1 FROM patient_identifiers i WHERE i.patient_id = p.id"
              + " AND i.status = 'ACTIVE' AND i.identifier_type = :idType AND i.value_norm = :idValue)");
      params.put("idType", filter.identifierType().name());
      params.put("idValue", filter.identifierNorm());
    }
    if (filter.phoneNorm() != null) {
      where.add("p.phone_norm = :phone");
      params.put("phone", filter.phoneNorm());
    }
    if (filter.nameNorm() != null) {
      where.add("p.full_name_norm % :name");
      params.put("name", filter.nameNorm());
    }
    if (filter.birthDate() != null) {
      where.add("p.birth_date = :birthDate");
      params.put("birthDate", filter.birthDate());
    }
    if (filter.birthYear() != null) {
      where.add("date_part('year', p.birth_date) = :birthYear");
      params.put("birthYear", filter.birthYear());
    }
    if (filter.sex() != null) {
      where.add("p.sex = :sex");
      params.put("sex", filter.sex().name());
    }
    String clause = " FROM patients p WHERE " + String.join(" AND ", where);
    String order =
        filter.nameNorm() != null
            ? " ORDER BY similarity(p.full_name_norm, :name) DESC, p.patient_number"
            : " ORDER BY p.patient_number";

    var count = jdbc.sql("SELECT count(*)" + clause);
    var select = jdbc.sql("SELECT p.*" + clause + order + " LIMIT :limit OFFSET :offset");
    for (var e : params.entrySet()) {
      count = count.param(e.getKey(), e.getValue());
      select = select.param(e.getKey(), e.getValue());
    }
    long total = count.query(Long.class).single();
    List<PatientRow> rows =
        select
            .param("limit", size)
            .param("offset", (long) page * size)
            .query(PatientStore::patientRow)
            .list();
    return new SearchPage(rows, total);
  }

  static String fullName(String givenNorm, String familyNorm) {
    String s =
        ((givenNorm == null ? "" : givenNorm) + " " + (familyNorm == null ? "" : familyNorm))
            .trim();
    return s.isEmpty() ? null : s;
  }

  static PatientRow patientRow(ResultSet rs, int i) throws SQLException {
    return new PatientRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organization_id", UUID.class),
        rs.getString("patient_number"),
        rs.getObject("registration_facility_id", UUID.class),
        PatientStatus.valueOf(rs.getString("status")),
        rs.getString("family_name"),
        rs.getString("given_names"),
        rs.getString("family_name_norm"),
        rs.getString("given_names_norm"),
        rs.getObject("birth_date", LocalDate.class),
        rs.getBoolean("birth_date_estimated"),
        Sex.valueOf(rs.getString("sex")),
        rs.getString("phone"),
        rs.getString("phone_norm"),
        rs.getBoolean("details_incomplete"),
        RegistrationSource.valueOf(rs.getString("registration_source")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("created_by", UUID.class),
        rs.getLong("version"),
        rs.getObject("linked_to_patient_id", UUID.class));
  }

  private static IdentifierRow identifierRow(ResultSet rs, int i) throws SQLException {
    return new IdentifierRow(
        rs.getObject("id", UUID.class),
        rs.getObject("patient_id", UUID.class),
        IdentifierType.valueOf(rs.getString("identifier_type")),
        rs.getString("value"),
        rs.getString("value_norm"),
        rs.getString("issuer"),
        rs.getBoolean("verified"),
        rs.getString("status"),
        rs.getString("source"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
