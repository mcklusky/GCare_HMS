package com.gcare.hms.billing;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.billing.BillingApi.ServiceStatus;
import com.gcare.hms.billing.InvoiceApi.InvoiceStatus;
import com.gcare.hms.billing.InvoiceStore.InvoiceRow;
import com.gcare.hms.billing.PaymentApi.CloseShiftRequest;
import com.gcare.hms.billing.PaymentApi.CountEntry;
import com.gcare.hms.billing.PaymentApi.CountView;
import com.gcare.hms.billing.PaymentApi.CreateMethodRequest;
import com.gcare.hms.billing.PaymentApi.CurrencyAmount;
import com.gcare.hms.billing.PaymentApi.MethodTotal;
import com.gcare.hms.billing.PaymentApi.MethodView;
import com.gcare.hms.billing.PaymentApi.OpenShiftRequest;
import com.gcare.hms.billing.PaymentApi.PaymentView;
import com.gcare.hms.billing.PaymentApi.ReceiptView;
import com.gcare.hms.billing.PaymentApi.RecordPaymentRequest;
import com.gcare.hms.billing.PaymentApi.ReviewRequest;
import com.gcare.hms.billing.PaymentApi.ShiftStatus;
import com.gcare.hms.billing.PaymentApi.ShiftView;
import com.gcare.hms.billing.PaymentApi.UpdateMethodRequest;
import com.gcare.hms.billing.PaymentStore.NewPayment;
import com.gcare.hms.billing.PaymentStore.ShiftRow;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.FacilityScope;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Payment methods, payments, receipts and cashier shifts (task 4.3; FR-018; docs/19 §4). Decided
 * 2026-11-23: methods are admin-managed and start empty; an issued invoice may be paid in parts but
 * never beyond its balance; each payment goes into the cashier's open shift at the invoice's
 * facility; a shift is closed by counting cash per currency and signed off by a different person.
 * Payments are idempotent (a retried request with the same key returns the same payment) and never
 * change; reversals and refunds are task 4.4.
 */
@Service
public class PaymentService {

  private static final int MAX_LIST = 200;

  private final PaymentStore store;
  private final InvoiceStore invoices;
  private final AuditService audit;

  PaymentService(PaymentStore store, InvoiceStore invoices, AuditService audit) {
    this.store = store;
    this.invoices = invoices;
    this.audit = audit;
  }

  /** A recorded payment and whether this request created it (false: an idempotent replay). */
  public record Recorded(PaymentView payment, boolean created) {}

  // --- methods ---------------------------------------------------------------------------------

  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.PAYMENT_RECORD
          + "', '"
          + Permissions.PAYMENT_METHOD_MANAGE
          + "', '"
          + Permissions.INVOICE_READ
          + "')")
  @Transactional(readOnly = true)
  public List<MethodView> methods() {
    return store.methods();
  }

  @PreAuthorize("hasAuthority('" + Permissions.PAYMENT_METHOD_MANAGE + "')")
  @Transactional
  public MethodView createMethod(CreateMethodRequest request) {
    CurrentUser user = CurrentUser.require();
    UUID id = UUID.randomUUID();
    try {
      store.insertMethod(
          id,
          user.organizationId(),
          request.code(),
          request.name().trim(),
          request.isCash(),
          user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict("A payment method with this code already exists");
    }
    audit(
        user, null, "PAYMENT_METHOD_CREATED", "PaymentMethod", id, Map.of("code", request.code()));
    return store.method(id).orElseThrow();
  }

  @PreAuthorize("hasAuthority('" + Permissions.PAYMENT_METHOD_MANAGE + "')")
  @Transactional
  public MethodView updateMethod(UUID id, UpdateMethodRequest request) {
    CurrentUser user = CurrentUser.require();
    MethodView current =
        store.method(id).orElseThrow(() -> new ApiException.NotFound("Payment method"));
    if (store.updateMethod(id, request.version(), request.name().trim(), request.status()) == 0) {
      throw new ApiException.Conflict("Someone else changed this method. Reload and try again.");
    }
    audit(
        user,
        null,
        "PAYMENT_METHOD_UPDATED",
        "PaymentMethod",
        id,
        Map.of("code", current.code(), "status", request.status().name()));
    return store.method(id).orElseThrow();
  }

  // --- shifts ----------------------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.PAYMENT_RECORD + "')")
  @Transactional
  public ShiftView openShift(OpenShiftRequest request) {
    CurrentUser user = CurrentUser.require();
    if (!user.scopeOf(Permissions.PAYMENT_RECORD).includes(request.facilityId())) {
      throw new ApiException.NotFound("Facility");
    }
    UUID id = UUID.randomUUID();
    try {
      store.insertShift(id, user.organizationId(), request.facilityId(), user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict("You already have an open shift. Close it first.");
    }
    audit(user, request.facilityId(), "SHIFT_OPENED", "CashierShift", id, Map.of());
    return view(store.shift(id).orElseThrow());
  }

  /** Your recent shifts, newest first (an open one, if any, is first). */
  @PreAuthorize("hasAuthority('" + Permissions.PAYMENT_RECORD + "')")
  @Transactional(readOnly = true)
  public List<ShiftView> myShifts() {
    CurrentUser user = CurrentUser.require();
    return store.shiftsOf(user.userId(), 20).stream().map(this::view).toList();
  }

  @PreAuthorize(
      "hasAnyAuthority('" + Permissions.PAYMENT_RECORD + "', '" + Permissions.SHIFT_REVIEW + "')")
  @Transactional(readOnly = true)
  public ShiftView shift(UUID id) {
    CurrentUser user = CurrentUser.require();
    ShiftRow shift =
        store
            .shift(id)
            .filter(s -> canSee(user, s))
            .orElseThrow(() -> new ApiException.NotFound("Shift"));
    audit(user, shift.facilityId(), "SHIFT_VIEWED", "CashierShift", id, Map.of());
    return view(shift);
  }

  /**
   * Closes your shift with the cash counted per currency. Every currency with cash payments must be
   * counted; any difference needs a note.
   */
  @PreAuthorize("hasAuthority('" + Permissions.PAYMENT_RECORD + "')")
  @Transactional
  public ShiftView closeShift(UUID id, CloseShiftRequest request) {
    CurrentUser user = CurrentUser.require();
    ShiftRow shift =
        store
            .lockShift(id)
            .filter(s -> s.cashier().id().equals(user.userId()))
            .orElseThrow(() -> new ApiException.NotFound("Shift"));
    if (shift.status() != ShiftStatus.OPEN) {
      throw new ApiException.InvalidTransition("This shift is already closed");
    }
    Map<String, BigDecimal> counted = new TreeMap<>();
    for (CountEntry c : request.counts()) {
      int digits = InvoiceMath.decimals(c.currency());
      if (digits < 0) {
        throw new ApiException.Invalid("counts", c.currency() + " is not an ISO 4217 currency");
      }
      if (c.counted().stripTrailingZeros().scale() > digits) {
        throw new ApiException.Invalid(
            "counts", c.currency() + " allows at most " + digits + " decimal places");
      }
      if (counted.put(c.currency(), c.counted()) != null) {
        throw new ApiException.Invalid("counts", c.currency() + " is counted twice");
      }
    }
    Map<String, BigDecimal> expected = expectedCash(store.totals(id), store.refundTotals(id));
    for (String currency : expected.keySet()) {
      if (!counted.containsKey(currency)) {
        throw new ApiException.Invalid("counts", "count the " + currency + " cash");
      }
    }
    List<CountView> counts = new ArrayList<>();
    boolean difference = false;
    for (var entry : counted.entrySet()) {
      BigDecimal exp = expected.getOrDefault(entry.getKey(), BigDecimal.ZERO);
      BigDecimal diff = entry.getValue().subtract(exp);
      difference |= diff.signum() != 0;
      counts.add(new CountView(entry.getKey(), exp, entry.getValue(), diff));
    }
    String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
    if (difference && note == null) {
      throw new ApiException.Invalid("note", "explain the difference between expected and counted");
    }
    if (store.closeShift(id, request.version(), note) == 0) {
      throw new ApiException.Conflict("This shift was changed. Reload and try again.");
    }
    counts.forEach(c -> store.insertCount(user.organizationId(), id, c));
    audit(
        user,
        shift.facilityId(),
        "SHIFT_CLOSED",
        "CashierShift",
        id,
        Map.of("difference", Boolean.toString(difference)));
    return view(store.shift(id).orElseThrow());
  }

  /** Closed shifts at facilities in scope, waiting for sign-off, oldest first. */
  @PreAuthorize("hasAuthority('" + Permissions.SHIFT_REVIEW + "')")
  @Transactional(readOnly = true)
  public List<ShiftView> shiftsToReview(UUID facilityId) {
    CurrentUser user = CurrentUser.require();
    FacilityScope scope = user.scopeOf(Permissions.SHIFT_REVIEW);
    if (facilityId != null) {
      if (!scope.includes(facilityId)) {
        throw new ApiException.NotFound("Facility");
      }
      scope = new FacilityScope(false, Set.of(facilityId));
    }
    List<ShiftView> result =
        store
            .shiftsWithStatus(
                scope.facilityIds(), scope.allFacilities(), ShiftStatus.CLOSED, MAX_LIST)
            .stream()
            .map(this::view)
            .toList();
    audit(user, facilityId, "SHIFTS_TO_REVIEW_VIEWED", "CashierShift", null, Map.of());
    return result;
  }

  @PreAuthorize("hasAuthority('" + Permissions.SHIFT_REVIEW + "')")
  @Transactional
  public ShiftView reviewShift(UUID id, ReviewRequest request) {
    CurrentUser user = CurrentUser.require();
    ShiftRow shift =
        store
            .shift(id)
            .filter(s -> user.scopeOf(Permissions.SHIFT_REVIEW).includes(s.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Shift"));
    if (shift.status() != ShiftStatus.CLOSED) {
      throw new ApiException.InvalidTransition(
          shift.status() == ShiftStatus.OPEN
              ? "This shift is still open"
              : "This shift has already been signed off");
    }
    if (shift.cashier().id().equals(user.userId())) {
      throw new ApiException.InvalidTransition(
          "This is your shift, so another person must sign it off");
    }
    if (store.reviewShift(id, request.version(), request.note().trim(), user.userId()) == 0) {
      throw new ApiException.Conflict("This shift was changed. Reload and try again.");
    }
    audit(user, shift.facilityId(), "SHIFT_REVIEWED", "CashierShift", id, Map.of());
    return view(store.shift(id).orElseThrow());
  }

  // --- payments --------------------------------------------------------------------------------

  /**
   * Records a payment on an issued invoice in your open shift. A retry with the same idempotency
   * key returns the payment already recorded instead of charging twice.
   */
  @PreAuthorize("hasAuthority('" + Permissions.PAYMENT_RECORD + "')")
  @Transactional
  public Recorded record(UUID invoiceId, RecordPaymentRequest request) {
    CurrentUser user = CurrentUser.require();
    var replay = store.byKey(request.idempotencyKey());
    if (replay.isPresent()) {
      PaymentView p = replay.get();
      boolean inScope =
          store
              .paymentFacility(p.id())
              .map(f -> user.scopeOf(Permissions.PAYMENT_RECORD).includes(f))
              .orElse(false);
      if (inScope
          && p.invoiceId().equals(invoiceId)
          && p.method().id().equals(request.methodId())
          && p.amount().compareTo(request.amount()) == 0) {
        return new Recorded(p, false);
      }
      throw new ApiException.Conflict(
          "This payment key was already used for a different payment. Reload and try again.");
    }
    InvoiceRow invoice =
        invoices
            .invoice(invoiceId)
            .filter(i -> user.scopeOf(Permissions.PAYMENT_RECORD).includes(i.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Invoice"));
    if (invoice.status() != InvoiceStatus.ISSUED) {
      throw new ApiException.InvalidTransition("Only issued invoices can be paid");
    }
    ShiftRow shift =
        store
            .openShiftOf(user.userId())
            .flatMap(store::lockShift)
            .filter(s -> s.status() == ShiftStatus.OPEN)
            .orElseThrow(() ->
                new ApiException.InvalidTransition(
                    "Open your cashier shift before taking payments"));
    if (!shift.facilityId().equals(invoice.facilityId())) {
      throw new ApiException.InvalidTransition(
          "Your open shift is at another facility. Close it and open one here.");
    }
    MethodView method =
        store
            .method(request.methodId())
            .filter(m -> m.status() == ServiceStatus.ACTIVE)
            .orElseThrow(
                () -> new ApiException.Invalid("method_id", "is not an active payment method"));
    int digits = InvoiceMath.decimals(invoice.currency());
    BigDecimal amount = request.amount().stripTrailingZeros();
    if (amount.scale() > digits) {
      throw new ApiException.Invalid(
          "amount", invoice.currency() + " allows at most " + digits + " decimal places");
    }
    store.lockInvoice(invoiceId);
    if (invoices.invoice(invoiceId).map(InvoiceRow::status).orElse(null) != InvoiceStatus.ISSUED) {
      throw new ApiException.InvalidTransition("Only issued invoices can be paid");
    }
    BigDecimal balance = store.settlement(invoiceId).balance(invoice.total());
    if (balance.signum() <= 0) {
      throw new ApiException.InvalidTransition("This invoice is already fully paid");
    }
    if (amount.compareTo(balance) > 0) {
      throw new ApiException.InvalidTransition(
          "The amount is more than the balance due. Give change outside the system.");
    }
    UUID id = UUID.randomUUID();
    String receipt = "RCT-%06d".formatted(store.nextReceiptNumber(user.organizationId()));
    String reference =
        request.reference() == null || request.reference().isBlank()
            ? null
            : request.reference().trim();
    try {
      store.insertPayment(
          new NewPayment(
              id,
              user.organizationId(),
              invoice.facilityId(),
              invoiceId,
              shift.id(),
              method,
              invoice.currency(),
              amount,
              balance.subtract(amount),
              reference,
              receipt,
              request.idempotencyKey(),
              user.userId()));
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict(
          "This payment is already being recorded. Reload the invoice.");
    }
    audit(
        user,
        invoice.facilityId(),
        "PAYMENT_RECORDED",
        "Invoice",
        invoiceId,
        Map.of(
            "payment", id.toString(),
            "receipt", receipt,
            "method", method.code(),
            "currency", invoice.currency()));
    return new Recorded(store.payment(id).orElseThrow(), true);
  }

  @PreAuthorize(
      "hasAnyAuthority('" + Permissions.INVOICE_READ + "', '" + Permissions.PAYMENT_RECORD + "')")
  @Transactional(readOnly = true)
  public List<PaymentView> paymentsOf(UUID invoiceId) {
    CurrentUser user = CurrentUser.require();
    InvoiceRow invoice =
        invoices
            .invoice(invoiceId)
            .filter(i -> canRead(user, i.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Invoice"));
    audit(user, invoice.facilityId(), "PAYMENTS_VIEWED", "Invoice", invoiceId, Map.of());
    return store.paymentsOf(invoiceId);
  }

  /** The receipt of a payment (also for reprints; every view is audited). */
  @PreAuthorize(
      "hasAnyAuthority('" + Permissions.INVOICE_READ + "', '" + Permissions.PAYMENT_RECORD + "')")
  @Transactional(readOnly = true)
  public ReceiptView receipt(UUID paymentId) {
    CurrentUser user = CurrentUser.require();
    UUID facility =
        store
            .paymentFacility(paymentId)
            .filter(f -> canRead(user, f))
            .orElseThrow(() -> new ApiException.NotFound("Receipt"));
    PaymentView p = store.payment(paymentId).orElseThrow();
    InvoiceRow invoice = invoices.invoice(p.invoiceId()).orElseThrow();
    audit(
        user,
        facility,
        "RECEIPT_VIEWED",
        "Invoice",
        invoice.id(),
        Map.of("receipt", p.receiptNumber()));
    return new ReceiptView(
        PaymentApi.RECEIPT_FORMAT,
        "PAYMENT",
        p.receiptNumber(),
        store.facilityName(facility),
        p.receivedAt(),
        invoice.patient(),
        invoice.invoiceNumber(),
        invoice.total(),
        p.currency(),
        p.amount(),
        p.method().name(),
        p.reference(),
        p.balanceAfter(),
        p.receivedBy().displayName());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static boolean canRead(CurrentUser user, UUID facilityId) {
    return user.scopeOf(Permissions.INVOICE_READ).includes(facilityId)
        || user.scopeOf(Permissions.PAYMENT_RECORD).includes(facilityId);
  }

  private static boolean canSee(CurrentUser user, ShiftRow s) {
    return (s.cashier().id().equals(user.userId())
            && user.scopeOf(Permissions.PAYMENT_RECORD).includes(s.facilityId()))
        || user.scopeOf(Permissions.SHIFT_REVIEW).includes(s.facilityId());
  }

  /** Cash that should be in the drawer per currency: cash payments minus cash refunds. */
  static Map<String, BigDecimal> expectedCash(List<MethodTotal> totals, List<MethodTotal> refunds) {
    Map<String, BigDecimal> expected = new LinkedHashMap<>();
    for (MethodTotal t : totals) {
      if (t.isCash()) {
        expected.merge(t.currency(), t.amount(), BigDecimal::add);
      }
    }
    for (MethodTotal r : refunds) {
      if (r.isCash()) {
        expected.merge(r.currency(), r.amount().negate(), BigDecimal::add);
      }
    }
    expected.replaceAll((c, v) -> PaymentStore.strip(v));
    return expected;
  }

  private ShiftView view(ShiftRow s) {
    List<MethodTotal> totals = store.totals(s.id());
    List<MethodTotal> refunds = store.refundTotals(s.id());
    List<CurrencyAmount> expected =
        expectedCash(totals, refunds).entrySet().stream()
            .map(e -> new CurrencyAmount(e.getKey(), e.getValue()))
            .toList();
    return new ShiftView(
        s.id(),
        s.facilityId(),
        s.cashier(),
        s.status(),
        s.openedAt(),
        s.closedAt(),
        s.closeNote(),
        s.reviewedBy(),
        s.reviewedAt(),
        s.reviewNote(),
        totals,
        refunds,
        expected,
        store.counts(s.id()),
        s.version());
  }

  /** Audited after commit; metadata holds ids, codes and currencies, never amounts or notes. */
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
