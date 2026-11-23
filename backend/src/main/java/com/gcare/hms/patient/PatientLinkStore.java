package com.gcare.hms.patient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Link persistence (docs/17 §5). Row-level security limits every statement to the current
 * organization; nothing is deleted. Call inside the service's transaction.
 */
@Repository
class PatientLinkStore {

  record LinkRow(
      UUID id,
      UUID retiredId,
      UUID survivingId,
      UUID flagId,
      String status,
      String requestReason,
      UUID requestedBy,
      Instant requestedAt,
      UUID decidedBy,
      Instant decidedAt,
      String decisionNote,
      String overrideReason,
      UUID unlinkedBy,
      Instant unlinkedAt,
      String unlinkReason,
      long version) {}

  private final JdbcClient jdbc;

  PatientLinkStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Locks the patient rows (in id order, so two decisions on the same records cannot deadlock or
   * both pass their checks). Hold until the transaction ends.
   */
  void lockPatients(Collection<UUID> ids) {
    jdbc.sql("SELECT id FROM patients WHERE id IN (:ids) ORDER BY id FOR UPDATE")
        .param("ids", ids)
        .query(UUID.class)
        .list();
  }

  void insert(
      UUID id,
      UUID organizationId,
      UUID retiredId,
      UUID survivingId,
      UUID flagId,
      String reason,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO patient_links (id, organization_id, retired_patient_id,
                surviving_patient_id, flag_id, status, request_reason, requested_by)
            VALUES (:id, :org, :retired, :surviving, :flag, 'REQUESTED', :reason, :by)
            """)
        .param("id", id)
        .param("org", organizationId)
        .param("retired", retiredId)
        .param("surviving", survivingId)
        .param("flag", flagId)
        .param("reason", reason)
        .param("by", by)
        .update();
  }

  Optional<LinkRow> find(UUID id) {
    return jdbc.sql("SELECT * FROM patient_links WHERE id = :id")
        .param("id", id)
        .query(PatientLinkStore::linkRow)
        .optional();
  }

  List<LinkRow> byStatus(String status, int page, int size) {
    return jdbc.sql(
            "SELECT * FROM patient_links WHERE status = :status"
                + " ORDER BY requested_at, id LIMIT :limit OFFSET :offset")
        .param("status", status)
        .param("limit", size)
        .param("offset", (long) page * size)
        .query(PatientLinkStore::linkRow)
        .list();
  }

  long count(String status) {
    return jdbc.sql("SELECT count(*) FROM patient_links WHERE status = :status")
        .param("status", status)
        .query(Long.class)
        .single();
  }

  /** Every link the patient took part in, on either side, newest first. */
  List<LinkRow> forPatient(UUID patientId) {
    return jdbc.sql(
            "SELECT * FROM patient_links WHERE retired_patient_id = :id"
                + " OR surviving_patient_id = :id ORDER BY requested_at DESC, id")
        .param("id", patientId)
        .query(PatientLinkStore::linkRow)
        .list();
  }

  /** A link for this record as the retired side is waiting or in place. */
  boolean liveLinkAsRetired(UUID patientId) {
    return jdbc.sql(
                "SELECT count(*) FROM patient_links WHERE retired_patient_id = :id"
                    + " AND status IN ('REQUESTED', 'APPROVED')")
            .param("id", patientId)
            .query(Long.class)
            .single()
        > 0;
  }

  /** Other records already point to this one; retiring it would make a chain. */
  boolean hasRecordsLinkedTo(UUID patientId) {
    return jdbc.sql("SELECT count(*) FROM patients WHERE linked_to_patient_id = :id")
            .param("id", patientId)
            .query(Long.class)
            .single()
        > 0;
  }

  int decide(
      UUID id, long expectedVersion, String status, String note, String overrideReason, UUID by) {
    return jdbc.sql(
            """
            UPDATE patient_links SET status = :status, decision_note = :note,
                override_reason = :override, decided_by = :by, decided_at = now(),
                version = version + 1
            WHERE id = :id AND version = :version AND status = 'REQUESTED'
            """)
        .param("status", status)
        .param("note", note)
        .param("override", overrideReason)
        .param("by", by)
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }

  int unlink(UUID id, long expectedVersion, String reason, UUID by) {
    return jdbc.sql(
            """
            UPDATE patient_links SET status = 'UNLINKED', unlink_reason = :reason,
                unlinked_by = :by, unlinked_at = now(), version = version + 1
            WHERE id = :id AND version = :version AND status = 'APPROVED'
            """)
        .param("reason", reason)
        .param("by", by)
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }

  /** Retires a record: status LINKED, pointing to the surviving record. Data is not touched. */
  int retire(UUID retiredId, UUID survivingId) {
    return jdbc.sql(
            """
            UPDATE patients SET status = 'LINKED', linked_to_patient_id = :surviving,
                updated_at = now(), version = version + 1
            WHERE id = :id AND status = 'ACTIVE'
            """)
        .param("surviving", survivingId)
        .param("id", retiredId)
        .update();
  }

  /** Reverses {@link #retire}. */
  int restore(UUID retiredId, UUID survivingId) {
    return jdbc.sql(
            """
            UPDATE patients SET status = 'ACTIVE', linked_to_patient_id = NULL,
                updated_at = now(), version = version + 1
            WHERE id = :id AND status = 'LINKED' AND linked_to_patient_id = :surviving
            """)
        .param("surviving", survivingId)
        .param("id", retiredId)
        .update();
  }

  private static LinkRow linkRow(ResultSet rs, int i) throws SQLException {
    return new LinkRow(
        rs.getObject("id", UUID.class),
        rs.getObject("retired_patient_id", UUID.class),
        rs.getObject("surviving_patient_id", UUID.class),
        rs.getObject("flag_id", UUID.class),
        rs.getString("status"),
        rs.getString("request_reason"),
        rs.getObject("requested_by", UUID.class),
        instant(rs, "requested_at"),
        rs.getObject("decided_by", UUID.class),
        instant(rs, "decided_at"),
        rs.getString("decision_note"),
        rs.getString("override_reason"),
        rs.getObject("unlinked_by", UUID.class),
        instant(rs, "unlinked_at"),
        rs.getString("unlink_reason"),
        rs.getLong("version"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
