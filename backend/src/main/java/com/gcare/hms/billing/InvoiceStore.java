package com.gcare.hms.billing;

import com.gcare.hms.billing.InvoiceApi.BillableVisit;
import com.gcare.hms.billing.InvoiceApi.EncounterRef;
import com.gcare.hms.billing.InvoiceApi.InvoiceStatus;
import com.gcare.hms.billing.InvoiceApi.LineSource;
import com.gcare.hms.billing.InvoiceApi.LineStatus;
import com.gcare.hms.billing.InvoiceApi.LineView;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Invoice persistence. Row-level security limits statements to the organization. Issued invoices
 * and the lines of non-draft invoices cannot change (database triggers and column grants). Reads of
 * encounters, patients, departments and orders are read-only joins for display (docs/18 §6).
 */
@Repository
class InvoiceStore {

  record VisitRow(UUID id, UUID facilityId, UUID patientId, String status) {}

  record InvoiceRow(
      UUID id,
      String invoiceNumber,
      InvoiceStatus status,
      String currency,
      UUID facilityId,
      EncounterRef encounter,
      PatientSummary patient,
      BigDecimal subtotal,
      BigDecimal taxTotal,
      BigDecimal total,
      UUID replacesInvoiceId,
      UserRef createdBy,
      Instant createdAt,
      UserRef issuedBy,
      Instant issuedAt,
      String voidReason,
      UserRef voidedBy,
      Instant voidedAt,
      long version) {}

  /** An order of the visit that has an active billable service and is not on the invoice. */
  record OrderToBill(
      UUID orderId, String itemName, UUID serviceId, String serviceCode, String serviceName) {}

  private static final String PATIENT_COLUMNS =
      """
      p.patient_number, p.status AS p_status, p.family_name, p.given_names, p.birth_date,
      p.birth_date_estimated, p.sex, p.details_incomplete
      """;

  private static final String SELECT_INVOICE =
      "SELECT i.*, e.status AS e_status, e.arrived_at AS e_arrived, d.name AS d_name, "
          + PATIENT_COLUMNS
          + """
          , cu.display_name AS creator, iu.display_name AS issuer, vu.display_name AS voider
          FROM invoices i
          JOIN encounters e ON e.id = i.encounter_id
          JOIN departments d ON d.id = e.department_id
          JOIN patients p ON p.id = i.patient_id
          JOIN users cu ON cu.id = i.created_by
          LEFT JOIN users iu ON iu.id = i.issued_by
          LEFT JOIN users vu ON vu.id = i.voided_by
          """;

  private static final String SELECT_LINE =
      """
      SELECT l.*, o.status AS o_status, au.display_name AS adder, ru.display_name AS remover
      FROM invoice_lines l
      JOIN users au ON au.id = l.added_by
      LEFT JOIN users ru ON ru.id = l.removed_by
      LEFT JOIN clinical_orders o ON o.id = l.order_id
      """;

  private final JdbcClient jdbc;

  InvoiceStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // --- visits ----------------------------------------------------------------------------------

  Optional<VisitRow> visit(UUID encounterId) {
    return jdbc.sql("SELECT id, facility_id, patient_id, status FROM encounters WHERE id = :id")
        .param("id", encounterId)
        .query((rs, i) ->
            new VisitRow(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("status")))
        .optional();
  }

  /** Visits since {@code since} that are not cancelled and have no draft or issued invoice. */
  List<BillableVisit> billableVisits(Set<UUID> facilities, boolean all, Instant since, int limit) {
    if (!all && facilities.isEmpty()) {
      return List.of();
    }
    String facilityFilter = all ? "" : " AND e.facility_id IN (:facilities)";
    var sql =
        jdbc.sql(
                "SELECT e.id, e.facility_id, e.patient_id, e.status AS e_status, e.arrived_at, d.name AS d_name, "
                    + PATIENT_COLUMNS
                    + """
                    FROM encounters e
                    JOIN departments d ON d.id = e.department_id
                    JOIN patients p ON p.id = e.patient_id
                    WHERE e.status <> 'CANCELLED' AND e.arrived_at >= :since
                      AND NOT EXISTS (SELECT 1 FROM invoices i WHERE i.encounter_id = e.id
                                      AND i.status IN ('DRAFT', 'ISSUED'))
                    """
                    + facilityFilter
                    + " ORDER BY e.arrived_at DESC LIMIT :limit")
            .param("since", OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC))
            .param("limit", limit);
    if (!all) {
      sql = sql.param("facilities", facilities);
    }
    return sql.query((rs, i) ->
            new BillableVisit(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                patient(rs, "patient_id"),
                rs.getString("d_name"),
                rs.getString("e_status"),
                instant(rs, "arrived_at")))
        .list();
  }

  /**
   * Orders of the visit that are not cancelled, whose orderable item is linked to an active
   * billable service, and that have no line (active or removed) on this invoice.
   */
  List<OrderToBill> ordersToBill(UUID invoiceId, UUID encounterId) {
    return jdbc.sql(
            """
            SELECT o.id, o.item_name, s.id AS s_id, s.code AS s_code, s.name AS s_name
            FROM clinical_orders o
            JOIN billable_services s ON s.order_catalog_item_id = o.catalog_item_id AND s.status = 'ACTIVE'
            WHERE o.encounter_id = :e AND o.status <> 'CANCELLED'
              AND NOT EXISTS (SELECT 1 FROM invoice_lines l WHERE l.invoice_id = :inv AND l.order_id = o.id)
            ORDER BY o.requested_at, o.id
            """)
        .param("e", encounterId)
        .param("inv", invoiceId)
        .query((rs, i) ->
            new OrderToBill(
                rs.getObject("id", UUID.class),
                rs.getString("item_name"),
                rs.getObject("s_id", UUID.class),
                rs.getString("s_code"),
                rs.getString("s_name")))
        .list();
  }

  // --- invoices --------------------------------------------------------------------------------

  /** The most recent void invoice of the visit that no other invoice replaces yet. */
  Optional<UUID> unreplacedVoid(UUID encounterId) {
    return jdbc.sql(
            """
            SELECT v.id FROM invoices v
            WHERE v.encounter_id = :e AND v.status = 'VOID' AND v.invoice_number IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM invoices n WHERE n.replaces_invoice_id = v.id)
            ORDER BY v.voided_at DESC LIMIT 1
            """)
        .param("e", encounterId)
        .query(UUID.class)
        .optional();
  }

  void insertInvoice(
      UUID id, UUID org, VisitRow visit, String currency, UUID replaces, UUID createdBy) {
    jdbc.sql(
            """
            INSERT INTO invoices (id, organization_id, facility_id, encounter_id, patient_id, currency,
                status, replaces_invoice_id, created_by)
            VALUES (:id, :org, :facility, :encounter, :patient, :currency, 'DRAFT', :replaces, :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("facility", visit.facilityId())
        .param("encounter", visit.id())
        .param("patient", visit.patientId())
        .param("currency", currency)
        .param("replaces", replaces)
        .param("by", createdBy)
        .update();
  }

  Optional<InvoiceRow> invoice(UUID id) {
    return jdbc.sql(SELECT_INVOICE + " WHERE i.id = :id")
        .param("id", id)
        .query(InvoiceStore::invoiceRow)
        .optional();
  }

  List<InvoiceRow> invoicesOfVisit(UUID encounterId) {
    return jdbc.sql(SELECT_INVOICE + " WHERE i.encounter_id = :e ORDER BY i.created_at DESC")
        .param("e", encounterId)
        .query(InvoiceStore::invoiceRow)
        .list();
  }

  List<InvoiceRow> invoices(
      Set<UUID> facilities, boolean all, Collection<InvoiceStatus> statuses, int limit) {
    if (!all && facilities.isEmpty()) {
      return List.of();
    }
    var sql =
        jdbc.sql(
                SELECT_INVOICE
                    + " WHERE i.status IN (:statuses)"
                    + (all ? "" : " AND i.facility_id IN (:facilities)")
                    + " ORDER BY i.created_at DESC LIMIT :limit")
            .param("statuses", statuses.stream().map(Enum::name).toList())
            .param("limit", limit);
    if (!all) {
      sql = sql.param("facilities", facilities);
    }
    return sql.query(InvoiceStore::invoiceRow).list();
  }

  /** Bumps the version of a draft; 0 rows means a stale version or not a draft. */
  int touchDraft(UUID id, long version) {
    return jdbc.sql(
            """
            UPDATE invoices SET version = version + 1, updated_at = now()
            WHERE id = :id AND version = :version AND status = 'DRAFT'
            """)
        .param("id", id)
        .param("version", version)
        .update();
  }

  /** Next gapless number for the organization (row lock until the transaction ends). */
  long nextNumber(UUID org) {
    return jdbc.sql(
            """
            INSERT INTO invoice_counters (organization_id, last_number) VALUES (:org, 1)
            ON CONFLICT (organization_id) DO UPDATE SET last_number = invoice_counters.last_number + 1
            RETURNING last_number
            """)
        .param("org", org)
        .query(Long.class)
        .single();
  }

  int issue(
      UUID id,
      long version,
      String number,
      BigDecimal subtotal,
      BigDecimal tax,
      BigDecimal total,
      UUID by) {
    return jdbc.sql(
            """
            UPDATE invoices SET status = 'ISSUED', invoice_number = :number, subtotal = :subtotal,
                tax_total = :tax, total = :total, issued_by = :by, issued_at = now(),
                updated_at = now(), version = version + 1
            WHERE id = :id AND version = :version AND status = 'DRAFT'
            """)
        .param("number", number)
        .param("subtotal", subtotal)
        .param("tax", tax)
        .param("total", total)
        .param("by", by)
        .param("id", id)
        .param("version", version)
        .update();
  }

  int voidInvoice(UUID id, long version, InvoiceStatus from, String reason, UUID by) {
    return jdbc.sql(
            """
            UPDATE invoices SET status = 'VOID', void_reason = :reason, voided_by = :by, voided_at = now(),
                updated_at = now(), version = version + 1
            WHERE id = :id AND version = :version AND status = :from
            """)
        .param("reason", reason)
        .param("by", by)
        .param("id", id)
        .param("version", version)
        .param("from", from.name())
        .update();
  }

  // --- lines -----------------------------------------------------------------------------------

  List<LineView> lines(UUID invoiceId) {
    return jdbc.sql(SELECT_LINE + " WHERE l.invoice_id = :inv ORDER BY l.added_at, l.id")
        .param("inv", invoiceId)
        .query(InvoiceStore::lineRow)
        .list();
  }

  Optional<LineView> line(UUID invoiceId, UUID lineId) {
    return jdbc.sql(SELECT_LINE + " WHERE l.invoice_id = :inv AND l.id = :id")
        .param("inv", invoiceId)
        .param("id", lineId)
        .query(InvoiceStore::lineRow)
        .optional();
  }

  record NewLine(
      UUID id,
      UUID org,
      UUID invoiceId,
      LineSource source,
      UUID orderId,
      UUID serviceId,
      String serviceCode,
      String serviceName,
      UUID priceId,
      int quantity,
      BigDecimal unitPrice,
      String taxRateCode,
      BigDecimal taxRatePercent,
      InvoiceMath.LineAmounts amounts,
      String reason,
      UUID addedBy) {}

  void insertLine(NewLine l) {
    jdbc.sql(
            """
            INSERT INTO invoice_lines (id, organization_id, invoice_id, source, order_id, service_id,
                service_code, service_name, price_id, quantity, unit_price, tax_rate_code, tax_rate_percent,
                net_amount, tax_amount, line_total, status, reason, added_by)
            VALUES (:id, :org, :inv, :source, :order, :service, :code, :name, :price, :qty, :unit, :taxCode,
                :taxPercent, :net, :tax, :total, 'ACTIVE', :reason, :by)
            """)
        .param("id", l.id())
        .param("org", l.org())
        .param("inv", l.invoiceId())
        .param("source", l.source().name())
        .param("order", l.orderId())
        .param("service", l.serviceId())
        .param("code", l.serviceCode())
        .param("name", l.serviceName())
        .param("price", l.priceId())
        .param("qty", l.quantity())
        .param("unit", l.unitPrice())
        .param("taxCode", l.taxRateCode())
        .param("taxPercent", l.taxRatePercent())
        .param("net", l.amounts().net())
        .param("tax", l.amounts().tax())
        .param("total", l.amounts().total())
        .param("reason", l.reason())
        .param("by", l.addedBy())
        .update();
  }

  int removeLine(UUID invoiceId, UUID lineId, String reason, UUID by) {
    return jdbc.sql(
            """
            UPDATE invoice_lines SET status = 'REMOVED', removed_reason = :reason, removed_by = :by,
                removed_at = now()
            WHERE invoice_id = :inv AND id = :id AND status = 'ACTIVE'
            """)
        .param("reason", reason)
        .param("by", by)
        .param("inv", invoiceId)
        .param("id", lineId)
        .update();
  }

  // --- mapping ---------------------------------------------------------------------------------

  private static InvoiceRow invoiceRow(ResultSet rs, int i) throws SQLException {
    return new InvoiceRow(
        rs.getObject("id", UUID.class),
        rs.getString("invoice_number"),
        InvoiceStatus.valueOf(rs.getString("status")),
        rs.getString("currency"),
        rs.getObject("facility_id", UUID.class),
        new EncounterRef(
            rs.getObject("encounter_id", UUID.class),
            rs.getString("e_status"),
            rs.getString("d_name"),
            instant(rs, "e_arrived")),
        patient(rs, "patient_id"),
        decimal(rs, "subtotal"),
        decimal(rs, "tax_total"),
        decimal(rs, "total"),
        rs.getObject("replaces_invoice_id", UUID.class),
        new UserRef(rs.getObject("created_by", UUID.class), rs.getString("creator")),
        instant(rs, "created_at"),
        user(rs, "issued_by", "issuer"),
        instant(rs, "issued_at"),
        rs.getString("void_reason"),
        user(rs, "voided_by", "voider"),
        instant(rs, "voided_at"),
        rs.getLong("version"));
  }

  private static LineView lineRow(ResultSet rs, int i) throws SQLException {
    return new LineView(
        rs.getObject("id", UUID.class),
        LineSource.valueOf(rs.getString("source")),
        rs.getObject("order_id", UUID.class),
        rs.getString("o_status"),
        rs.getObject("service_id", UUID.class),
        rs.getString("service_code"),
        rs.getString("service_name"),
        rs.getInt("quantity"),
        decimal(rs, "unit_price"),
        rs.getString("tax_rate_code"),
        decimal(rs, "tax_rate_percent"),
        decimal(rs, "net_amount"),
        decimal(rs, "tax_amount"),
        decimal(rs, "line_total"),
        LineStatus.valueOf(rs.getString("status")),
        rs.getString("reason"),
        new UserRef(rs.getObject("added_by", UUID.class), rs.getString("adder")),
        instant(rs, "added_at"),
        rs.getString("removed_reason"),
        user(rs, "removed_by", "remover"),
        instant(rs, "removed_at"));
  }

  private static PatientSummary patient(ResultSet rs, String idColumn) throws SQLException {
    return new PatientSummary(
        rs.getObject(idColumn, UUID.class),
        rs.getString("patient_number"),
        PatientStatus.valueOf(rs.getString("p_status")),
        rs.getString("family_name"),
        rs.getString("given_names"),
        rs.getObject("birth_date", LocalDate.class),
        rs.getBoolean("birth_date_estimated"),
        Sex.valueOf(rs.getString("sex")),
        rs.getBoolean("details_incomplete"));
  }

  /** Stored with 4 decimals; returned without trailing zeros (the browser pads for display). */
  private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
    BigDecimal value = rs.getBigDecimal(column);
    if (value == null) {
      return null;
    }
    BigDecimal stripped = value.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }

  private static UserRef user(ResultSet rs, String idColumn, String nameColumn)
      throws SQLException {
    UUID id = rs.getObject(idColumn, UUID.class);
    return id == null ? null : new UserRef(id, rs.getString(nameColumn));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
