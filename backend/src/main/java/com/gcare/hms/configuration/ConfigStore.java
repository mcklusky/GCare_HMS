package com.gcare.hms.configuration;

import com.gcare.hms.configuration.ConfigApi.UserRef;
import com.gcare.hms.configuration.ConfigApi.VersionStatus;
import com.gcare.hms.configuration.ConfigApi.VersionView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configuration versions and facility logos. Row-level security limits statements to the
 * organization; values never change once stored (database trigger and column grants).
 */
@Repository
class ConfigStore {

  record Logo(UUID id, UUID facilityId, String contentType, byte[] bytes) {}

  private static final String SELECT =
      """
      SELECT c.*, pu.display_name AS proposer, du.display_name AS decider
      FROM config_versions c
      JOIN users pu ON pu.id = c.proposed_by
      LEFT JOIN users du ON du.id = c.decided_by
      """;

  /** Matches NULL facility (organization settings) or the given facility. */
  private static final String SAME_SETTING =
      " c.setting_key = :key AND c.facility_id IS NOT DISTINCT FROM CAST(:facility AS uuid)";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  ConfigStore(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  List<VersionView> versions(String key, UUID facility) {
    return jdbc.sql(SELECT + " WHERE" + SAME_SETTING + " ORDER BY c.version_number DESC")
        .param("key", key)
        .param("facility", facility)
        .query(this::row)
        .list();
  }

  Optional<VersionView> version(UUID id) {
    return jdbc.sql(SELECT + " WHERE c.id = :id").param("id", id).query(this::row).optional();
  }

  Optional<VersionView> active(String key, UUID facility) {
    return jdbc.sql(SELECT + " WHERE" + SAME_SETTING + " AND c.status = 'ACTIVE'")
        .param("key", key)
        .param("facility", facility)
        .query(this::row)
        .optional();
  }

  List<VersionView> pending() {
    return jdbc.sql(SELECT + " WHERE c.status = 'PROPOSED' ORDER BY c.proposed_at")
        .query(this::row)
        .list();
  }

  int nextNumber(String key, UUID facility) {
    return jdbc.sql(
            "SELECT coalesce(max(c.version_number), 0) + 1 FROM config_versions c WHERE"
                + SAME_SETTING)
        .param("key", key)
        .param("facility", facility)
        .query(Integer.class)
        .single();
  }

  void insert(
      UUID id,
      UUID org,
      String key,
      UUID facility,
      int number,
      String valueJson,
      String reason,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO config_versions (id, organization_id, setting_key, facility_id, version_number, value,
                status, reason, proposed_by)
            VALUES (:id, :org, :key, :facility, :number, CAST(:value AS jsonb), 'PROPOSED', :reason, :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("key", key)
        .param("facility", facility)
        .param("number", number)
        .param("value", valueJson)
        .param("reason", reason)
        .param("by", by)
        .update();
  }

  int supersedeActive(String key, UUID facility) {
    return jdbc.sql(
            "UPDATE config_versions c SET status = 'SUPERSEDED', version = version + 1 WHERE"
                + SAME_SETTING
                + " AND c.status = 'ACTIVE'")
        .param("key", key)
        .param("facility", facility)
        .update();
  }

  int decide(UUID id, long version, VersionStatus status, String note, UUID by) {
    return jdbc.sql(
            """
            UPDATE config_versions SET status = :st, decided_by = :by, decided_at = now(), decision_note = :note,
                version = version + 1
            WHERE id = :id AND version = :version AND status = 'PROPOSED'
            """)
        .param("st", status.name())
        .param("by", by)
        .param("note", note)
        .param("id", id)
        .param("version", version)
        .update();
  }

  // --- logos -----------------------------------------------------------------------------------

  void insertLogo(
      UUID id, UUID org, UUID facility, String contentType, byte[] bytes, String sha, UUID by) {
    jdbc.sql(
            """
            INSERT INTO facility_logos (id, organization_id, facility_id, content_type, bytes, sha256, uploaded_by)
            VALUES (:id, :org, :facility, :type, :bytes, :sha, :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("facility", facility)
        .param("type", contentType)
        .param("bytes", bytes)
        .param("sha", sha)
        .param("by", by)
        .update();
  }

  Optional<Logo> logo(UUID id) {
    return jdbc.sql(
            "SELECT id, facility_id, content_type, bytes FROM facility_logos WHERE id = :id")
        .param("id", id)
        .query((rs, i) ->
            new Logo(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getString("content_type"),
                rs.getBytes("bytes")))
        .optional();
  }

  boolean logoBelongsTo(UUID id, UUID facility) {
    return jdbc.sql("SELECT count(*) FROM facility_logos WHERE id = :id AND facility_id = :f")
            .param("id", id)
            .param("f", facility)
            .query(Long.class)
            .single()
        > 0;
  }

  Optional<String> facilityName(UUID facility) {
    return jdbc.sql("SELECT name FROM facilities WHERE id = :id")
        .param("id", facility)
        .query(String.class)
        .optional();
  }

  // --- mapping ---------------------------------------------------------------------------------

  private VersionView row(ResultSet rs, int i) throws SQLException {
    UUID decider = rs.getObject("decided_by", UUID.class);
    return new VersionView(
        rs.getObject("id", UUID.class),
        rs.getString("setting_key"),
        rs.getObject("facility_id", UUID.class),
        rs.getInt("version_number"),
        json.readTree(rs.getString("value")),
        VersionStatus.valueOf(rs.getString("status")),
        rs.getString("reason"),
        new UserRef(rs.getObject("proposed_by", UUID.class), rs.getString("proposer")),
        instant(rs, "proposed_at"),
        decider == null ? null : new UserRef(decider, rs.getString("decider")),
        instant(rs, "decided_at"),
        rs.getString("decision_note"),
        rs.getLong("version"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
