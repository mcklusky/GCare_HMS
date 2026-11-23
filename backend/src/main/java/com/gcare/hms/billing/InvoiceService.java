package com.gcare.hms.billing;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.billing.BillingApi.PriceView;
import com.gcare.hms.billing.BillingApi.ServiceStatus;
import com.gcare.hms.billing.BillingApi.TaxRateView;
import com.gcare.hms.billing.BillingStore.ServiceRow;
import com.gcare.hms.billing.InvoiceApi.AddLineRequest;
import com.gcare.hms.billing.InvoiceApi.BillableVisit;
import com.gcare.hms.billing.InvoiceApi.CreateInvoiceRequest;
import com.gcare.hms.billing.InvoiceApi.InvoiceStatus;
import com.gcare.hms.billing.InvoiceApi.InvoiceSummary;
import com.gcare.hms.billing.InvoiceApi.InvoiceView;
import com.gcare.hms.billing.InvoiceApi.LineSource;
import com.gcare.hms.billing.InvoiceApi.LineStatus;
import com.gcare.hms.billing.InvoiceApi.LineView;
import com.gcare.hms.billing.InvoiceApi.ReasonRequest;
import com.gcare.hms.billing.InvoiceApi.UnbilledOrder;
import com.gcare.hms.billing.InvoiceApi.UnbilledProblem;
import com.gcare.hms.billing.InvoiceApi.VersionRequest;
import com.gcare.hms.billing.InvoiceMath.LineAmounts;
import com.gcare.hms.billing.InvoiceMath.Totals;
import com.gcare.hms.billing.InvoiceStore.InvoiceRow;
import com.gcare.hms.billing.InvoiceStore.NewLine;
import com.gcare.hms.billing.InvoiceStore.OrderToBill;
import com.gcare.hms.billing.InvoiceStore.VisitRow;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.FacilityScope;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Visit invoices (task 4.2; FR-017; docs/19 §3). Decided 2026-11-23: charges come from orders whose
 * orderable item is linked to a billable service, plus lines a cashier adds with a reason; the
 * cashier picks one currency per invoice and nothing is converted; tax is per line, rounded half-up
 * ({@link InvoiceMath}); an issued invoice never changes and is corrected by voiding it and making
 * a new one. Each line copies the approved price and tax rate in effect (UTC date) when it is
 * added. Access is checked per facility; anything outside the caller's scope is "not found".
 */
@Service
public class InvoiceService {

  private static final int MAX_LIST = 200;
  private static final int DEFAULT_DAYS = 7;
  private static final int MAX_DAYS = 90;

  private final InvoiceStore store;
  private final BillingStore billing;
  private final PaymentStore payments;
  private final AuditService audit;
  private final Clock clock = Clock.systemUTC();

  InvoiceService(
      InvoiceStore store, BillingStore billing, PaymentStore payments, AuditService audit) {
    this.store = store;
    this.billing = billing;
    this.payments = payments;
    this.audit = audit;
  }

  // --- lists -----------------------------------------------------------------------------------

  /** Recent visits without a draft or issued invoice (default 7 days, at most 90). */
  @PreAuthorize("hasAuthority('" + Permissions.INVOICE_MANAGE + "')")
  @Transactional(readOnly = true)
  public List<BillableVisit> billableVisits(UUID facilityId, Integer days) {
    CurrentUser user = CurrentUser.require();
    int window = days == null ? DEFAULT_DAYS : days;
    if (window < 1 || window > MAX_DAYS) {
      throw new ApiException.Invalid("days", "must be between 1 and " + MAX_DAYS);
    }
    FacilityScope scope = narrow(user.scopeOf(Permissions.INVOICE_MANAGE), facilityId);
    List<BillableVisit> result =
        store.billableVisits(
            scope.facilityIds(),
            scope.allFacilities(),
            Instant.now(clock).minus(Duration.ofDays(window)),
            MAX_LIST);
    audit(user, facilityId, "BILLABLE_VISITS_VIEWED", "Encounter", null, Map.of());
    return result;
  }

  /** Invoices at facilities in scope; default drafts and issued invoices, newest first. */
  @PreAuthorize("hasAuthority('" + Permissions.INVOICE_READ + "')")
  @Transactional(readOnly = true)
  public List<InvoiceSummary> invoices(UUID facilityId, List<InvoiceStatus> statuses) {
    CurrentUser user = CurrentUser.require();
    FacilityScope scope = narrow(user.scopeOf(Permissions.INVOICE_READ), facilityId);
    Set<InvoiceStatus> wanted =
        statuses == null || statuses.isEmpty()
            ? EnumSet.of(InvoiceStatus.DRAFT, InvoiceStatus.ISSUED)
            : EnumSet.copyOf(statuses);
    List<InvoiceSummary> result =
        store.invoices(scope.facilityIds(), scope.allFacilities(), wanted, MAX_LIST).stream()
            .map(this::summary)
            .toList();
    audit(user, facilityId, "INVOICES_VIEWED", "Invoice", null, Map.of());
    return result;
  }

  @PreAuthorize("hasAuthority('" + Permissions.INVOICE_READ + "')")
  @Transactional(readOnly = true)
  public List<InvoiceSummary> invoicesOfVisit(UUID encounterId) {
    CurrentUser user = CurrentUser.require();
    VisitRow visit = visitInScope(user, encounterId, Permissions.INVOICE_READ);
    List<InvoiceSummary> result =
        store.invoicesOfVisit(encounterId).stream().map(this::summary).toList();
    audit(user, visit.facilityId(), "INVOICES_VIEWED", "Encounter", encounterId, Map.of());
    return result;
  }

  @PreAuthorize("hasAuthority('" + Permissions.INVOICE_READ + "')")
  @Transactional(readOnly = true)
  public InvoiceView get(UUID id) {
    CurrentUser user = CurrentUser.require();
    InvoiceRow invoice = invoiceInScope(user, id, Permissions.INVOICE_READ);
    audit(user, invoice.facilityId(), "INVOICE_VIEWED", "Invoice", id, Map.of());
    return view(invoice);
  }

  // --- drafts ----------------------------------------------------------------------------------

  /**
   * Starts a draft invoice for a visit in the chosen currency and adds the charges of its orders.
   * If the visit's last invoice was voided, the new one records that it replaces it.
   */
  @PreAuthorize("hasAuthority('" + Permissions.INVOICE_MANAGE + "')")
  @Transactional
  public InvoiceView create(UUID encounterId, CreateInvoiceRequest request) {
    CurrentUser user = CurrentUser.require();
    VisitRow visit = visitInScope(user, encounterId, Permissions.INVOICE_MANAGE);
    if ("CANCELLED".equals(visit.status())) {
      throw new ApiException.InvalidTransition("This visit was cancelled and cannot be billed");
    }
    if (InvoiceMath.decimals(request.currency()) < 0) {
      throw new ApiException.Invalid("currency", "is not an ISO 4217 currency that can be charged");
    }
    UUID id = UUID.randomUUID();
    UUID replaces = store.unreplacedVoid(encounterId).orElse(null);
    try {
      store.insertInvoice(
          id, user.organizationId(), visit, request.currency(), replaces, user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict(
          "This visit already has an invoice. Open it, or void it before starting a new one.");
    }
    InvoiceRow invoice = store.invoice(id).orElseThrow();
    int added = addOrderLines(user, invoice);
    audit(
        user,
        visit.facilityId(),
        "INVOICE_CREATED",
        "Invoice",
        id,
        Map.of(
            "encounter", encounterId.toString(),
            "currency", request.currency(),
            "order_lines", Integer.toString(added)));
    return view(store.invoice(id).orElseThrow());
  }

  /** Adds the charges of orders placed since the draft was started (or last refreshed). */
  @PreAuthorize("hasAuthority('" + Permissions.INVOICE_MANAGE + "')")
  @Transactional
  public InvoiceView addOrderCharges(UUID id, VersionRequest request) {
    CurrentUser user = CurrentUser.require();
    InvoiceRow invoice = draftInScope(user, id, request.version());
    int added = addOrderLines(user, invoice);
    audit(
        user,
        invoice.facilityId(),
        "INVOICE_ORDER_CHARGES_ADDED",
        "Invoice",
        id,
        Map.of("order_lines", Integer.toString(added)));
    return view(store.invoice(id).orElseThrow());
  }

  /** A charge the cashier adds by hand (for example a consultation), with a reason. */
  @PreAuthorize("hasAuthority('" + Permissions.INVOICE_MANAGE + "')")
  @Transactional
  public InvoiceView addLine(UUID id, AddLineRequest request) {
    CurrentUser user = CurrentUser.require();
    InvoiceRow invoice = draftInScope(user, id, request.version());
    ServiceRow service =
        billing
            .service(request.serviceId())
            .filter(s -> s.status() == ServiceStatus.ACTIVE)
            .orElseThrow(() -> new ApiException.Invalid("service_id", "is not an active service"));
    Snapshot snapshot = snapshot(service.id(), invoice.currency());
    if (snapshot.problem() == UnbilledProblem.NO_PRICE) {
      throw new ApiException.InvalidTransition(
          service.name() + " has no approved price in " + invoice.currency() + " today");
    }
    if (snapshot.problem() == UnbilledProblem.NO_TAX_RATE) {
      throw new ApiException.InvalidTransition(
          "The tax rate of this price has no approved rate in effect today");
    }
    UUID lineId = UUID.randomUUID();
    insertLine(
        user,
        invoice,
        lineId,
        LineSource.MANUAL,
        null,
        service.id(),
        service.code(),
        service.name(),
        snapshot,
        request.quantity(),
        request.reason().trim());
    audit(
        user,
        invoice.facilityId(),
        "INVOICE_LINE_ADDED",
        "Invoice",
        id,
        Map.of("line", lineId.toString(), "service", service.code()));
    return view(store.invoice(id).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.INVOICE_MANAGE + "')")
  @Transactional
  public InvoiceView removeLine(UUID id, UUID lineId, ReasonRequest request) {
    CurrentUser user = CurrentUser.require();
    InvoiceRow invoice = draftInScope(user, id, request.version());
    LineView line =
        store.line(id, lineId).orElseThrow(() -> new ApiException.NotFound("Invoice line"));
    if (line.status() != LineStatus.ACTIVE
        || store.removeLine(id, lineId, request.reason().trim(), user.userId()) == 0) {
      throw new ApiException.InvalidTransition("This charge has already been removed");
    }
    audit(
        user,
        invoice.facilityId(),
        "INVOICE_LINE_REMOVED",
        "Invoice",
        id,
        Map.of("line", lineId.toString(), "service", line.serviceCode()));
    return view(store.invoice(id).orElseThrow());
  }

  // --- issue and void --------------------------------------------------------------------------

  /** Fixes the totals and gives the invoice its number. After this it can only be voided. */
  @PreAuthorize("hasAuthority('" + Permissions.INVOICE_MANAGE + "')")
  @Transactional
  public InvoiceView issue(UUID id, VersionRequest request) {
    CurrentUser user = CurrentUser.require();
    InvoiceRow invoice = invoiceInScope(user, id, Permissions.INVOICE_MANAGE);
    requireDraft(invoice);
    List<LineView> active =
        store.lines(id).stream().filter(l -> l.status() == LineStatus.ACTIVE).toList();
    if (active.isEmpty()) {
      throw new ApiException.InvalidTransition("Add at least one charge before issuing");
    }
    if (active.stream().anyMatch(l -> "CANCELLED".equals(l.orderStatus()))) {
      throw new ApiException.InvalidTransition(
          "A charge is for an order that was cancelled. Remove it before issuing.");
    }
    if (unbilled(invoice).stream().anyMatch(o -> o.problem() == null)) {
      throw new ApiException.InvalidTransition(
          "Orders were placed after this invoice was started. Add their charges before issuing.");
    }
    Totals totals = totals(active, invoice.currency());
    String number = "INV-%06d".formatted(store.nextNumber(user.organizationId()));
    if (store.issue(
            id,
            request.version(),
            number,
            totals.subtotal(),
            totals.tax(),
            totals.total(),
            user.userId())
        == 0) {
      throw new ApiException.Conflict("Someone else changed this invoice. Reload and try again.");
    }
    audit(
        user,
        invoice.facilityId(),
        "INVOICE_ISSUED",
        "Invoice",
        id,
        Map.of("number", number, "currency", invoice.currency()));
    return view(store.invoice(id).orElseThrow());
  }

  /**
   * Voids an invoice with a reason. Discarding a draft needs INVOICE_MANAGE; voiding an issued
   * invoice needs INVOICE_VOID. A new invoice can then be started for the visit.
   */
  @PreAuthorize(
      "hasAnyAuthority('" + Permissions.INVOICE_MANAGE + "', '" + Permissions.INVOICE_VOID + "')")
  @Transactional
  public InvoiceView voidInvoice(UUID id, ReasonRequest request) {
    CurrentUser user = CurrentUser.require();
    InvoiceRow invoice = invoiceInScope(user, id, null);
    String needed =
        switch (invoice.status()) {
          case DRAFT -> Permissions.INVOICE_MANAGE;
          case ISSUED -> Permissions.INVOICE_VOID;
          case VOID -> throw new ApiException.InvalidTransition("This invoice is already void");
        };
    if (!user.scopeOf(needed).includes(invoice.facilityId())) {
      throw new AccessDeniedException(
          invoice.status() == InvoiceStatus.DRAFT
              ? "You cannot discard this draft"
              : "Voiding an issued invoice needs the void permission");
    }
    if (payments.paidTotal(id).signum() > 0) {
      throw new ApiException.InvalidTransition(
          "This invoice has payments and cannot be voided. Refunds come with task 4.4.");
    }
    if (store.voidInvoice(
            id, request.version(), invoice.status(), request.reason().trim(), user.userId())
        == 0) {
      throw new ApiException.Conflict("Someone else changed this invoice. Reload and try again.");
    }
    audit(
        user,
        invoice.facilityId(),
        invoice.status() == InvoiceStatus.DRAFT ? "INVOICE_DRAFT_DISCARDED" : "INVOICE_VOIDED",
        "Invoice",
        id,
        invoice.invoiceNumber() == null ? Map.of() : Map.of("number", invoice.invoiceNumber()));
    return view(store.invoice(id).orElseThrow());
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** The approved price and tax rate in effect today, or the reason there is none. */
  private record Snapshot(PriceView price, TaxRateView rate, UnbilledProblem problem) {}

  private Snapshot snapshot(UUID serviceId, String currency) {
    LocalDate today = LocalDate.now(clock);
    Optional<PriceView> price = billing.effectivePrice(serviceId, currency, today);
    if (price.isEmpty()) {
      return new Snapshot(null, null, UnbilledProblem.NO_PRICE);
    }
    if (price.get().taxRateCode() == null) {
      return new Snapshot(price.get(), null, null);
    }
    return billing
        .effectiveRate(price.get().taxRateCode(), today)
        .map(r -> new Snapshot(price.get(), r, null))
        .orElseGet(() -> new Snapshot(price.get(), null, UnbilledProblem.NO_TAX_RATE));
  }

  private int addOrderLines(CurrentUser user, InvoiceRow invoice) {
    int added = 0;
    for (OrderToBill order : store.ordersToBill(invoice.id(), invoice.encounter().id())) {
      Snapshot snapshot = snapshot(order.serviceId(), invoice.currency());
      if (snapshot.problem() == null) {
        insertLine(
            user,
            invoice,
            UUID.randomUUID(),
            LineSource.ORDER,
            order.orderId(),
            order.serviceId(),
            order.serviceCode(),
            order.serviceName(),
            snapshot,
            1,
            null);
        added++;
      }
    }
    return added;
  }

  private void insertLine(
      CurrentUser user,
      InvoiceRow invoice,
      UUID lineId,
      LineSource source,
      UUID orderId,
      UUID serviceId,
      String serviceCode,
      String serviceName,
      Snapshot snapshot,
      int quantity,
      String reason) {
    var percent = snapshot.rate() == null ? null : snapshot.rate().ratePercent();
    LineAmounts amounts =
        InvoiceMath.line(snapshot.price().amount(), quantity, percent, invoice.currency());
    store.insertLine(
        new NewLine(
            lineId,
            user.organizationId(),
            invoice.id(),
            source,
            orderId,
            serviceId,
            serviceCode,
            serviceName,
            snapshot.price().id(),
            quantity,
            snapshot.price().amount(),
            snapshot.rate() == null ? null : snapshot.rate().code(),
            percent,
            amounts,
            reason,
            user.userId()));
  }

  private List<UnbilledOrder> unbilled(InvoiceRow invoice) {
    if (invoice.status() != InvoiceStatus.DRAFT) {
      return List.of();
    }
    List<UnbilledOrder> result = new ArrayList<>();
    for (OrderToBill order : store.ordersToBill(invoice.id(), invoice.encounter().id())) {
      result.add(
          new UnbilledOrder(
              order.orderId(),
              order.itemName(),
              order.serviceCode(),
              snapshot(order.serviceId(), invoice.currency()).problem()));
    }
    return result;
  }

  private static Totals totals(List<LineView> active, String currency) {
    return InvoiceMath.totals(
        active.stream()
            .map(l -> new LineAmounts(l.netAmount(), l.taxAmount(), l.lineTotal()))
            .toList(),
        currency);
  }

  private InvoiceView view(InvoiceRow i) {
    List<LineView> lines = store.lines(i.id());
    Totals totals =
        i.status() == InvoiceStatus.DRAFT
            ? totals(
                lines.stream().filter(l -> l.status() == LineStatus.ACTIVE).toList(), i.currency())
            : new Totals(i.subtotal(), i.taxTotal(), i.total());
    BigDecimal paid = PaymentStore.strip(payments.paidTotal(i.id()));
    return new InvoiceView(
        i.id(),
        i.invoiceNumber(),
        i.status(),
        i.currency(),
        i.facilityId(),
        i.encounter(),
        i.patient(),
        lines,
        unbilled(i),
        totals.subtotal(),
        totals.tax(),
        totals.total(),
        paid,
        i.status() == InvoiceStatus.ISSUED ? i.total().subtract(paid) : null,
        i.replacesInvoiceId(),
        i.createdBy(),
        i.createdAt(),
        i.issuedBy(),
        i.issuedAt(),
        i.voidReason(),
        i.voidedBy(),
        i.voidedAt(),
        i.version());
  }

  private InvoiceSummary summary(InvoiceRow i) {
    var total =
        i.status() == InvoiceStatus.DRAFT
            ? totals(
                    store.lines(i.id()).stream()
                        .filter(l -> l.status() == LineStatus.ACTIVE)
                        .toList(),
                    i.currency())
                .total()
            : i.total();
    return new InvoiceSummary(
        i.id(),
        i.invoiceNumber(),
        i.status(),
        i.currency(),
        i.facilityId(),
        i.encounter().id(),
        i.patient(),
        total,
        i.createdAt(),
        i.issuedAt(),
        i.version());
  }

  private static FacilityScope narrow(FacilityScope scope, UUID facilityId) {
    if (facilityId == null) {
      return scope;
    }
    if (!scope.includes(facilityId)) {
      throw new ApiException.NotFound("Facility");
    }
    return new FacilityScope(false, Set.of(facilityId));
  }

  private VisitRow visitInScope(CurrentUser user, UUID encounterId, String permission) {
    return store
        .visit(encounterId)
        .filter(v -> user.scopeOf(permission).includes(v.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Visit"));
  }

  /** The invoice if the caller may see it (with {@code permission}, or any invoice permission). */
  private InvoiceRow invoiceInScope(CurrentUser user, UUID id, String permission) {
    return store
        .invoice(id)
        .filter(i ->
            permission == null
                ? user.scopeOf(Permissions.INVOICE_READ).includes(i.facilityId())
                    || user.scopeOf(Permissions.INVOICE_MANAGE).includes(i.facilityId())
                    || user.scopeOf(Permissions.INVOICE_VOID).includes(i.facilityId())
                : user.scopeOf(permission).includes(i.facilityId()))
        .orElseThrow(() -> new ApiException.NotFound("Invoice"));
  }

  private static void requireDraft(InvoiceRow invoice) {
    if (invoice.status() != InvoiceStatus.DRAFT) {
      throw new ApiException.InvalidTransition(
          "This invoice is " + invoice.status().name().toLowerCase() + " and cannot be changed");
    }
  }

  /** A draft in scope whose version matches; bumps the version so concurrent edits conflict. */
  private InvoiceRow draftInScope(CurrentUser user, UUID id, long version) {
    InvoiceRow invoice = invoiceInScope(user, id, Permissions.INVOICE_MANAGE);
    requireDraft(invoice);
    if (store.touchDraft(id, version) == 0) {
      throw new ApiException.Conflict("Someone else changed this invoice. Reload and try again.");
    }
    return invoice;
  }

  /** Audited after commit; metadata holds ids, codes and currencies, never amounts or reasons. */
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
