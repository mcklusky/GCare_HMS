package com.gcare.hms.radiology;

import com.gcare.hms.radiology.ImagingWorkApi.UserRef;
import com.gcare.hms.radiology.RadiologyReportApi.Communication;
import com.gcare.hms.radiology.RadiologyReportApi.CommunicationMethod;
import com.gcare.hms.radiology.RadiologyReportApi.ReportStatus;
import com.gcare.hms.radiology.RadiologyReportApi.Sections;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Radiology reports and critical results. Signed reports never change (database trigger). */
@Repository
class RadiologyReportStore {

  record ReportRow(
      UUID id,
      UUID facilityId,
      UUID orderId,
      ReportStatus status,
      String technique,
      String comparison,
      String findings,
      String impression,
      boolean critical,
      UserRef author,
      Instant createdAt,
      Instant updatedAt,
      Instant signedAt,
      UUID amendsReportId,
      String amendmentReason,
      long version) {}

  record CriticalRow(
      UUID reportId,
      UUID facilityId,
      UUID orderId,
      UserRef flaggedBy,
      Instant flaggedAt,
      String communicatedTo,
      CommunicationMethod method,
      Instant communicatedAt,
      String communicationNote,
      UserRef acknowledgedBy,
      Instant acknowledgedAt,
      String acknowledgementNote,
      long version) {}

  private static final String REPORT =
      "SELECT r.*, u.display_name AS author_name FROM radiology_reports r JOIN users u ON u.id = r.author_id";

  private static final String CRITICAL =
      """
      SELECT c.*, fu.display_name AS flagger, au.display_name AS acknowledger
      FROM critical_results c
      JOIN users fu ON fu.id = c.flagged_by
      LEFT JOIN users au ON au.id = c.acknowledged_by
      """;

  private final JdbcClient jdbc;

  RadiologyReportStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void insert(
      UUID id,
      UUID org,
      UUID facility,
      UUID order,
      UUID author,
      Sections s,
      UUID amends,
      String amendmentReason) {
    jdbc.sql(
            """
            INSERT INTO radiology_reports (id, organization_id, facility_id, order_id, author_id, status, technique,
                comparison, findings, impression, critical, amends_report_id, amendment_reason)
            VALUES (:id, :org, :f, :o, :a, 'DRAFT', :t, :c, :fi, :im, :crit, :amends, :reason)
            """)
        .param("id", id)
        .param("org", org)
        .param("f", facility)
        .param("o", order)
        .param("a", author)
        .param("t", s.technique())
        .param("c", s.comparison())
        .param("fi", s.findings())
        .param("im", s.impression())
        .param("crit", Boolean.TRUE.equals(s.critical()))
        .param("amends", amends)
        .param("reason", amendmentReason)
        .update();
  }

  int update(UUID id, long version, Sections s) {
    return jdbc.sql(
            """
            UPDATE radiology_reports SET technique = :t, comparison = :c, findings = :fi, impression = :im,
                critical = :crit, updated_at = now(), version = version + 1
            WHERE id = :id AND version = :v AND status = 'DRAFT'
            """)
        .param("t", s.technique())
        .param("c", s.comparison())
        .param("fi", s.findings())
        .param("im", s.impression())
        .param("crit", Boolean.TRUE.equals(s.critical()))
        .param("id", id)
        .param("v", version)
        .update();
  }

  int sign(UUID id, long version) {
    return jdbc.sql(
            """
            UPDATE radiology_reports SET status = 'SIGNED', signed_at = now(), updated_at = now(), version = version + 1
            WHERE id = :id AND version = :v AND status = 'DRAFT'
            """)
        .param("id", id)
        .param("v", version)
        .update();
  }

  int discard(UUID id, long version) {
    return jdbc.sql(
            """
            UPDATE radiology_reports SET status = 'DISCARDED', discarded_at = now(), updated_at = now(),
                version = version + 1
            WHERE id = :id AND version = :v AND status = 'DRAFT'
            """)
        .param("id", id)
        .param("v", version)
        .update();
  }

  Optional<ReportRow> report(UUID id) {
    return jdbc.sql(REPORT + " WHERE r.id = :id").param("id", id).query(this::mapReport).optional();
  }

  void lock(UUID id) {
    jdbc.sql("SELECT id FROM radiology_reports WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(UUID.class)
        .optional();
  }

  /** Drafts and signed reports of an order, oldest first (discarded ones are left out). */
  List<ReportRow> reportsOfOrder(UUID order) {
    return jdbc.sql(
            REPORT + " WHERE r.order_id = :o AND r.status <> 'DISCARDED' ORDER BY r.created_at")
        .param("o", order)
        .query(this::mapReport)
        .list();
  }

  /** Examined imaging orders without a signed report, or with an open draft. */
  List<UUID> ordersToReport() {
    return jdbc.sql(
            """
            SELECT io.order_id FROM imaging_orders io JOIN clinical_orders o ON o.id = io.order_id
            WHERE io.examined_at IS NOT NULL AND o.status <> 'CANCELLED'
              AND (NOT EXISTS (SELECT 1 FROM radiology_reports r WHERE r.order_id = io.order_id AND r.status = 'SIGNED')
                   OR EXISTS (SELECT 1 FROM radiology_reports r WHERE r.order_id = io.order_id AND r.status = 'DRAFT'))
            ORDER BY (o.priority = 'URGENT') DESC, io.examined_at
            LIMIT 300
            """)
        .query(UUID.class)
        .list();
  }

  void insertCritical(UUID report, UUID org, UUID facility, UUID order, UUID by, Communication c) {
    jdbc.sql(
            """
            INSERT INTO critical_results (report_id, organization_id, facility_id, order_id, flagged_by, communicated_to,
                communication_method, communicated_at, communication_note)
            VALUES (:r, :org, :f, :o, :by, :to, :m, :at, :note)
            """)
        .param("r", report)
        .param("org", org)
        .param("f", facility)
        .param("o", order)
        .param("by", by)
        .param("to", c.communicatedTo().trim())
        .param("m", c.method().name())
        .param("at", OffsetDateTime.ofInstant(c.communicatedAt(), ZoneOffset.UTC))
        .param("note", c.note() == null || c.note().isBlank() ? null : c.note().trim())
        .update();
  }

  Optional<CriticalRow> critical(UUID report) {
    return jdbc.sql(CRITICAL + " WHERE c.report_id = :r")
        .param("r", report)
        .query(this::mapCritical)
        .optional();
  }

  List<CriticalRow> openCritical() {
    return jdbc.sql(CRITICAL + " WHERE c.acknowledged_at IS NULL ORDER BY c.flagged_at LIMIT 300")
        .query(this::mapCritical)
        .list();
  }

  int acknowledge(UUID report, long version, UUID by, String note) {
    return jdbc.sql(
            """
            UPDATE critical_results SET acknowledged_by = :by, acknowledged_at = now(), acknowledgement_note = :note,
                version = version + 1
            WHERE report_id = :r AND version = :v AND acknowledged_at IS NULL
            """)
        .param("by", by)
        .param("note", note)
        .param("r", report)
        .param("v", version)
        .update();
  }

  private ReportRow mapReport(ResultSet rs, int i) throws SQLException {
    return new ReportRow(
        rs.getObject("id", UUID.class),
        rs.getObject("facility_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        ReportStatus.valueOf(rs.getString("status")),
        rs.getString("technique"),
        rs.getString("comparison"),
        rs.getString("findings"),
        rs.getString("impression"),
        rs.getBoolean("critical"),
        new UserRef(rs.getObject("author_id", UUID.class), rs.getString("author_name")),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        instant(rs, "signed_at"),
        rs.getObject("amends_report_id", UUID.class),
        rs.getString("amendment_reason"),
        rs.getLong("version"));
  }

  private CriticalRow mapCritical(ResultSet rs, int i) throws SQLException {
    UUID ack = rs.getObject("acknowledged_by", UUID.class);
    return new CriticalRow(
        rs.getObject("report_id", UUID.class),
        rs.getObject("facility_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        new UserRef(rs.getObject("flagged_by", UUID.class), rs.getString("flagger")),
        instant(rs, "flagged_at"),
        rs.getString("communicated_to"),
        CommunicationMethod.valueOf(rs.getString("communication_method")),
        instant(rs, "communicated_at"),
        rs.getString("communication_note"),
        ack == null ? null : new UserRef(ack, rs.getString("acknowledger")),
        instant(rs, "acknowledged_at"),
        rs.getString("acknowledgement_note"),
        rs.getLong("version"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
