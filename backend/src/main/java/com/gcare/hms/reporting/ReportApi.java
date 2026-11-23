package com.gcare.hms.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import com.gcare.hms.shared.security.Permissions;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operational reports and dashboard (task 5.1; FR-024, FR-025; docs/20). Every report is an
 * aggregate for one facility over a range of facility-local days; rows never identify patients.
 */
public final class ReportApi {

  private ReportApi() {}

  public enum ColumnType {
    DATE,
    TEXT,
    COUNT,
    DECIMAL,
    MINUTES
  }

  public record Column(String key, String label, ColumnType type) {}

  /** The reports, the permission each needs, and their columns. */
  public enum ReportType {
    VISITS(
        "visits",
        "Visits and waiting",
        Permissions.REPORT_OPERATIONS_READ,
        List.of(
            new Column("day", "Day", ColumnType.DATE),
            new Column("department", "Department", ColumnType.TEXT),
            new Column("arrivals", "Arrivals", ColumnType.COUNT),
            new Column("seen", "Called in", ColumnType.COUNT),
            new Column("closed", "Closed", ColumnType.COUNT),
            new Column("cancelled", "Cancelled", ColumnType.COUNT),
            new Column("still_open", "Still open", ColumnType.COUNT),
            new Column("avg_wait_minutes", "Average wait (min)", ColumnType.MINUTES)),
        List.of(
            "Wait = arrival to call-in as recorded; visits never called in are not in the average.",
            "Counts reflect each visit's status now, grouped by arrival day.")),
    ORDERS(
        "orders",
        "Orders and turnaround",
        Permissions.REPORT_OPERATIONS_READ,
        List.of(
            new Column("day", "Day requested", ColumnType.DATE),
            new Column("category", "Category", ColumnType.TEXT),
            new Column("item", "Item", ColumnType.TEXT),
            new Column("requested", "Requested", ColumnType.COUNT),
            new Column("completed", "Completed", ColumnType.COUNT),
            new Column("cancelled", "Cancelled", ColumnType.COUNT),
            new Column("still_open", "Still open", ColumnType.COUNT),
            new Column("avg_turnaround_minutes", "Average turnaround (min)", ColumnType.MINUTES)),
        List.of("Turnaround = request to completion, for completed orders only.")),
    REGISTRATIONS(
        "registrations",
        "Registrations",
        Permissions.REPORT_OPERATIONS_READ,
        List.of(
            new Column("day", "Day", ColumnType.DATE),
            new Column("source", "Source", ColumnType.TEXT),
            new Column("registrations", "New records", ColumnType.COUNT),
            new Column("details_incomplete", "Details incomplete", ColumnType.COUNT),
            new Column("linked", "Later linked as duplicate", ColumnType.COUNT)),
        List.of("Records registered at this facility, by registration day.")),
    REVENUE(
        "revenue",
        "Revenue and receipts",
        Permissions.REPORT_REVENUE_READ,
        List.of(
            new Column("day", "Day", ColumnType.DATE),
            new Column("currency", "Currency", ColumnType.TEXT),
            new Column("invoices_issued", "Invoices issued", ColumnType.COUNT),
            new Column("invoiced", "Invoiced (not void)", ColumnType.DECIMAL),
            new Column("invoices_voided", "Invoices voided", ColumnType.COUNT),
            new Column("collected", "Collected", ColumnType.DECIMAL),
            new Column("refunded", "Refunded", ColumnType.DECIMAL),
            new Column("net_collected", "Net collected", ColumnType.DECIMAL),
            new Column("exempted", "Exempted", ColumnType.DECIMAL)),
        List.of(
            "Amounts per currency; nothing is converted or added across currencies.",
            "Invoiced counts invoices issued that day that are not void now; voids are counted on the void day.",
            "Collected by payment day; refunded by pay-out day; exempted by approval day.")),
    PAYMENT_METHODS(
        "payment-methods",
        "Payments by method",
        Permissions.REPORT_REVENUE_READ,
        List.of(
            new Column("day", "Day", ColumnType.DATE),
            new Column("method", "Method", ColumnType.TEXT),
            new Column("currency", "Currency", ColumnType.TEXT),
            new Column("payments", "Payments", ColumnType.COUNT),
            new Column("amount", "Amount", ColumnType.DECIMAL),
            new Column("refunds", "Refunds paid out", ColumnType.COUNT),
            new Column("refunded", "Refunded", ColumnType.DECIMAL)),
        List.of("Amounts per currency; nothing is converted.")),
    CASH_SHIFTS(
        "cash-shifts",
        "Cashier shifts",
        Permissions.REPORT_REVENUE_READ,
        List.of(
            new Column("day", "Day closed", ColumnType.DATE),
            new Column("cashier", "Cashier", ColumnType.TEXT),
            new Column("status", "Status", ColumnType.TEXT),
            new Column("currency", "Currency", ColumnType.TEXT),
            new Column("expected", "Expected cash", ColumnType.DECIMAL),
            new Column("counted", "Counted", ColumnType.DECIMAL),
            new Column("difference", "Difference", ColumnType.DECIMAL)),
        List.of("Shifts closed in the period; one row per counted currency."));

    private final String slug;
    private final String title;
    private final String permission;
    private final List<Column> columns;
    private final List<String> notes;

    ReportType(
        String slug, String title, String permission, List<Column> columns, List<String> notes) {
      this.slug = slug;
      this.title = title;
      this.permission = permission;
      this.columns = columns;
      this.notes = notes;
    }

    public String slug() {
      return slug;
    }

    public String title() {
      return title;
    }

    public String permission() {
      return permission;
    }

    public List<Column> columns() {
      return columns;
    }

    public List<String> notes() {
      return notes;
    }

    public static ReportType bySlug(String slug) {
      for (ReportType t : values()) {
        if (t.slug.equals(slug)) {
          return t;
        }
      }
      return null;
    }
  }

  public record ReportInfo(String slug, String title, boolean exportable) {}

  public record ReportView(
      String report,
      String title,
      @JsonProperty("facility_id") UUID facilityId,
      @JsonProperty("facility_name") String facilityName,
      String timezone,
      LocalDate from,
      LocalDate to,
      @JsonProperty("generated_at") Instant generatedAt,
      @JsonProperty("generated_by") UserRef generatedBy,
      List<Column> columns,
      List<Map<String, Object>> rows,
      List<String> notes) {}

  /** Live numbers for today at one facility; revenue tiles only with REPORT_REVENUE_READ. */
  public record Tile(String key, String label, Object value, String currency) {}

  public record Dashboard(
      @JsonProperty("facility_id") UUID facilityId,
      @JsonProperty("facility_name") String facilityName,
      LocalDate day,
      @JsonProperty("generated_at") Instant generatedAt,
      List<Tile> tiles) {}
}
