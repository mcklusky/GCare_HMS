package com.gcare.hms.billing;

import static com.gcare.hms.billing.PaymentStore.decimal;

import com.gcare.hms.billing.AdjustmentApi.ExemptionStatus;
import com.gcare.hms.billing.AdjustmentApi.ExemptionView;
import com.gcare.hms.billing.AdjustmentApi.ReasonView;
import com.gcare.hms.billing.AdjustmentApi.RefundStatus;
import com.gcare.hms.billing.AdjustmentApi.RefundView;
import com.gcare.hms.billing.BillingApi.ServiceStatus;
import com.gcare.hms.billing.PaymentApi.MethodRef;
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
 * Refund and exemption persistence. Row-level security limits statements to the organization;
 * decided exemptions and paid refunds never change (database triggers and column grants).
 */
@Repository
class AdjustmentStore {

  private static final String SELECT_REFUND =
      """
      SELECT r.*, i.invoice_number, p.receipt_number AS payment_receipt, m.code AS m_code, m.name AS m_name,
             qu.display_name AS requester, du.display_name AS decider, pu.display_name AS payer
      FROM refunds r
      JOIN invoices i ON i.id = r.invoice_id
      JOIN payments p ON p.id = r.payment_id
      JOIN payment_methods m ON m.id = r.method_id
      JOIN users qu ON qu.id = r.requested_by
      LEFT JOIN users du ON du.id = r.decided_by
      LEFT JOIN users pu ON pu.id = r.paid_by
      """;

  private static final String SELECT_EXEMPTION =
      """
      SELECT x.*, i.invoice_number, er.code AS r_code, er.name AS r_name, er.status AS r_status,
             er.version AS r_version, qu.display_name AS requester, du.display_name AS decider
      FROM exemptions x
      JOIN invoices i ON i.id = x.invoice_id
      JOIN exemption_reasons er ON er.id = x.reason_id
      JOIN users qu ON qu.id = x.requested_by
      LEFT JOIN users du ON du.id = x.decided_by
      """;

  private final JdbcClient jdbc;

  AdjustmentStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // --- reasons ---------------------------------------------------------------------------------

  List<ReasonView> reasons() {
    return jdbc.sql("SELECT * FROM exemption_reasons ORDER BY name, code")
        .query(AdjustmentStore::reasonRow)
        .list();
  }

  Optional<ReasonView> reason(UUID id) {
    return jdbc.sql("SELECT * FROM exemption_reasons WHERE id = :id")
        .param("id", id)
        .query(AdjustmentStore::reasonRow)
        .optional();
  }

  void insertReason(UUID id, UUID org, String code, String name, UUID by) {
    jdbc.sql(
            """
            INSERT INTO exemption_reasons (id, organization_id, code, name, status, created_by)
            VALUES (:id, :org, :code, :name, 'ACTIVE', :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("code", code)
        .param("name", name)
        .param("by", by)
        .update();
  }

  int updateReason(UUID id, long version, String name, ServiceStatus status) {
    return jdbc.sql(
            """
            UPDATE exemption_reasons SET name = :name, status = :status, updated_at = now(), version = version + 1
            WHERE id = :id AND version = :version
            """)
        .param("name", name)
        .param("status", status.name())
        .param("id", id)
        .param("version", version)
        .update();
  }

  // --- refunds ---------------------------------------------------------------------------------

  /** Requested, approved and paid refunds of a payment (what can no longer be refunded). */
  BigDecimal committedRefunds(UUID paymentId) {
    return jdbc.sql(
            "SELECT coalesce(sum(amount), 0) FROM refunds WHERE payment_id = :p AND status <> 'REJECTED'")
        .param("p", paymentId)
        .query(BigDecimal.class)
        .single();
  }

  void insertRefund(
      UUID id,
      UUID org,
      PaymentApi.PaymentView payment,
      UUID facility,
      BigDecimal amount,
      String reason,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO refunds (id, organization_id, facility_id, invoice_id, payment_id, method_id, method_is_cash,
                currency, amount, reason, status, requested_by)
            VALUES (:id, :org, :facility, :invoice, :payment, :method, :cash, :currency, :amount, :reason,
                'REQUESTED', :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("facility", facility)
        .param("invoice", payment.invoiceId())
        .param("payment", payment.id())
        .param("method", payment.method().id())
        .param("cash", payment.method().isCash())
        .param("currency", payment.currency())
        .param("amount", amount)
        .param("reason", reason)
        .param("by", by)
        .update();
  }

  Optional<RefundView> refund(UUID id) {
    return jdbc.sql(SELECT_REFUND + " WHERE r.id = :id")
        .param("id", id)
        .query(AdjustmentStore::refundRow)
        .optional();
  }

  List<RefundView> refundsOf(UUID invoiceId) {
    return jdbc.sql(SELECT_REFUND + " WHERE r.invoice_id = :i ORDER BY r.requested_at, r.id")
        .param("i", invoiceId)
        .query(AdjustmentStore::refundRow)
        .list();
  }

  List<RefundView> refundsWithStatus(
      Set<UUID> facilities, boolean all, RefundStatus status, int limit) {
    if (!all && facilities.isEmpty()) {
      return List.of();
    }
    var sql =
        jdbc.sql(
                SELECT_REFUND
                    + " WHERE r.status = :st"
                    + (all ? "" : " AND r.facility_id IN (:facilities)")
                    + " ORDER BY r.requested_at LIMIT :limit")
            .param("st", status.name())
            .param("limit", limit);
    if (!all) {
      sql = sql.param("facilities", facilities);
    }
    return sql.query(AdjustmentStore::refundRow).list();
  }

  int decideRefund(UUID id, long version, RefundStatus status, String note, UUID by) {
    return jdbc.sql(
            """
            UPDATE refunds SET status = :st, decision_note = :note, decided_by = :by, decided_at = now(),
                version = version + 1
            WHERE id = :id AND version = :version AND status = 'REQUESTED'
            """)
        .param("st", status.name())
        .param("note", note)
        .param("by", by)
        .param("id", id)
        .param("version", version)
        .update();
  }

  Optional<RefundView> refundByPayoutKey(UUID key) {
    return jdbc.sql(SELECT_REFUND + " WHERE r.payout_key = :k")
        .param("k", key)
        .query(AdjustmentStore::refundRow)
        .optional();
  }

  int payRefund(
      UUID id,
      long version,
      UUID shift,
      String receipt,
      UUID key,
      BigDecimal balanceAfter,
      UUID by) {
    return jdbc.sql(
            """
            UPDATE refunds SET status = 'PAID', shift_id = :shift, paid_by = :by, paid_at = now(),
                receipt_number = :receipt, payout_key = :key, balance_after = :balance, version = version + 1
            WHERE id = :id AND version = :version AND status = 'APPROVED'
            """)
        .param("shift", shift)
        .param("balance", balanceAfter)
        .param("by", by)
        .param("receipt", receipt)
        .param("key", key)
        .param("id", id)
        .param("version", version)
        .update();
  }

  // --- exemptions ------------------------------------------------------------------------------

  /** Requested and approved exemptions of an invoice. */
  BigDecimal committedExemptions(UUID invoiceId) {
    return jdbc.sql(
            "SELECT coalesce(sum(amount), 0) FROM exemptions WHERE invoice_id = :i AND status <> 'REJECTED'")
        .param("i", invoiceId)
        .query(BigDecimal.class)
        .single();
  }

  void insertExemption(
      UUID id,
      UUID org,
      UUID facility,
      UUID invoice,
      UUID reason,
      String currency,
      BigDecimal amount,
      String note,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO exemptions (id, organization_id, facility_id, invoice_id, reason_id, currency, amount, note,
                status, requested_by)
            VALUES (:id, :org, :facility, :invoice, :reason, :currency, :amount, :note, 'REQUESTED', :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("facility", facility)
        .param("invoice", invoice)
        .param("reason", reason)
        .param("currency", currency)
        .param("amount", amount)
        .param("note", note)
        .param("by", by)
        .update();
  }

  Optional<ExemptionView> exemption(UUID id) {
    return jdbc.sql(SELECT_EXEMPTION + " WHERE x.id = :id")
        .param("id", id)
        .query(AdjustmentStore::exemptionRow)
        .optional();
  }

  List<ExemptionView> exemptionsOf(UUID invoiceId) {
    return jdbc.sql(SELECT_EXEMPTION + " WHERE x.invoice_id = :i ORDER BY x.requested_at, x.id")
        .param("i", invoiceId)
        .query(AdjustmentStore::exemptionRow)
        .list();
  }

  List<ExemptionView> exemptionsWithStatus(
      Set<UUID> facilities, boolean all, ExemptionStatus status, int limit) {
    if (!all && facilities.isEmpty()) {
      return List.of();
    }
    var sql =
        jdbc.sql(
                SELECT_EXEMPTION
                    + " WHERE x.status = :st"
                    + (all ? "" : " AND x.facility_id IN (:facilities)")
                    + " ORDER BY x.requested_at LIMIT :limit")
            .param("st", status.name())
            .param("limit", limit);
    if (!all) {
      sql = sql.param("facilities", facilities);
    }
    return sql.query(AdjustmentStore::exemptionRow).list();
  }

  int decideExemption(UUID id, long version, ExemptionStatus status, String note, UUID by) {
    return jdbc.sql(
            """
            UPDATE exemptions SET status = :st, decision_note = :note, decided_by = :by, decided_at = now(),
                version = version + 1
            WHERE id = :id AND version = :version AND status = 'REQUESTED'
            """)
        .param("st", status.name())
        .param("note", note)
        .param("by", by)
        .param("id", id)
        .param("version", version)
        .update();
  }

  // --- mapping ---------------------------------------------------------------------------------

  private static ReasonView reasonRow(ResultSet rs, int i) throws SQLException {
    return new ReasonView(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        ServiceStatus.valueOf(rs.getString("status")),
        rs.getLong("version"));
  }

  private static RefundView refundRow(ResultSet rs, int i) throws SQLException {
    return new RefundView(
        rs.getObject("id", UUID.class),
        rs.getObject("invoice_id", UUID.class),
        rs.getString("invoice_number"),
        rs.getObject("facility_id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getString("payment_receipt"),
        new MethodRef(
            rs.getObject("method_id", UUID.class),
            rs.getString("m_code"),
            rs.getString("m_name"),
            rs.getBoolean("method_is_cash")),
        rs.getString("currency"),
        decimal(rs, "amount"),
        rs.getString("reason"),
        RefundStatus.valueOf(rs.getString("status")),
        new UserRef(rs.getObject("requested_by", UUID.class), rs.getString("requester")),
        instant(rs, "requested_at"),
        user(rs, "decided_by", "decider"),
        instant(rs, "decided_at"),
        rs.getString("decision_note"),
        user(rs, "paid_by", "payer"),
        instant(rs, "paid_at"),
        rs.getString("receipt_number"),
        decimal(rs, "balance_after"),
        rs.getLong("version"));
  }

  private static ExemptionView exemptionRow(ResultSet rs, int i) throws SQLException {
    return new ExemptionView(
        rs.getObject("id", UUID.class),
        rs.getObject("invoice_id", UUID.class),
        rs.getString("invoice_number"),
        rs.getObject("facility_id", UUID.class),
        new ReasonView(
            rs.getObject("reason_id", UUID.class),
            rs.getString("r_code"),
            rs.getString("r_name"),
            ServiceStatus.valueOf(rs.getString("r_status")),
            rs.getLong("r_version")),
        rs.getString("currency"),
        decimal(rs, "amount"),
        rs.getString("note"),
        ExemptionStatus.valueOf(rs.getString("status")),
        new UserRef(rs.getObject("requested_by", UUID.class), rs.getString("requester")),
        instant(rs, "requested_at"),
        user(rs, "decided_by", "decider"),
        instant(rs, "decided_at"),
        rs.getString("decision_note"),
        rs.getLong("version"));
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
