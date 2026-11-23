package com.gcare.hms.radiology;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.radiology.ImagingStore.OrderRow;
import com.gcare.hms.radiology.ImagingWorkApi.WorklistItem;
import com.gcare.hms.radiology.RadiologyReportApi.AcknowledgeRequest;
import com.gcare.hms.radiology.RadiologyReportApi.CreateReportRequest;
import com.gcare.hms.radiology.RadiologyReportApi.CriticalView;
import com.gcare.hms.radiology.RadiologyReportApi.ImagingResult;
import com.gcare.hms.radiology.RadiologyReportApi.ReportStatus;
import com.gcare.hms.radiology.RadiologyReportApi.ReportView;
import com.gcare.hms.radiology.RadiologyReportApi.ReportingItem;
import com.gcare.hms.radiology.RadiologyReportApi.Sections;
import com.gcare.hms.radiology.RadiologyReportApi.SignRequest;
import com.gcare.hms.radiology.RadiologyReportApi.UpdateReportRequest;
import com.gcare.hms.radiology.RadiologyReportApi.VersionRequest;
import com.gcare.hms.radiology.RadiologyReportStore.CriticalRow;
import com.gcare.hms.radiology.RadiologyReportStore.ReportRow;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.FacilityScope;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * Radiology reports and critical results (task 6.4; docs/26).
 *
 * <ul>
 *   <li>A radiologist writes a draft (visible to radiologists only) and signs it; signed reports
 *       never change. A correction is an amendment of the latest signed report, with a reason.
 *   <li>A report marked critical can only be signed with the documented direct contact (who, how,
 *       when). Clinicians acknowledge it; open ones are listed for oversight. No time limits.
 *   <li>Clinicians see imaging results with "not yet reported" until a report is signed.
 * </ul>
 */
@Service
public class RadiologyReportService {

  private static final String READERS =
      "hasAnyAuthority('"
          + Permissions.RADIOLOGY_REPORT_READ
          + "', '"
          + Permissions.RADIOLOGY_REPORT_WRITE
          + "')";

  private final RadiologyReportStore store;
  private final ImagingStore imaging;
  private final ImagingViews views;
  private final AuditService audit;
  private static final Instant EARLIEST_CONTACT = Instant.parse("2000-01-01T00:00:00Z");

  RadiologyReportService(
      RadiologyReportStore store, ImagingStore imaging, ImagingViews views, AuditService audit) {
    this.store = store;
    this.imaging = imaging;
    this.views = views;
    this.audit = audit;
  }

  // --- radiologist -----------------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.RADIOLOGY_REPORT_WRITE + "')")
  @Transactional(readOnly = true)
  public List<ReportingItem> reportingWorklist() {
    CurrentUser user = CurrentUser.require();
    List<ReportingItem> items = new ArrayList<>();
    FacilityScope scope = user.scopeOf(Permissions.RADIOLOGY_REPORT_WRITE);
    for (UUID id : scope.isEmpty() ? List.<UUID>of() : store.ordersToReport(scope)) {
      OrderRow o = imaging.order(id).orElse(null);
      if (o == null || !user.scopeOf(Permissions.RADIOLOGY_REPORT_WRITE).includes(o.facilityId())) {
        continue;
      }
      List<ReportRow> reports = store.reportsOfOrder(id);
      Optional<ReportRow> draft =
          reports.stream().filter(r -> r.status() == ReportStatus.DRAFT).findFirst();
      Optional<ReportRow> signed = latestSigned(reports);
      items.add(
          new ReportingItem(
              o.orderId(),
              o.accessionNumber(),
              o.patient(),
              o.itemName(),
              o.priority(),
              o.indication(),
              o.examinedAt(),
              views.item(o).studies(),
              draft.map(ReportRow::id).orElse(null),
              draft.map(ReportRow::author).orElse(null),
              signed.map(ReportRow::id).orElse(null)));
    }
    audit(
        user,
        null,
        "RADIOLOGY_REPORTING_LIST_VIEWED",
        "RadiologyReport",
        null,
        Map.of("count", Integer.toString(items.size())));
    return items;
  }

  /** Reports of an order: drafts and signed for radiologists, signed only for other readers. */
  @PreAuthorize(READERS)
  @Transactional(readOnly = true)
  public List<ReportView> reportsOfOrder(UUID orderId) {
    CurrentUser user = CurrentUser.require();
    OrderRow o = readableOrder(user, orderId);
    boolean radiologist = user.scopeOf(Permissions.RADIOLOGY_REPORT_WRITE).includes(o.facilityId());
    List<ReportView> list =
        store.reportsOfOrder(orderId).stream()
            .filter(r -> radiologist || r.status() == ReportStatus.SIGNED)
            .map(r -> view(r, o))
            .toList();
    audit(
        user,
        o.facilityId(),
        "RADIOLOGY_REPORT_VIEWED",
        "ImagingOrder",
        orderId,
        Map.of("count", Integer.toString(list.size())));
    return list;
  }

  @PreAuthorize("hasAuthority('" + Permissions.RADIOLOGY_REPORT_WRITE + "')")
  @Transactional
  public ReportView createDraft(UUID orderId, CreateReportRequest request) {
    CurrentUser user = CurrentUser.require();
    writableOrder(user, orderId);
    imaging.lockOrder(orderId);
    OrderRow o = imaging.order(orderId).orElseThrow();
    if ("CANCELLED".equals(o.orderStatus())) {
      throw new ApiException.InvalidTransition("The order was cancelled");
    }
    if (o.examinedAt() == null) {
      throw new ApiException.InvalidTransition(
          "The examination is not recorded as done yet; it cannot be reported");
    }
    List<ReportRow> reports = store.reportsOfOrder(orderId);
    reports.stream()
        .filter(r -> r.status() == ReportStatus.DRAFT)
        .findFirst()
        .ifPresent(d -> {
          throw new ApiException.Conflict(
              "A draft by " + d.author().displayName() + " is already open for this order");
        });
    Optional<ReportRow> signed = latestSigned(reports);
    String reason =
        StringUtils.hasText(request.amendmentReason()) ? request.amendmentReason().trim() : null;
    if (signed.isPresent() && reason == null) {
      throw new ApiException.Invalid(
          "amendment_reason", "is required: this order already has a signed report");
    }
    if (signed.isEmpty() && reason != null) {
      throw new ApiException.Invalid("amendment_reason", "only applies to an amendment");
    }
    Sections s =
        request.sections() == null
            ? new Sections(null, null, null, null, false)
            : request.sections();
    if (signed.isPresent() && request.sections() == null) {
      // An amendment starts from the signed text.
      ReportRow r = signed.get();
      s = new Sections(r.technique(), r.comparison(), r.findings(), r.impression(), r.critical());
    }
    UUID id = UUID.randomUUID();
    try {
      store.insert(
          id,
          user.organizationId(),
          o.facilityId(),
          orderId,
          user.userId(),
          clean(s),
          signed.map(ReportRow::id).orElse(null),
          reason);
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict("Another draft or amendment was started at the same time");
    }
    audit(
        user,
        o.facilityId(),
        signed.isPresent() ? "RADIOLOGY_AMENDMENT_STARTED" : "RADIOLOGY_REPORT_STARTED",
        "RadiologyReport",
        id,
        Map.of("order", orderId.toString()));
    return view(store.report(id).orElseThrow(), o);
  }

  @PreAuthorize("hasAuthority('" + Permissions.RADIOLOGY_REPORT_WRITE + "')")
  @Transactional
  public ReportView update(UUID reportId, UpdateReportRequest request) {
    CurrentUser user = CurrentUser.require();
    ReportRow r = ownDraft(user, reportId);
    if (store.update(reportId, request.version(), clean(request.sections())) == 0) {
      throw new ApiException.Conflict("This draft was changed. Reload it.");
    }
    audit(
        user,
        r.facilityId(),
        "RADIOLOGY_REPORT_SAVED",
        "RadiologyReport",
        reportId,
        Map.of("fields", "technique,comparison,findings,impression,critical"));
    return view(store.report(reportId).orElseThrow(), imaging.order(r.orderId()).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.RADIOLOGY_REPORT_WRITE + "')")
  @Transactional
  public ReportView sign(UUID reportId, SignRequest request) {
    CurrentUser user = CurrentUser.require();
    ReportRow r = ownDraft(user, reportId);
    if (r.version() != request.version()) {
      throw new ApiException.Conflict("This draft was changed. Reload it.");
    }
    if ("CANCELLED".equals(imaging.order(r.orderId()).orElseThrow().orderStatus())) {
      throw new ApiException.InvalidTransition(
          "The order was cancelled after the examination; discard the draft");
    }
    if (!StringUtils.hasText(r.findings())) {
      throw new ApiException.Invalid("findings", "is required to sign");
    }
    if (!StringUtils.hasText(r.impression())) {
      throw new ApiException.Invalid("impression", "is required to sign");
    }
    boolean amendsCritical = amendsCriticalResult(r);
    if (r.critical() || amendsCritical) {
      if (request.communication() == null) {
        throw new ApiException.Invalid(
            "communication",
            r.critical()
                ? "A critical result needs the documented direct contact: who was told, how and when"
                : "This amends a report with a critical result: document whom you told about the"
                    + " change, how and when");
      }
      if (request
          .communication()
          .communicatedAt()
          .isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
        throw new ApiException.Invalid(
            "communication.communicated_at", "must not be in the future");
      }
      // A sanity floor only (a typing error must give 400, not a database error). Orders keyed
      // after downtime carry the keying time, so the contact may precede the order's own time.
      if (request.communication().communicatedAt().isBefore(EARLIEST_CONTACT)) {
        throw new ApiException.Invalid(
            "communication.communicated_at", "is not a plausible date; check the year");
      }
    } else if (request.communication() != null) {
      throw new ApiException.Invalid(
          "communication",
          "only applies to a report marked critical or amending a critical result");
    }
    if (store.sign(reportId, request.version()) == 0) {
      throw new ApiException.Conflict("This draft was changed. Reload it.");
    }
    if (r.critical() || amendsCritical) {
      String kind = r.critical() ? "CRITICAL" : "CHANGED";
      store.insertCritical(
          reportId,
          user.organizationId(),
          r.facilityId(),
          r.orderId(),
          user.userId(),
          request.communication(),
          kind);
      audit(
          user,
          r.facilityId(),
          "CRITICAL_RESULT_FLAGGED",
          "RadiologyReport",
          reportId,
          Map.of("method", request.communication().method().name(), "kind", kind));
    }
    if (r.amendsReportId() != null && store.supersede(r.amendsReportId(), reportId) == 1) {
      // The open result of the amended report is replaced: clinicians acknowledge the latest one.
      audit(
          user,
          r.facilityId(),
          "CRITICAL_RESULT_SUPERSEDED",
          "RadiologyReport",
          r.amendsReportId(),
          Map.of("by_report", reportId.toString()));
    }
    audit(
        user,
        r.facilityId(),
        r.amendsReportId() == null ? "RADIOLOGY_REPORT_SIGNED" : "RADIOLOGY_REPORT_AMENDED",
        "RadiologyReport",
        reportId,
        Map.of("order", r.orderId().toString(), "critical", Boolean.toString(r.critical())));
    return view(store.report(reportId).orElseThrow(), imaging.order(r.orderId()).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.RADIOLOGY_REPORT_WRITE + "')")
  @Transactional
  public ReportView discard(UUID reportId, VersionRequest request) {
    CurrentUser user = CurrentUser.require();
    ReportRow r = ownDraft(user, reportId);
    if (store.discard(reportId, request.version()) == 0) {
      throw new ApiException.Conflict("This draft was changed. Reload it.");
    }
    audit(
        user, r.facilityId(), "RADIOLOGY_REPORT_DISCARDED", "RadiologyReport", reportId, Map.of());
    return view(store.report(reportId).orElseThrow(), imaging.order(r.orderId()).orElseThrow());
  }

  // --- clinicians ------------------------------------------------------------------------------

  @PreAuthorize(READERS)
  @Transactional(readOnly = true)
  public List<ImagingResult> resultsOfEncounter(UUID encounterId) {
    CurrentUser user = CurrentUser.require();
    return results(user, imaging.ordersOfEncounter(encounterId), "Encounter", encounterId);
  }

  @PreAuthorize(READERS)
  @Transactional(readOnly = true)
  public List<ImagingResult> resultsOfPatient(UUID patientId) {
    CurrentUser user = CurrentUser.require();
    return results(user, imaging.ordersOfPatient(patientId), "Patient", patientId);
  }

  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.CRITICAL_RESULT_ACKNOWLEDGE
          + "', '"
          + Permissions.CRITICAL_RESULT_OVERSIGHT
          + "')")
  @Transactional(readOnly = true)
  public List<CriticalView> openCriticalResults() {
    CurrentUser user = CurrentUser.require();
    FacilityScope ack = user.scopeOf(Permissions.CRITICAL_RESULT_ACKNOWLEDGE);
    FacilityScope oversight = user.scopeOf(Permissions.CRITICAL_RESULT_OVERSIGHT);
    FacilityScope either =
        ack.allFacilities() || oversight.allFacilities()
            ? FacilityScope.ALL
            : new FacilityScope(
                false,
                java.util.stream.Stream.concat(
                        ack.facilityIds().stream(), oversight.facilityIds().stream())
                    .collect(java.util.stream.Collectors.toSet()));
    List<CriticalView> list =
        either.isEmpty()
            ? List.of()
            : store.openCritical(either).stream().map(this::criticalView).toList();
    audit(
        user,
        null,
        "CRITICAL_RESULTS_VIEWED",
        "CriticalResult",
        null,
        Map.of("count", Integer.toString(list.size())));
    return list;
  }

  @PreAuthorize("hasAuthority('" + Permissions.CRITICAL_RESULT_ACKNOWLEDGE + "')")
  @Transactional
  public CriticalView acknowledge(UUID reportId, AcknowledgeRequest request) {
    CurrentUser user = CurrentUser.require();
    CriticalRow c =
        store
            .critical(reportId)
            .filter(
                x -> user.scopeOf(Permissions.CRITICAL_RESULT_ACKNOWLEDGE).includes(x.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Critical result"));
    if (c.acknowledgedAt() != null) {
      throw new ApiException.InvalidTransition(
          "Already acknowledged by " + c.acknowledgedBy().displayName());
    }
    if (c.supersededAt() != null) {
      throw new ApiException.InvalidTransition(
          "The report was amended; acknowledge the amended report's result instead");
    }
    String note = StringUtils.hasText(request.note()) ? request.note().trim() : null;
    if (store.acknowledge(reportId, request.version(), user.userId(), note) == 0) {
      throw new ApiException.Conflict("This critical result was changed. Reload it.");
    }
    audit(
        user,
        c.facilityId(),
        "CRITICAL_RESULT_ACKNOWLEDGED",
        "RadiologyReport",
        reportId,
        Map.of());
    return criticalView(store.critical(reportId).orElseThrow());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private List<ImagingResult> results(
      CurrentUser user, List<OrderRow> orders, String subjectType, UUID subjectId) {
    List<ImagingResult> list = new ArrayList<>();
    for (OrderRow o : orders) {
      if (!user.scopeOf(Permissions.RADIOLOGY_REPORT_READ).includes(o.facilityId())
          && !user.scopeOf(Permissions.RADIOLOGY_REPORT_WRITE).includes(o.facilityId())) {
        continue;
      }
      WorklistItem item = views.item(o);
      List<ReportRow> signed =
          store.reportsOfOrder(o.orderId()).stream()
              .filter(r -> r.status() == ReportStatus.SIGNED)
              .sorted((a, b) -> a.signedAt().compareTo(b.signedAt()))
              .toList();
      ReportView current = signed.isEmpty() ? null : view(signed.get(signed.size() - 1), o);
      List<ReportView> earlier = new ArrayList<>();
      for (int i = 0; i < signed.size() - 1; i++) {
        earlier.add(view(signed.get(i), o));
      }
      Collections.reverse(earlier);
      String state =
          current == null
              ? "NOT_YET_REPORTED"
              : current.amendsReportId() == null ? "REPORTED" : "AMENDED";
      list.add(
          new ImagingResult(
              o.orderId(),
              o.encounterId(),
              o.accessionNumber(),
              o.itemName(),
              o.requestedAt(),
              item.step(),
              state,
              item.studies(),
              current,
              earlier));
    }
    // Whose record was read: the visit or the patient, like the other chart reads.
    audit(
        user,
        null,
        "IMAGING_RESULTS_VIEWED",
        subjectType,
        subjectId,
        Map.of("count", Integer.toString(list.size())));
    return list;
  }

  private static Optional<ReportRow> latestSigned(List<ReportRow> reports) {
    return reports.stream()
        .filter(r -> r.status() == ReportStatus.SIGNED)
        .max((a, b) -> a.signedAt().compareTo(b.signedAt()));
  }

  private ReportView view(ReportRow r, OrderRow o) {
    CriticalView c = store.critical(r.id()).map(this::criticalView).orElse(null);
    return new ReportView(
        r.id(),
        r.orderId(),
        r.status(),
        r.technique(),
        r.comparison(),
        r.findings(),
        r.impression(),
        r.critical(),
        r.author(),
        r.createdAt(),
        r.updatedAt(),
        r.signedAt(),
        r.amendsReportId(),
        r.amendmentReason(),
        c,
        r.status() == ReportStatus.DRAFT && (r.critical() || amendsCriticalResult(r)),
        r.version());
  }

  /** True when this report amends one that carried a critical (or changed) result. */
  private boolean amendsCriticalResult(ReportRow r) {
    return r.amendsReportId() != null && store.critical(r.amendsReportId()).isPresent();
  }

  private CriticalView criticalView(CriticalRow c) {
    OrderRow o = imaging.order(c.orderId()).orElseThrow();
    return new CriticalView(
        c.reportId(),
        c.orderId(),
        c.facilityId(),
        o.encounterId(),
        o.accessionNumber(),
        o.itemName(),
        o.patient(),
        c.flaggedBy(),
        c.flaggedAt(),
        c.communicatedTo(),
        c.method(),
        c.communicatedAt(),
        c.communicationNote(),
        c.acknowledgedBy(),
        c.acknowledgedAt(),
        c.acknowledgementNote(),
        c.kind(),
        c.supersededBy(),
        c.supersededAt(),
        c.version());
  }

  private static Sections clean(Sections s) {
    return new Sections(
        trim(s.technique()),
        trim(s.comparison()),
        trim(s.findings()),
        trim(s.impression()),
        Boolean.TRUE.equals(s.critical()));
  }

  private static String trim(String v) {
    return StringUtils.hasText(v) ? v.strip() : null;
  }

  private OrderRow readableOrder(CurrentUser user, UUID orderId) {
    return imaging
        .order(orderId)
        .filter(o ->
            user.scopeOf(Permissions.RADIOLOGY_REPORT_READ).includes(o.facilityId())
                || user.scopeOf(Permissions.RADIOLOGY_REPORT_WRITE).includes(o.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Imaging order"));
  }

  private OrderRow writableOrder(CurrentUser user, UUID orderId) {
    return imaging
        .order(orderId)
        .filter(o -> user.scopeOf(Permissions.RADIOLOGY_REPORT_WRITE).includes(o.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Imaging order"));
  }

  /** A draft the user wrote; other radiologists may read it but not change or sign it. */
  private ReportRow ownDraft(CurrentUser user, UUID reportId) {
    store.lock(reportId);
    ReportRow r =
        store
            .report(reportId)
            .filter(x -> user.scopeOf(Permissions.RADIOLOGY_REPORT_WRITE).includes(x.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Radiology report"));
    if (r.status() != ReportStatus.DRAFT) {
      throw new ApiException.InvalidTransition(
          r.status() == ReportStatus.SIGNED
              ? "A signed report cannot change; start an amendment"
              : "This draft was discarded");
    }
    if (!r.author().id().equals(user.userId())) {
      throw new ApiException.InvalidTransition("Only the author can change or sign this draft");
    }
    return r;
  }

  /** Audited after commit; metadata holds ids and counts, never report text. */
  private void audit(
      CurrentUser user,
      UUID facilityId,
      String action,
      String type,
      UUID id,
      Map<String, String> metadata) {
    AuditEvent event =
        new AuditEvent(
            user.organizationId(),
            facilityId,
            user.userId(),
            action,
            type,
            id == null ? null : id.toString(),
            AuditOutcome.SUCCESS,
            null,
            Instant.now(),
            null,
            Map.copyOf(metadata));
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
