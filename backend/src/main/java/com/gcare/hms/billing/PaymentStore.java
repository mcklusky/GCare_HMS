package com.gcare.hms.billing;

import com.gcare.hms.billing.BillingApi.ServiceStatus;
import com.gcare.hms.billing.PaymentApi.CountView;
import com.gcare.hms.billing.PaymentApi.MethodRef;
import com.gcare.hms.billing.PaymentApi.MethodTotal;
import com.gcare.hms.billing.PaymentApi.MethodView;
import com.gcare.hms.billing.PaymentApi.PaymentView;
import com.gcare.hms.billing.PaymentApi.ShiftStatus;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Payment, receipt and shift persistence. Row-level security limits statements to the organization.
 * Payments and cash counts are insert-only (grants); facility names are a read-only join for
 * receipts.
 */
@Repository
class PaymentStore {

  record ShiftRow(
      UUID id,
      UUID facilityId,
      UserRef cashier,
      ShiftStatus status,
      Instant openedAt,
      Instant closedAt,
      String closeNote,
      UserRef reviewedBy,
      Instant reviewedAt,
      String reviewNote,
      long version) {}

  record NewPayment(
      UUID id,
      UUID org,
      UUID facilityId,
      UUID invoiceId,
      UUID shiftId,
      MethodView method,
      String currency,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String reference,
      String receiptNumber,
      UUID idempotencyKey,
      UUID receivedBy) {}

  private static final String SELECT_SHIFT =
      """
      SELECT s.*, cu.display_name AS cashier_name, ru.display_name AS reviewer_name
      FROM cashier_shifts s
      JOIN users cu ON cu.id = s.cashier_id
      LEFT JOIN users ru ON ru.id = s.reviewed_by
      """;

  private static final String SELECT_PAYMENT =
      """
      SELECT p.*, m.code AS m_code, m.name AS m_name, u.display_name AS receiver
      FROM payments p
      JOIN payment_methods m ON m.id = p.method_id
      JOIN users u ON u.id = p.received_by
      """;

  private final JdbcClient jdbc;

  PaymentStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // --- methods ---------------------------------------------------------------------------------

  List<MethodView> methods() {
    return jdbc.sql("SELECT * FROM payment_methods ORDER BY name, code")
        .query(PaymentStore::methodRow)
        .list();
  }

  Optional<MethodView> method(UUID id) {
    return jdbc.sql("SELECT * FROM payment_methods WHERE id = :id")
        .param("id", id)
        .query(PaymentStore::methodRow)
        .optional();
  }

  void insertMethod(UUID id, UUID org, String code, String name, boolean cash, UUID by) {
    jdbc.sql(
            """
            INSERT INTO payment_methods (id, organization_id, code, name, is_cash, status, created_by)
            VALUES (:id, :org, :code, :name, :cash, 'ACTIVE', :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("code", code)
        .param("name", name)
        .param("cash", cash)
        .param("by", by)
        .update();
  }

  int updateMethod(UUID id, long version, String name, ServiceStatus status) {
    return jdbc.sql(
            """
            UPDATE payment_methods SET name = :name, status = :status, updated_at = now(), version = version + 1
            WHERE id = :id AND version = :version
            """)
        .param("name", name)
        .param("status", status.name())
        .param("id", id)
        .param("version", version)
        .update();
  }

  // --- shifts ----------------------------------------------------------------------------------

  void insertShift(UUID id, UUID org, UUID facility, UUID cashier) {
    jdbc.sql(
            """
            INSERT INTO cashier_shifts (id, organization_id, facility_id, cashier_id, status)
            VALUES (:id, :org, :facility, :cashier, 'OPEN')
            """)
        .param("id", id)
        .param("org", org)
        .param("facility", facility)
        .param("cashier", cashier)
        .update();
  }

  Optional<ShiftRow> shift(UUID id) {
    return jdbc.sql(SELECT_SHIFT + " WHERE s.id = :id")
        .param("id", id)
        .query(PaymentStore::shiftRow)
        .optional();
  }

  /** Locks the shift row until the transaction ends (payments and closing take this lock). */
  Optional<ShiftRow> lockShift(UUID id) {
    return jdbc.sql(SELECT_SHIFT + " WHERE s.id = :id FOR UPDATE OF s")
        .param("id", id)
        .query(PaymentStore::shiftRow)
        .optional();
  }

  Optional<UUID> openShiftOf(UUID cashier) {
    return jdbc.sql("SELECT id FROM cashier_shifts WHERE cashier_id = :c AND status = 'OPEN'")
        .param("c", cashier)
        .query(UUID.class)
        .optional();
  }

  List<ShiftRow> shiftsOf(UUID cashier, int limit) {
    return jdbc.sql(
            SELECT_SHIFT + " WHERE s.cashier_id = :c ORDER BY s.opened_at DESC LIMIT :limit")
        .param("c", cashier)
        .param("limit", limit)
        .query(PaymentStore::shiftRow)
        .list();
  }

  List<ShiftRow> shiftsWithStatus(
      Set<UUID> facilities, boolean all, ShiftStatus status, int limit) {
    if (!all && facilities.isEmpty()) {
      return List.of();
    }
    var sql =
        jdbc.sql(
                SELECT_SHIFT
                    + " WHERE s.status = :st"
                    + (all ? "" : " AND s.facility_id IN (:facilities)")
                    + " ORDER BY s.closed_at, s.opened_at LIMIT :limit")
            .param("st", status.name())
            .param("limit", limit);
    if (!all) {
      sql = sql.param("facilities", facilities);
    }
    return sql.query(PaymentStore::shiftRow).list();
  }

  List<MethodTotal> totals(UUID shiftId) {
    return jdbc.sql(
            """
            SELECT m.code, m.name, p.method_is_cash, p.currency, sum(p.amount) AS amount, count(*) AS n
            FROM payments p JOIN payment_methods m ON m.id = p.method_id
            WHERE p.shift_id = :s
            GROUP BY m.code, m.name, p.method_is_cash, p.currency
            ORDER BY m.name, p.currency
            """)
        .param("s", shiftId)
        .query((rs, i) ->
            new MethodTotal(
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("method_is_cash"),
                rs.getString("currency"),
                decimal(rs, "amount"),
                rs.getInt("n")))
        .list();
  }

  List<CountView> counts(UUID shiftId) {
    return jdbc.sql("SELECT * FROM shift_cash_counts WHERE shift_id = :s ORDER BY currency")
        .param("s", shiftId)
        .query((rs, i) ->
            new CountView(
                rs.getString("currency"),
                decimal(rs, "expected"),
                decimal(rs, "counted"),
                decimal(rs, "difference")))
        .list();
  }

  void insertCount(UUID org, UUID shiftId, CountView count) {
    jdbc.sql(
            """
            INSERT INTO shift_cash_counts (organization_id, shift_id, currency, expected, counted, difference)
            VALUES (:org, :s, :c, :expected, :counted, :difference)
            """)
        .param("org", org)
        .param("s", shiftId)
        .param("c", count.currency())
        .param("expected", count.expected())
        .param("counted", count.counted())
        .param("difference", count.difference())
        .update();
  }

  int closeShift(UUID id, long version, String note) {
    return jdbc.sql(
            """
            UPDATE cashier_shifts SET status = 'CLOSED', closed_at = now(), close_note = :note,
                version = version + 1
            WHERE id = :id AND version = :version AND status = 'OPEN'
            """)
        .param("note", note)
        .param("id", id)
        .param("version", version)
        .update();
  }

  int reviewShift(UUID id, long version, String note, UUID by) {
    return jdbc.sql(
            """
            UPDATE cashier_shifts SET status = 'REVIEWED', reviewed_by = :by, reviewed_at = now(),
                review_note = :note, version = version + 1
            WHERE id = :id AND version = :version AND status = 'CLOSED'
            """)
        .param("by", by)
        .param("note", note)
        .param("id", id)
        .param("version", version)
        .update();
  }

  // --- payments --------------------------------------------------------------------------------

  /** Locks the invoice row so concurrent payments cannot together exceed the balance. */
  void lockInvoice(UUID invoiceId) {
    jdbc.sql("SELECT id FROM invoices WHERE id = :id FOR UPDATE")
        .param("id", invoiceId)
        .query(UUID.class)
        .optional();
  }

  BigDecimal paidTotal(UUID invoiceId) {
    return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM payments WHERE invoice_id = :i")
        .param("i", invoiceId)
        .query(BigDecimal.class)
        .single();
  }

  long nextReceiptNumber(UUID org) {
    return jdbc.sql(
            """
            INSERT INTO receipt_counters (organization_id, last_number) VALUES (:org, 1)
            ON CONFLICT (organization_id) DO UPDATE SET last_number = receipt_counters.last_number + 1
            RETURNING last_number
            """)
        .param("org", org)
        .query(Long.class)
        .single();
  }

  void insertPayment(NewPayment p) {
    jdbc.sql(
            """
            INSERT INTO payments (id, organization_id, facility_id, invoice_id, shift_id, method_id, method_is_cash,
                currency, amount, balance_after, reference, receipt_number, idempotency_key, received_by)
            VALUES (:id, :org, :facility, :invoice, :shift, :method, :cash, :currency, :amount, :balance,
                :reference, :receipt, :key, :by)
            """)
        .param("id", p.id())
        .param("org", p.org())
        .param("facility", p.facilityId())
        .param("invoice", p.invoiceId())
        .param("shift", p.shiftId())
        .param("method", p.method().id())
        .param("cash", p.method().isCash())
        .param("currency", p.currency())
        .param("amount", p.amount())
        .param("balance", p.balanceAfter())
        .param("reference", p.reference())
        .param("receipt", p.receiptNumber())
        .param("key", p.idempotencyKey())
        .param("by", p.receivedBy())
        .update();
  }

  Optional<PaymentView> payment(UUID id) {
    return jdbc.sql(SELECT_PAYMENT + " WHERE p.id = :id")
        .param("id", id)
        .query(PaymentStore::paymentRow)
        .optional();
  }

  Optional<UUID> paymentFacility(UUID id) {
    return jdbc.sql("SELECT facility_id FROM payments WHERE id = :id")
        .param("id", id)
        .query(UUID.class)
        .optional();
  }

  Optional<PaymentView> byKey(UUID key) {
    return jdbc.sql(SELECT_PAYMENT + " WHERE p.idempotency_key = :k")
        .param("k", key)
        .query(PaymentStore::paymentRow)
        .optional();
  }

  List<PaymentView> paymentsOf(UUID invoiceId) {
    return jdbc.sql(SELECT_PAYMENT + " WHERE p.invoice_id = :i ORDER BY p.received_at, p.id")
        .param("i", invoiceId)
        .query(PaymentStore::paymentRow)
        .list();
  }

  String facilityName(UUID facilityId) {
    return jdbc.sql("SELECT name FROM facilities WHERE id = :id")
        .param("id", facilityId)
        .query(String.class)
        .single();
  }

  // --- mapping ---------------------------------------------------------------------------------

  private static MethodView methodRow(ResultSet rs, int i) throws SQLException {
    return new MethodView(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getBoolean("is_cash"),
        ServiceStatus.valueOf(rs.getString("status")),
        rs.getLong("version"));
  }

  private static ShiftRow shiftRow(ResultSet rs, int i) throws SQLException {
    UUID reviewer = rs.getObject("reviewed_by", UUID.class);
    return new ShiftRow(
        rs.getObject("id", UUID.class),
        rs.getObject("facility_id", UUID.class),
        new UserRef(rs.getObject("cashier_id", UUID.class), rs.getString("cashier_name")),
        ShiftStatus.valueOf(rs.getString("status")),
        instant(rs, "opened_at"),
        instant(rs, "closed_at"),
        rs.getString("close_note"),
        reviewer == null ? null : new UserRef(reviewer, rs.getString("reviewer_name")),
        instant(rs, "reviewed_at"),
        rs.getString("review_note"),
        rs.getLong("version"));
  }

  private static PaymentView paymentRow(ResultSet rs, int i) throws SQLException {
    return new PaymentView(
        rs.getObject("id", UUID.class),
        rs.getObject("invoice_id", UUID.class),
        rs.getObject("shift_id", UUID.class),
        new MethodRef(
            rs.getObject("method_id", UUID.class),
            rs.getString("m_code"),
            rs.getString("m_name"),
            rs.getBoolean("method_is_cash")),
        rs.getString("currency"),
        decimal(rs, "amount"),
        decimal(rs, "balance_after"),
        rs.getString("reference"),
        rs.getString("receipt_number"),
        new UserRef(rs.getObject("received_by", UUID.class), rs.getString("receiver")),
        instant(rs, "received_at"));
  }

  /** Stored with 4 decimals; returned without trailing zeros (the browser pads for display). */
  static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
    return strip(rs.getBigDecimal(column));
  }

  static BigDecimal strip(BigDecimal value) {
    if (value == null) {
      return null;
    }
    BigDecimal stripped = value.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
