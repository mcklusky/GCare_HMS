package com.gcare.hms.reporting;

import com.gcare.hms.reporting.ReportApi.ReportType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only aggregate queries over encounters, orders, patients and billing tables (docs/18 §6:
 * read-only cross-module joins). Row-level security limits every statement to the organization; the
 * service limits the facility. Days are facility-local: {@code (t AT TIME ZONE :tz)::date}.
 */
@Repository
class ReportStore {

  record FacilityRow(UUID id, String name, String timezone) {}

  private static final String RANGE =
      " >= (CAST(:from AS timestamp) AT TIME ZONE :tz) AND %s < (CAST(:until AS timestamp) AT TIME ZONE :tz)";

  private final JdbcClient jdbc;

  ReportStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<FacilityRow> facility(UUID id) {
    return jdbc.sql("SELECT id, name, timezone FROM facilities WHERE id = :id")
        .param("id", id)
        .query((rs, i) ->
            new FacilityRow(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("timezone")))
        .optional();
  }

  private static String range(String column) {
    return column + RANGE.formatted(column);
  }

  /** Rows for a report; {@code until} is the day after the last day (exclusive). */
  List<Map<String, Object>> rows(
      ReportType type, UUID facility, String tz, LocalDate from, LocalDate until) {
    String sql =
        switch (type) {
          case VISITS ->
              """
              SELECT (e.arrived_at AT TIME ZONE :tz)::date AS day, d.name AS department,
                     count(*) AS arrivals,
                     count(e.consultation_started_at) AS seen,
                     count(*) FILTER (WHERE e.status = 'CLOSED') AS closed,
                     count(*) FILTER (WHERE e.status = 'CANCELLED') AS cancelled,
                     count(*) FILTER (WHERE e.status IN ('WAITING', 'IN_CONSULTATION')) AS still_open,
                     avg(extract(epoch FROM e.consultation_started_at - e.arrived_at) / 60) AS avg_wait_minutes
              FROM encounters e JOIN departments d ON d.id = e.department_id
              WHERE e.facility_id = :facility AND %s
              GROUP BY 1, 2 ORDER BY 1, 2
              """
                  .formatted(range("e.arrived_at"));
          case ORDERS ->
              """
              SELECT (o.requested_at AT TIME ZONE :tz)::date AS day, o.item_category AS category,
                     o.item_code || ' ' || o.item_name AS item,
                     count(*) AS requested,
                     count(*) FILTER (WHERE o.status = 'COMPLETED') AS completed,
                     count(*) FILTER (WHERE o.status = 'CANCELLED') AS cancelled,
                     count(*) FILTER (WHERE o.status IN ('REQUESTED', 'IN_PROGRESS')) AS still_open,
                     avg(extract(epoch FROM c.done - o.requested_at) / 60)
                         FILTER (WHERE o.status = 'COMPLETED') AS avg_turnaround_minutes
              FROM clinical_orders o
              LEFT JOIN LATERAL (SELECT max(ev.occurred_at) AS done FROM order_events ev
                                 WHERE ev.order_id = o.id AND ev.event_type = 'COMPLETED') c ON true
              WHERE o.facility_id = :facility AND %s
              GROUP BY 1, 2, 3 ORDER BY 1, 2, 3
              """
                  .formatted(range("o.requested_at"));
          case REGISTRATIONS ->
              """
              SELECT (p.created_at AT TIME ZONE :tz)::date AS day, p.registration_source AS source,
                     count(*) AS registrations,
                     count(*) FILTER (WHERE p.details_incomplete) AS details_incomplete,
                     count(*) FILTER (WHERE p.status = 'LINKED') AS linked
              FROM patients p
              WHERE p.registration_facility_id = :facility AND %s
              GROUP BY 1, 2 ORDER BY 1, 2
              """
                  .formatted(range("p.created_at"));
          case REVENUE ->
              """
              WITH facts AS (
                  SELECT (i.issued_at AT TIME ZONE :tz)::date AS day, i.currency, 'issued' AS kind,
                         CASE WHEN i.status = 'ISSUED' THEN i.total ELSE 0 END AS amount,
                         CASE WHEN i.status = 'ISSUED' THEN 1 ELSE 0 END AS n
                  FROM invoices i WHERE i.facility_id = :facility AND i.issued_at IS NOT NULL AND %s
                  UNION ALL
                  SELECT (i.voided_at AT TIME ZONE :tz)::date, i.currency, 'voided', 0, 1
                  FROM invoices i WHERE i.facility_id = :facility AND i.status = 'VOID'
                      AND i.invoice_number IS NOT NULL AND %s
                  UNION ALL
                  SELECT (p.received_at AT TIME ZONE :tz)::date, p.currency, 'paid', p.amount, 1
                  FROM payments p WHERE p.facility_id = :facility AND %s
                  UNION ALL
                  SELECT (r.paid_at AT TIME ZONE :tz)::date, r.currency, 'refunded', r.amount, 1
                  FROM refunds r WHERE r.facility_id = :facility AND r.status = 'PAID' AND %s
                  UNION ALL
                  SELECT (x.decided_at AT TIME ZONE :tz)::date, x.currency, 'exempted', x.amount, 1
                  FROM exemptions x WHERE x.facility_id = :facility AND x.status = 'APPROVED' AND %s
              )
              SELECT day, currency,
                     coalesce(sum(n) FILTER (WHERE kind = 'issued'), 0) AS invoices_issued,
                     coalesce(sum(amount) FILTER (WHERE kind = 'issued'), 0) AS invoiced,
                     coalesce(sum(n) FILTER (WHERE kind = 'voided'), 0) AS invoices_voided,
                     coalesce(sum(amount) FILTER (WHERE kind = 'paid'), 0) AS collected,
                     coalesce(sum(amount) FILTER (WHERE kind = 'refunded'), 0) AS refunded,
                     coalesce(sum(amount) FILTER (WHERE kind = 'paid'), 0)
                         - coalesce(sum(amount) FILTER (WHERE kind = 'refunded'), 0) AS net_collected,
                     coalesce(sum(amount) FILTER (WHERE kind = 'exempted'), 0) AS exempted
              FROM facts GROUP BY day, currency ORDER BY day, currency
              """
                  .formatted(
                      range("i.issued_at"),
                      range("i.voided_at"),
                      range("p.received_at"),
                      range("r.paid_at"),
                      range("x.decided_at"));
          case PAYMENT_METHODS ->
              """
              WITH facts AS (
                  SELECT (p.received_at AT TIME ZONE :tz)::date AS day, p.method_id, p.currency,
                         'paid' AS kind, p.amount
                  FROM payments p WHERE p.facility_id = :facility AND %s
                  UNION ALL
                  SELECT (r.paid_at AT TIME ZONE :tz)::date, r.method_id, r.currency, 'refunded', r.amount
                  FROM refunds r WHERE r.facility_id = :facility AND r.status = 'PAID' AND %s
              )
              SELECT f.day, m.name AS method, f.currency,
                     count(*) FILTER (WHERE f.kind = 'paid') AS payments,
                     coalesce(sum(f.amount) FILTER (WHERE f.kind = 'paid'), 0) AS amount,
                     count(*) FILTER (WHERE f.kind = 'refunded') AS refunds,
                     coalesce(sum(f.amount) FILTER (WHERE f.kind = 'refunded'), 0) AS refunded
              FROM facts f JOIN payment_methods m ON m.id = f.method_id
              GROUP BY 1, 2, 3 ORDER BY 1, 2, 3
              """
                  .formatted(range("p.received_at"), range("r.paid_at"));
          case CASH_SHIFTS ->
              """
              SELECT (s.closed_at AT TIME ZONE :tz)::date AS day, u.display_name AS cashier,
                     s.status, c.currency, c.expected, c.counted, c.difference
              FROM cashier_shifts s
              JOIN users u ON u.id = s.cashier_id
              JOIN shift_cash_counts c ON c.shift_id = s.id
              WHERE s.facility_id = :facility AND s.closed_at IS NOT NULL AND %s
              ORDER BY s.closed_at, u.display_name, c.currency
              """
                  .formatted(range("s.closed_at"));
        };
    return jdbc.sql(sql)
        .param("facility", facility)
        .param("tz", tz)
        .param("from", from)
        .param("until", until)
        .query(ReportStore::row)
        .list();
  }

  /** Today's live numbers at a facility. */
  Map<String, Object> operationsToday(UUID facility, String tz, LocalDate day) {
    return jdbc.sql(
            """
            SELECT
              (SELECT count(*) FROM encounters e WHERE e.facility_id = :facility AND %s) AS arrivals_today,
              (SELECT count(*) FROM encounters e WHERE e.facility_id = :facility AND e.status = 'WAITING') AS waiting_now,
              (SELECT count(*) FROM encounters e WHERE e.facility_id = :facility AND e.status = 'IN_CONSULTATION') AS in_consultation_now,
              (SELECT count(*) FROM clinical_orders o WHERE o.facility_id = :facility
                  AND o.status IN ('REQUESTED', 'IN_PROGRESS')) AS open_orders,
              (SELECT count(*) FROM patients p WHERE p.registration_facility_id = :facility AND %s) AS registrations_today
            """
                .formatted(range("e.arrived_at"), range("p.created_at")))
        .param("facility", facility)
        .param("tz", tz)
        .param("from", day)
        .param("until", day.plusDays(1))
        .query(ReportStore::row)
        .single();
  }

  /** Collected (payments minus paid refunds) today, per currency. */
  List<Map<String, Object>> collectedToday(UUID facility, String tz, LocalDate day) {
    return jdbc.sql(
            """
            WITH facts AS (
                SELECT p.currency, p.amount FROM payments p WHERE p.facility_id = :facility AND %s
                UNION ALL
                SELECT r.currency, -r.amount FROM refunds r
                WHERE r.facility_id = :facility AND r.status = 'PAID' AND %s
            )
            SELECT currency, sum(amount) AS net_collected FROM facts GROUP BY currency ORDER BY currency
            """
                .formatted(range("p.received_at"), range("r.paid_at")))
        .param("facility", facility)
        .param("tz", tz)
        .param("from", day)
        .param("until", day.plusDays(1))
        .query(ReportStore::row)
        .list();
  }

  /**
   * Column name → value: dates as LocalDate, money without trailing zeros, averages to 1 decimal.
   */
  private static Map<String, Object> row(ResultSet rs, int n) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    Map<String, Object> row = new LinkedHashMap<>();
    for (int c = 1; c <= meta.getColumnCount(); c++) {
      String name = meta.getColumnLabel(c);
      Object value = rs.getObject(c);
      if (value instanceof Date d) {
        value = d.toLocalDate();
      } else if (value instanceof BigDecimal b) {
        if (name.startsWith("avg_")) {
          value = b.setScale(1, RoundingMode.HALF_UP);
        } else {
          BigDecimal s = b.stripTrailingZeros();
          value = s.scale() < 0 ? s.setScale(0) : s;
        }
      } else if (value instanceof Double d && name.startsWith("avg_")) {
        value = BigDecimal.valueOf(d).setScale(1, RoundingMode.HALF_UP);
      }
      row.put(name, value);
    }
    return row;
  }
}
