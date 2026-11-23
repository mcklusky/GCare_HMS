package com.gcare.hms.reporting;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import com.gcare.hms.reporting.ReportApi.Column;
import com.gcare.hms.reporting.ReportApi.Dashboard;
import com.gcare.hms.reporting.ReportApi.ReportInfo;
import com.gcare.hms.reporting.ReportApi.ReportType;
import com.gcare.hms.reporting.ReportApi.ReportView;
import com.gcare.hms.reporting.ReportApi.Tile;
import com.gcare.hms.reporting.ReportStore.FacilityRow;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Operational reports, dashboard and CSV export (task 5.1; FR-024, FR-025; docs/20). Decided
 * 2026-11-23: visit, order and registration reports (REPORT_OPERATIONS_READ), revenue reports
 * (REPORT_REVENUE_READ); one facility in scope at a time; days in the facility's time zone; range
 * at most 366 days. Exports (REPORT_EXPORT) contain the same aggregates as the screen, never
 * patient names or numbers. Every view and export is audited with its filters.
 */
@Service
public class ReportService {

  static final int MAX_DAYS = 366;

  private final ReportStore store;
  private final AuditService audit;

  ReportService(ReportStore store, AuditService audit) {
    this.store = store;
    this.audit = audit;
  }

  /** Reports the caller may open (some facility in scope), and whether they may export. */
  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.REPORT_OPERATIONS_READ
          + "', '"
          + Permissions.REPORT_REVENUE_READ
          + "')")
  public List<ReportInfo> available() {
    CurrentUser user = CurrentUser.require();
    boolean export = user.hasPermission(Permissions.REPORT_EXPORT);
    return Arrays.stream(ReportType.values())
        .filter(t -> user.hasPermission(t.permission()))
        .map(t -> new ReportInfo(t.slug(), t.title(), export))
        .toList();
  }

  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.REPORT_OPERATIONS_READ
          + "', '"
          + Permissions.REPORT_REVENUE_READ
          + "')")
  @Transactional(readOnly = true)
  public ReportView report(String slug, UUID facilityId, LocalDate from, LocalDate to) {
    CurrentUser user = CurrentUser.require();
    ReportView view = build(user, slug, facilityId, from, to);
    audit(user, facilityId, "REPORT_VIEWED", view);
    return view;
  }

  /** The same report as CSV text, for REPORT_EXPORT holders. */
  @PreAuthorize("hasAuthority('" + Permissions.REPORT_EXPORT + "')")
  @Transactional(readOnly = true)
  public String export(String slug, UUID facilityId, LocalDate from, LocalDate to) {
    CurrentUser user = CurrentUser.require();
    ReportView view = build(user, slug, facilityId, from, to);
    audit(user, facilityId, "REPORT_EXPORTED", view);
    return ReportCsv.write(view);
  }

  /** Today's live numbers at one facility; revenue tiles only with REPORT_REVENUE_READ. */
  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.REPORT_OPERATIONS_READ
          + "', '"
          + Permissions.REPORT_REVENUE_READ
          + "')")
  @Transactional(readOnly = true)
  public Dashboard dashboard(UUID facilityId) {
    CurrentUser user = CurrentUser.require();
    boolean ops = user.scopeOf(Permissions.REPORT_OPERATIONS_READ).includes(facilityId);
    boolean revenue = user.scopeOf(Permissions.REPORT_REVENUE_READ).includes(facilityId);
    if (!ops && !revenue) {
      throw new ApiException.NotFound("Facility");
    }
    FacilityRow facility =
        store.facility(facilityId).orElseThrow(() -> new ApiException.NotFound("Facility"));
    String tz = zone(facility);
    LocalDate today = LocalDate.now(ZoneId.of(tz));
    List<Tile> tiles = new ArrayList<>();
    if (ops) {
      Map<String, Object> o = store.operationsToday(facilityId, tz, today);
      tiles.add(new Tile("arrivals_today", "Arrivals today", o.get("arrivals_today"), null));
      tiles.add(new Tile("waiting_now", "Waiting now", o.get("waiting_now"), null));
      tiles.add(
          new Tile(
              "in_consultation_now", "In consultation now", o.get("in_consultation_now"), null));
      tiles.add(new Tile("open_orders", "Open orders", o.get("open_orders"), null));
      tiles.add(
          new Tile("registrations_today", "New records today", o.get("registrations_today"), null));
    }
    if (revenue) {
      for (Map<String, Object> r : store.collectedToday(facilityId, tz, today)) {
        String currency = (String) r.get("currency");
        tiles.add(
            new Tile(
                "net_collected_" + currency,
                "Net collected today",
                r.get("net_collected"),
                currency));
      }
    }
    audit(
        user,
        facilityId,
        "DASHBOARD_VIEWED",
        "Report",
        "dashboard",
        Map.of("day", today.toString()));
    return new Dashboard(facilityId, facility.name(), today, Instant.now(), tiles);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private ReportView build(
      CurrentUser user, String slug, UUID facilityId, LocalDate from, LocalDate to) {
    ReportType type = ReportType.bySlug(slug);
    if (type == null) {
      throw new ApiException.NotFound("Report");
    }
    if (!user.hasPermission(type.permission())) {
      throw new AccessDeniedException("This report needs " + type.permission());
    }
    if (facilityId == null) {
      throw new ApiException.Invalid("facility_id", "is required");
    }
    if (!user.scopeOf(type.permission()).includes(facilityId)) {
      throw new ApiException.NotFound("Facility");
    }
    if (from == null || to == null) {
      throw new ApiException.Invalid(from == null ? "from" : "to", "is required");
    }
    if (to.isBefore(from)) {
      throw new ApiException.Invalid("to", "must be on or after from");
    }
    if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
      throw new ApiException.Invalid("to", "the period can be at most " + MAX_DAYS + " days");
    }
    FacilityRow facility =
        store.facility(facilityId).orElseThrow(() -> new ApiException.NotFound("Facility"));
    String tz = zone(facility);
    List<Map<String, Object>> rows = store.rows(type, facilityId, tz, from, to.plusDays(1));
    List<Column> columns = type.columns();
    return new ReportView(
        type.slug(),
        type.title(),
        facilityId,
        facility.name(),
        tz,
        from,
        to,
        Instant.now(),
        new UserRef(user.userId(), user.displayName()),
        columns,
        rows,
        type.notes());
  }

  private static String zone(FacilityRow facility) {
    try {
      return ZoneId.of(facility.timezone()).getId();
    } catch (DateTimeException e) {
      throw new ApiException.InvalidTransition(
          "The facility's time zone is not valid. Ask an administrator to correct it.");
    }
  }

  /** Filters only (report, period); never data values. */
  private void audit(CurrentUser user, UUID facilityId, String action, ReportView view) {
    audit(
        user,
        facilityId,
        action,
        "Report",
        view.report(),
        Map.of(
            "from",
            view.from().toString(),
            "to",
            view.to().toString(),
            "rows",
            Integer.toString(view.rows().size())));
  }

  private void audit(
      CurrentUser user,
      UUID facilityId,
      String action,
      String type,
      String id,
      Map<String, String> metadata) {
    AuditEvent event =
        new AuditEvent(
            user.organizationId(),
            facilityId,
            user.userId(),
            action,
            type,
            id,
            AuditOutcome.SUCCESS,
            null,
            Instant.now(),
            null,
            metadata);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              audit.record(event);
            }
          });
    } else {
      audit.record(event);
    }
  }
}
