package com.gcare.hms.billing;

import com.gcare.hms.billing.BillingApi.DecisionStatus;
import com.gcare.hms.billing.BillingApi.PriceView;
import com.gcare.hms.billing.BillingApi.ServiceStatus;
import com.gcare.hms.billing.BillingApi.TaxRateView;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Billing catalog persistence. Row-level security limits statements to the organization; decided
 * prices and tax rates cannot change (database trigger and column grants).
 */
@Repository
class BillingStore {

  record ServiceRow(
      UUID id,
      String code,
      String name,
      UUID orderCatalogItemId,
      ServiceStatus status,
      long version) {}

  private static final String SELECT_PRICE =
      """
      SELECT p.*, s.code AS s_code, s.name AS s_name, pu.display_name AS proposer, du.display_name AS decider
      FROM service_prices p
      JOIN billable_services s ON s.id = p.service_id
      JOIN users pu ON pu.id = p.proposed_by
      LEFT JOIN users du ON du.id = p.decided_by
      """;

  private static final String SELECT_RATE =
      """
      SELECT t.*, pu.display_name AS proposer, du.display_name AS decider
      FROM tax_rates t
      JOIN users pu ON pu.id = t.proposed_by
      LEFT JOIN users du ON du.id = t.decided_by
      """;

  private final JdbcClient jdbc;

  BillingStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // --- services --------------------------------------------------------------------------------

  List<ServiceRow> services() {
    return jdbc.sql("SELECT * FROM billable_services ORDER BY name, code")
        .query(BillingStore::serviceRow)
        .list();
  }

  Optional<ServiceRow> service(UUID id) {
    return jdbc.sql("SELECT * FROM billable_services WHERE id = :id")
        .param("id", id)
        .query(BillingStore::serviceRow)
        .optional();
  }

  boolean orderItemExists(UUID id) {
    return jdbc.sql("SELECT count(*) FROM order_catalog_items WHERE id = :id")
            .param("id", id)
            .query(Long.class)
            .single()
        > 0;
  }

  void insertService(UUID id, UUID org, String code, String name, UUID orderItem, UUID by) {
    jdbc.sql(
            """
            INSERT INTO billable_services (id, organization_id, code, name, order_catalog_item_id, status, created_by)
            VALUES (:id, :org, :code, :name, :item, 'ACTIVE', :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("code", code)
        .param("name", name)
        .param("item", orderItem)
        .param("by", by)
        .update();
  }

  int updateService(UUID id, long version, String name, UUID orderItem, ServiceStatus status) {
    return jdbc.sql(
            """
            UPDATE billable_services SET name = :name, order_catalog_item_id = :item, status = :status,
                updated_at = now(), version = version + 1
            WHERE id = :id AND version = :version
            """)
        .param("name", name)
        .param("item", orderItem)
        .param("status", status.name())
        .param("id", id)
        .param("version", version)
        .update();
  }

  // --- prices ----------------------------------------------------------------------------------

  void insertPrice(
      UUID id,
      UUID org,
      UUID service,
      String currency,
      BigDecimal amount,
      String taxCode,
      LocalDate from,
      String reason,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO service_prices (id, organization_id, service_id, currency, amount, tax_rate_code,
                effective_from, status, reason, proposed_by)
            VALUES (:id, :org, :service, :currency, :amount, :tax, :from, 'PROPOSED', :reason, :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("service", service)
        .param("currency", currency)
        .param("amount", amount)
        .param("tax", taxCode)
        .param("from", from)
        .param("reason", reason)
        .param("by", by)
        .update();
  }

  Optional<PriceView> price(UUID id) {
    return jdbc.sql(SELECT_PRICE + " WHERE p.id = :id")
        .param("id", id)
        .query(BillingStore::priceRow)
        .optional();
  }

  List<PriceView> pricesOf(UUID serviceId) {
    return jdbc.sql(
            SELECT_PRICE
                + " WHERE p.service_id = :s ORDER BY p.currency, p.effective_from DESC, p.proposed_at DESC")
        .param("s", serviceId)
        .query(BillingStore::priceRow)
        .list();
  }

  List<PriceView> pricesWithStatus(DecisionStatus status) {
    return jdbc.sql(SELECT_PRICE + " WHERE p.status = :st ORDER BY p.proposed_at, p.id")
        .param("st", status.name())
        .query(BillingStore::priceRow)
        .list();
  }

  /**
   * Approved prices in effect on {@code date}: the latest effective_from not after it, per service
   * and currency.
   */
  List<PriceView> effectivePrices(LocalDate date) {
    return jdbc.sql(
            "SELECT DISTINCT ON (p.service_id, p.currency) "
                + SELECT_PRICE.substring(SELECT_PRICE.indexOf("p.*"))
                + " WHERE p.status = 'APPROVED' AND p.effective_from <= :d"
                + " ORDER BY p.service_id, p.currency, p.effective_from DESC")
        .param("d", date)
        .query(BillingStore::priceRow)
        .list();
  }

  Optional<PriceView> effectivePrice(UUID service, String currency, LocalDate date) {
    return jdbc.sql(
            SELECT_PRICE
                + " WHERE p.service_id = :s AND p.currency = :c AND p.status = 'APPROVED' AND p.effective_from <= :d"
                + " ORDER BY p.effective_from DESC LIMIT 1")
        .param("s", service)
        .param("c", currency)
        .param("d", date)
        .query(BillingStore::priceRow)
        .optional();
  }

  int decidePrice(UUID id, long version, DecisionStatus status, String note, UUID by) {
    return jdbc.sql(
            """
            UPDATE service_prices SET status = :st, decision_note = :note, decided_by = :by, decided_at = now(),
                version = version + 1
            WHERE id = :id AND version = :version AND status = 'PROPOSED'
            """)
        .param("st", status.name())
        .param("note", note)
        .param("by", by)
        .param("id", id)
        .param("version", version)
        .update();
  }

  // --- tax rates -------------------------------------------------------------------------------

  void insertRate(
      UUID id,
      UUID org,
      String code,
      String name,
      BigDecimal rate,
      LocalDate from,
      String reason,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO tax_rates (id, organization_id, code, name, rate_percent, effective_from, status, reason, proposed_by)
            VALUES (:id, :org, :code, :name, :rate, :from, 'PROPOSED', :reason, :by)
            """)
        .param("id", id)
        .param("org", org)
        .param("code", code)
        .param("name", name)
        .param("rate", rate)
        .param("from", from)
        .param("reason", reason)
        .param("by", by)
        .update();
  }

  Optional<TaxRateView> rate(UUID id) {
    return jdbc.sql(SELECT_RATE + " WHERE t.id = :id")
        .param("id", id)
        .query(BillingStore::rateRow)
        .optional();
  }

  List<TaxRateView> rates() {
    return jdbc.sql(SELECT_RATE + " ORDER BY t.code, t.effective_from DESC, t.proposed_at DESC")
        .query(BillingStore::rateRow)
        .list();
  }

  List<TaxRateView> ratesWithStatus(DecisionStatus status) {
    return jdbc.sql(SELECT_RATE + " WHERE t.status = :st ORDER BY t.proposed_at, t.id")
        .param("st", status.name())
        .query(BillingStore::rateRow)
        .list();
  }

  boolean approvedRateExists(String code) {
    return jdbc.sql("SELECT count(*) FROM tax_rates WHERE code = :c AND status = 'APPROVED'")
            .param("c", code)
            .query(Long.class)
            .single()
        > 0;
  }

  Optional<TaxRateView> effectiveRate(String code, LocalDate date) {
    return jdbc.sql(
            SELECT_RATE
                + " WHERE t.code = :c AND t.status = 'APPROVED' AND t.effective_from <= :d"
                + " ORDER BY t.effective_from DESC LIMIT 1")
        .param("c", code)
        .param("d", date)
        .query(BillingStore::rateRow)
        .optional();
  }

  int decideRate(UUID id, long version, DecisionStatus status, String note, UUID by) {
    return jdbc.sql(
            """
            UPDATE tax_rates SET status = :st, decision_note = :note, decided_by = :by, decided_at = now(),
                version = version + 1
            WHERE id = :id AND version = :version AND status = 'PROPOSED'
            """)
        .param("st", status.name())
        .param("note", note)
        .param("by", by)
        .param("id", id)
        .param("version", version)
        .update();
  }

  // --- mapping ---------------------------------------------------------------------------------

  private static ServiceRow serviceRow(ResultSet rs, int i) throws SQLException {
    return new ServiceRow(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getObject("order_catalog_item_id", UUID.class),
        ServiceStatus.valueOf(rs.getString("status")),
        rs.getLong("version"));
  }

  private static PriceView priceRow(ResultSet rs, int i) throws SQLException {
    return new PriceView(
        rs.getObject("id", UUID.class),
        rs.getObject("service_id", UUID.class),
        rs.getString("s_code"),
        rs.getString("s_name"),
        rs.getString("currency"),
        rs.getBigDecimal("amount").stripTrailingZeros(),
        rs.getString("tax_rate_code"),
        rs.getObject("effective_from", LocalDate.class),
        DecisionStatus.valueOf(rs.getString("status")),
        rs.getString("reason"),
        new UserRef(rs.getObject("proposed_by", UUID.class), rs.getString("proposer")),
        instant(rs, "proposed_at"),
        user(rs, "decided_by", "decider"),
        instant(rs, "decided_at"),
        rs.getString("decision_note"),
        rs.getLong("version"));
  }

  private static TaxRateView rateRow(ResultSet rs, int i) throws SQLException {
    return new TaxRateView(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getBigDecimal("rate_percent").stripTrailingZeros(),
        rs.getObject("effective_from", LocalDate.class),
        DecisionStatus.valueOf(rs.getString("status")),
        rs.getString("reason"),
        new UserRef(rs.getObject("proposed_by", UUID.class), rs.getString("proposer")),
        instant(rs, "proposed_at"),
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
