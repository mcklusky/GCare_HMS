package com.gcare.hms.encounter;

import com.gcare.hms.encounter.ClinicalApi.Certainty;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Notes and diagnoses persistence. Row-level security limits statements to the organization; the
 * database refuses changes to notes that are not drafts and to recorded diagnosis values.
 */
@Repository
class ClinicalStore {

  record NoteRow(
      UUID id,
      UUID encounterId,
      String status,
      String history,
      String examination,
      String assessment,
      String plan,
      UserRef author,
      Instant createdAt,
      Instant updatedAt,
      Instant signedAt,
      UUID amendsNoteId,
      String amendmentReason,
      long version) {}

  record DiagnosisRow(
      UUID id,
      UUID encounterId,
      String description,
      Certainty certainty,
      String code,
      String codeSystem,
      String status,
      UserRef recordedBy,
      Instant recordedAt,
      String errorReason,
      UserRef errorBy,
      Instant errorAt) {}

  private static final String SELECT_NOTE =
      "SELECT n.*, u.display_name AS author_name FROM clinical_notes n JOIN users u ON u.id = n.author_id";

  private final JdbcClient jdbc;

  ClinicalStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // --- notes -----------------------------------------------------------------------------------

  void insertNote(
      UUID id,
      UUID organizationId,
      UUID encounterId,
      UUID patientId,
      UUID authorId,
      String history,
      String examination,
      String assessment,
      String plan,
      UUID amendsNoteId,
      String amendmentReason) {
    jdbc.sql(
            """
            INSERT INTO clinical_notes (id, organization_id, encounter_id, patient_id, author_id,
                status, history, examination, assessment, plan, amends_note_id, amendment_reason)
            VALUES (:id, :org, :encounter, :patient, :author, 'DRAFT', :history, :examination,
                :assessment, :plan, :amends, :reason)
            """)
        .param("id", id)
        .param("org", organizationId)
        .param("encounter", encounterId)
        .param("patient", patientId)
        .param("author", authorId)
        .param("history", history)
        .param("examination", examination)
        .param("assessment", assessment)
        .param("plan", plan)
        .param("amends", amendsNoteId)
        .param("reason", amendmentReason)
        .update();
  }

  Optional<NoteRow> note(UUID id) {
    return jdbc.sql(SELECT_NOTE + " WHERE n.id = :id")
        .param("id", id)
        .query(ClinicalStore::noteRow)
        .optional();
  }

  /** Signed notes of the encounter plus the given user's own drafts, oldest first. */
  List<NoteRow> notes(UUID encounterId, UUID viewerId) {
    return jdbc.sql(
            SELECT_NOTE
                + " WHERE n.encounter_id = :encounter AND (n.status = 'SIGNED'"
                + " OR (n.status = 'DRAFT' AND n.author_id = :viewer))"
                + " ORDER BY coalesce(n.signed_at, n.created_at), n.id")
        .param("encounter", encounterId)
        .param("viewer", viewerId)
        .query(ClinicalStore::noteRow)
        .list();
  }

  int updateDraft(
      UUID id,
      long expectedVersion,
      String history,
      String examination,
      String assessment,
      String plan) {
    return jdbc.sql(
            """
            UPDATE clinical_notes SET history = :history, examination = :examination,
                assessment = :assessment, plan = :plan, updated_at = now(), version = version + 1
            WHERE id = :id AND version = :version AND status = 'DRAFT'
            """)
        .param("history", history)
        .param("examination", examination)
        .param("assessment", assessment)
        .param("plan", plan)
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }

  int sign(UUID id, long expectedVersion) {
    return jdbc.sql(
            """
            UPDATE clinical_notes SET status = 'SIGNED', signed_at = now(), updated_at = now(),
                version = version + 1
            WHERE id = :id AND version = :version AND status = 'DRAFT'
            """)
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }

  int discard(UUID id, long expectedVersion) {
    return jdbc.sql(
            """
            UPDATE clinical_notes SET status = 'DISCARDED', discarded_at = now(), updated_at = now(),
                version = version + 1
            WHERE id = :id AND version = :version AND status = 'DRAFT'
            """)
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }

  // --- diagnoses -------------------------------------------------------------------------------

  void insertDiagnosis(
      UUID id,
      UUID organizationId,
      UUID encounterId,
      UUID patientId,
      String description,
      Certainty certainty,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO encounter_diagnoses (id, organization_id, encounter_id, patient_id,
                description, certainty, recorded_by)
            VALUES (:id, :org, :encounter, :patient, :description, :certainty, :by)
            """)
        .param("id", id)
        .param("org", organizationId)
        .param("encounter", encounterId)
        .param("patient", patientId)
        .param("description", description)
        .param("certainty", certainty.name())
        .param("by", by)
        .update();
  }

  private static final String SELECT_DIAGNOSIS =
      """
      SELECT g.*, r.display_name AS recorded_name, x.display_name AS error_name
      FROM encounter_diagnoses g
      JOIN users r ON r.id = g.recorded_by
      LEFT JOIN users x ON x.id = g.error_by
      """;

  List<DiagnosisRow> diagnoses(UUID encounterId) {
    return jdbc.sql(SELECT_DIAGNOSIS + " WHERE g.encounter_id = :id ORDER BY g.recorded_at, g.id")
        .param("id", encounterId)
        .query(ClinicalStore::diagnosisRow)
        .list();
  }

  Optional<DiagnosisRow> diagnosis(UUID id) {
    return jdbc.sql(SELECT_DIAGNOSIS + " WHERE g.id = :id")
        .param("id", id)
        .query(ClinicalStore::diagnosisRow)
        .optional();
  }

  int markDiagnosisInError(UUID id, String reason, UUID by) {
    return jdbc.sql(
            """
            UPDATE encounter_diagnoses SET status = 'ENTERED_IN_ERROR', error_reason = :reason,
                error_by = :by, error_at = now()
            WHERE id = :id AND status = 'RECORDED'
            """)
        .param("reason", reason)
        .param("by", by)
        .param("id", id)
        .update();
  }

  // --- mapping ---------------------------------------------------------------------------------

  private static NoteRow noteRow(ResultSet rs, int i) throws SQLException {
    return new NoteRow(
        rs.getObject("id", UUID.class),
        rs.getObject("encounter_id", UUID.class),
        rs.getString("status"),
        rs.getString("history"),
        rs.getString("examination"),
        rs.getString("assessment"),
        rs.getString("plan"),
        new UserRef(rs.getObject("author_id", UUID.class), rs.getString("author_name")),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        instant(rs, "signed_at"),
        rs.getObject("amends_note_id", UUID.class),
        rs.getString("amendment_reason"),
        rs.getLong("version"));
  }

  private static DiagnosisRow diagnosisRow(ResultSet rs, int i) throws SQLException {
    UUID errorBy = rs.getObject("error_by", UUID.class);
    return new DiagnosisRow(
        rs.getObject("id", UUID.class),
        rs.getObject("encounter_id", UUID.class),
        rs.getString("description"),
        Certainty.valueOf(rs.getString("certainty")),
        rs.getString("code"),
        rs.getString("code_system"),
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
