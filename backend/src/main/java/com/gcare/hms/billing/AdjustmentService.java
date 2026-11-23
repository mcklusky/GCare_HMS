package com.gcare.hms.billing;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.billing.AdjustmentApi.Adjustments;
import com.gcare.hms.billing.AdjustmentApi.CreateReasonRequest;
import com.gcare.hms.billing.AdjustmentApi.DecideRequest;
import com.gcare.hms.billing.AdjustmentApi.ExemptionRequest;
import com.gcare.hms.billing.AdjustmentApi.ExemptionStatus;
import com.gcare.hms.billing.AdjustmentApi.ExemptionView;
import com.gcare.hms.billing.AdjustmentApi.PayOutRequest;
import com.gcare.hms.billing.AdjustmentApi.ReasonView;
import com.gcare.hms.billing.AdjustmentApi.RefundRequest;
import com.gcare.hms.billing.AdjustmentApi.RefundStatus;
import com.gcare.hms.billing.AdjustmentApi.RefundView;
import com.gcare.hms.billing.AdjustmentApi.UpdateReasonRequest;
import com.gcare.hms.billing.BillingApi.ServiceStatus;
import com.gcare.hms.billing.InvoiceApi.InvoiceStatus;
import com.gcare.hms.billing.InvoiceStore.InvoiceRow;
import com.gcare.hms.billing.PaymentApi.PaymentView;
import com.gcare.hms.billing.PaymentApi.ReceiptView;
import com.gcare.hms.billing.PaymentApi.ShiftStatus;
import com.gcare.hms.billing.PaymentStore.ShiftRow;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.FacilityScope;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Refunds and fee exemptions (task 4.4; FR-018, FR-019; docs/19 §5). Decided 2026-11-23: a refund
 * of a payment is requested with a reason, approved by a different person, then paid out by a
 * cashier in an open shift (cash refunds reduce the shift's expected cash); the payment itself
 * never changes. An exemption waives a stated amount up to the balance due, with an admin-managed
 * reason and approval by a different person. Balance due = total − exempted − paid + refunded.
 */
@Service
public class AdjustmentService {

  private static final int MAX_LIST = 200;

  private final AdjustmentStore store;
  private final PaymentStore payments;
  private final InvoiceStore invoices;
  private final AuditService audit;

  AdjustmentService(
      AdjustmentStore store, PaymentStore payments, InvoiceStore invoices, AuditService audit) {
    this.store = store;
    this.payments = payments;
    this.invoices = invoices;
    this.audit = audit;
  }

  // --- exemption reasons -----------------------------------------------------------------------

  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.EXEMPTION_REASON_MANAGE
          + "', '"
          + Permissions.EXEMPTION_REQUEST
          + "', '"
          + Permissions.EXEMPTION_APPROVE
          + "')")
  @Transactional(readOnly = true)
  public List<ReasonView> reasons() {
    return store.reasons();
  }

  @PreAuthorize("hasAuthority('" + Permissions.EXEMPTION_REASON_MANAGE + "')")
  @Transactional
  public ReasonView createReason(CreateReasonRequest request) {
    CurrentUser user = CurrentUser.require();
    UUID id = UUID.randomUUID();
    try {
      store.insertReason(
          id, user.organizationId(), request.code(), request.name().trim(), user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict("A reason with this code already exists");
    }
    audit(
        user,
        null,
        "EXEMPTION_REASON_CREATED",
        "ExemptionReason",
        id,
        Map.of("code", request.code()));
    return store.reason(id).orElseThrow();
  }

  @PreAuthorize("hasAuthority('" + Permissions.EXEMPTION_REASON_MANAGE + "')")
  @Transactional
  public ReasonView updateReason(UUID id, UpdateReasonRequest request) {
    CurrentUser user = CurrentUser.require();
    ReasonView current =
        store.reason(id).orElseThrow(() -> new ApiException.NotFound("Exemption reason"));
    if (store.updateReason(id, request.version(), request.name().trim(), request.status()) == 0) {
      throw new ApiException.Conflict("Someone else changed this reason. Reload and try again.");
    }
    audit(
        user,
        null,
        "EXEMPTION_REASON_UPDATED",
        "ExemptionReason",
        id,
        Map.of("code", current.code(), "status", request.status().name()));
    return store.reason(id).orElseThrow();
  }

  // --- lists -----------------------------------------------------------------------------------

  @PreAuthorize(
      "hasAnyAuthority('" + Permissions.INVOICE_READ + "', '" + Permissions.PAYMENT_RECORD + "')")
  @Transactional(readOnly = true)
  public Adjustments adjustmentsOf(UUID invoiceId) {
    CurrentUser user = CurrentUser.require();
    InvoiceRow invoice =
        invoices
            .invoice(invoiceId)
            .filter(i ->
                user.scopeOf(Permissions.INVOICE_READ).includes(i.facilityId())
                    || user.scopeOf(Permissions.PAYMENT_RECORD).includes(i.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Invoice"));
    audit(user, invoice.facilityId(), "ADJUSTMENTS_VIEWED", "Invoice", invoiceId, Map.of());
    return new Adjustments(store.refundsOf(invoiceId), store.exemptionsOf(invoiceId));
  }

  /** Refund and exemption requests waiting for a decision at facilities in scope. */
  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.REFUND_APPROVE
          + "', '"
          + Permissions.EXEMPTION_APPROVE
          + "')")
  @Transactional(readOnly = true)
  public Adjustments pending(UUID facilityId) {
    CurrentUser user = CurrentUser.require();
    FacilityScope refunds = narrow(user.scopeOf(Permissions.REFUND_APPROVE), facilityId);
    FacilityScope exemptions = narrow(user.scopeOf(Permissions.EXEMPTION_APPROVE), facilityId);
    if (facilityId != null && refunds.isEmpty() && exemptions.isEmpty()) {
      throw new ApiException.NotFound("Facility");
    }
    Adjustments result =
        new Adjustments(
            store.refundsWithStatus(
                refunds.facilityIds(), refunds.allFacilities(), RefundStatus.REQUESTED, MAX_LIST),
            store.exemptionsWithStatus(
                exemptions.facilityIds(),
                exemptions.allFacilities(),
                ExemptionStatus.REQUESTED,
                MAX_LIST));
    audit(user, facilityId, "ADJUSTMENT_APPROVALS_VIEWED", "Invoice", null, Map.of());
    return result;
  }

  // --- refunds ---------------------------------------------------------------------------------

  /** Requests a refund of (part of) a payment. A different person must approve it. */
  @PreAuthorize("hasAuthority('" + Permissions.REFUND_REQUEST + "')")
  @Transactional
  public RefundView requestRefund(UUID paymentId, RefundRequest request) {
    CurrentUser user = CurrentUser.require();
    UUID facility =
        payments
            .paymentFacility(paymentId)
            .filter(f -> user.scopeOf(Permissions.REFUND_REQUEST).includes(f))
            .orElseThrow(() -> new ApiException.NotFound("Payment"));
    PaymentView payment = payments.payment(paymentId).orElseThrow();
    BigDecimal amount = checkDecimals(payment.currency(), request.amount());
    payments.lockInvoice(payment.invoiceId());
    BigDecimal refundable = payment.amount().subtract(store.committedRefunds(paymentId));
    if (amount.compareTo(refundable) > 0) {
      throw new ApiException.InvalidTransition(
          refundable.signum() <= 0
              ? "This payment has already been refunded or has a refund waiting"
              : "The amount is more than can still be refunded from this payment");
    }
    UUID id = UUID.randomUUID();
    store.insertRefund(
        id,
        user.organizationId(),
        payment,
        facility,
        amount,
        request.reason().trim(),
        user.userId());
    audit(
        user,
        facility,
        "REFUND_REQUESTED",
        "Invoice",
        payment.invoiceId(),
        Map.of("refund", id.toString(), "payment_receipt", payment.receiptNumber()));
    return store.refund(id).orElseThrow();
  }

  @PreAuthorize("hasAuthority('" + Permissions.REFUND_APPROVE + "')")
  @Transactional
  public RefundView decideRefund(UUID id, RefundStatus decision, DecideRequest request) {
    CurrentUser user = CurrentUser.require();
    RefundView refund =
        store
            .refund(id)
            .filter(r -> user.scopeOf(Permissions.REFUND_APPROVE).includes(r.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Refund"));
    checkDecidable(user, refund.status() == RefundStatus.REQUESTED, refund.requestedBy().id());
    if (store.decideRefund(id, request.version(), decision, request.note().trim(), user.userId())
        == 0) {
      throw new ApiException.Conflict("This request was changed. Reload it.");
    }
    audit(
        user,
        refund.facilityId(),
        "REFUND_" + decision.name(),
        "Invoice",
        refund.invoiceId(),
        Map.of("refund", id.toString()));
    return store.refund(id).orElseThrow();
  }

  /**
   * Pays out an approved refund in your open shift. Cash refunds come from the drawer, so the shift
   * must have recorded at least that much cash in the currency. A retry with the same key returns
   * the refund already paid.
   */
  @PreAuthorize("hasAuthority('" + Permissions.REFUND_REQUEST + "')")
  @Transactional
  public RefundView payRefund(UUID id, PayOutRequest request) {
    CurrentUser user = CurrentUser.require();
    var replay = store.refundByPayoutKey(request.idempotencyKey());
    if (replay.isPresent()) {
      if (replay.get().id().equals(id)
          && user.scopeOf(Permissions.REFUND_REQUEST).includes(replay.get().facilityId())) {
        return replay.get();
      }
      throw new ApiException.Conflict("This pay-out key was already used. Reload and try again.");
    }
    RefundView refund =
        store
            .refund(id)
            .filter(r -> user.scopeOf(Permissions.REFUND_REQUEST).includes(r.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Refund"));
    if (refund.status() != RefundStatus.APPROVED) {
      throw new ApiException.InvalidTransition(
          refund.status() == RefundStatus.PAID
              ? "This refund has already been paid out"
              : "Only approved refunds can be paid out");
    }
    ShiftRow shift =
        payments
            .openShiftOf(user.userId())
            .flatMap(payments::lockShift)
            .filter(s -> s.status() == ShiftStatus.OPEN)
            .orElseThrow(() ->
                new ApiException.InvalidTransition(
                    "Open your cashier shift before paying out refunds"));
    if (!shift.facilityId().equals(refund.facilityId())) {
      throw new ApiException.InvalidTransition(
          "Your open shift is at another facility. Close it and open one here.");
    }
    if (refund.method().isCash()) {
      BigDecimal drawer =
          PaymentService.expectedCash(
                  payments.totals(shift.id()), payments.refundTotals(shift.id()))
              .getOrDefault(refund.currency(), BigDecimal.ZERO);
      if (drawer.compareTo(refund.amount()) < 0) {
        throw new ApiException.InvalidTransition(
            "Your shift has not taken enough "
                + refund.currency()
                + " cash to pay out this refund");
      }
    }
    payments.lockInvoice(refund.invoiceId());
    InvoiceRow invoice = invoices.invoice(refund.invoiceId()).orElseThrow();
    BigDecimal balanceAfter =
        payments.settlement(invoice.id()).balance(invoice.total()).add(refund.amount());
    String receipt = "RCT-%06d".formatted(payments.nextReceiptNumber(user.organizationId()));
    try {
      if (store.payRefund(
              id,
              refund.version(),
              shift.id(),
              receipt,
              request.idempotencyKey(),
              balanceAfter,
              user.userId())
          == 0) {
        throw new ApiException.Conflict("This refund was changed. Reload it.");
      }
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict("This refund is already being paid out. Reload it.");
    }
    audit(
        user,
        refund.facilityId(),
        "REFUND_PAID",
        "Invoice",
        refund.invoiceId(),
        Map.of("refund", id.toString(), "receipt", receipt, "method", refund.method().code()));
    return store.refund(id).orElseThrow();
  }

  /** Receipt for a paid-out refund (same numbering and layout as payment receipts). */
  @PreAuthorize(
      "hasAnyAuthority('" + Permissions.INVOICE_READ + "', '" + Permissions.PAYMENT_RECORD + "')")
  @Transactional(readOnly = true)
  public ReceiptView refundReceipt(UUID id) {
    CurrentUser user = CurrentUser.require();
    RefundView r =
        store
            .refund(id)
            .filter(x -> x.status() == RefundStatus.PAID)
            .filter(x ->
                user.scopeOf(Permissions.INVOICE_READ).includes(x.facilityId())
                    || user.scopeOf(Permissions.PAYMENT_RECORD).includes(x.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Receipt"));
    InvoiceRow invoice = invoices.invoice(r.invoiceId()).orElseThrow();
    audit(
        user,
        r.facilityId(),
        "RECEIPT_VIEWED",
        "Invoice",
        r.invoiceId(),
        Map.of("receipt", r.receiptNumber()));
    return new ReceiptView(
        PaymentApi.RECEIPT_FORMAT,
        "REFUND",
        r.receiptNumber(),
        payments.facilityName(r.facilityId()),
        r.paidAt(),
        invoice.patient(),
        invoice.invoiceNumber(),
        invoice.total(),
        r.currency(),
        r.amount(),
        r.method().name(),
        "Refund of " + r.paymentReceiptNumber(),
        r.balanceAfter(),
        r.paidBy().displayName());
  }

  // --- exemptions ------------------------------------------------------------------------------

  /** Requests an exemption of an amount up to the balance due. A different person approves it. */
  @PreAuthorize("hasAuthority('" + Permissions.EXEMPTION_REQUEST + "')")
  @Transactional
  public ExemptionView requestExemption(UUID invoiceId, ExemptionRequest request) {
    CurrentUser user = CurrentUser.require();
    InvoiceRow invoice =
        invoices
            .invoice(invoiceId)
            .filter(i -> user.scopeOf(Permissions.EXEMPTION_REQUEST).includes(i.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Invoice"));
    if (invoice.status() != InvoiceStatus.ISSUED) {
      throw new ApiException.InvalidTransition("Only issued invoices can have exemptions");
    }
    ReasonView reason =
        store
            .reason(request.reasonId())
            .filter(r -> r.status() == ServiceStatus.ACTIVE)
            .orElseThrow(() -> new ApiException.Invalid("reason_id", "is not an active reason"));
    BigDecimal amount = checkDecimals(invoice.currency(), request.amount());
    payments.lockInvoice(invoiceId);
    PaymentStore.Settlement settled = payments.settlement(invoiceId);
    BigDecimal available =
        invoice
            .total()
            .subtract(store.committedExemptions(invoiceId))
            .subtract(settled.paid())
            .add(settled.refunded());
    if (amount.compareTo(available) > 0) {
      throw new ApiException.InvalidTransition(
          "The amount is more than the balance due (after exemptions already requested)");
    }
    UUID id = UUID.randomUUID();
    store.insertExemption(
        id,
        user.organizationId(),
        invoice.facilityId(),
        invoiceId,
        reason.id(),
        invoice.currency(),
        amount,
        request.note().trim(),
        user.userId());
    audit(
        user,
        invoice.facilityId(),
        "EXEMPTION_REQUESTED",
        "Invoice",
        invoiceId,
        Map.of("exemption", id.toString(), "reason", reason.code()));
    return store.exemption(id).orElseThrow();
  }

  @PreAuthorize("hasAuthority('" + Permissions.EXEMPTION_APPROVE + "')")
  @Transactional
  public ExemptionView decideExemption(UUID id, ExemptionStatus decision, DecideRequest request) {
    CurrentUser user = CurrentUser.require();
    ExemptionView exemption =
        store
            .exemption(id)
            .filter(x -> user.scopeOf(Permissions.EXEMPTION_APPROVE).includes(x.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Exemption"));
    checkDecidable(
        user, exemption.status() == ExemptionStatus.REQUESTED, exemption.requestedBy().id());
    if (decision == ExemptionStatus.APPROVED) {
      payments.lockInvoice(exemption.invoiceId());
      InvoiceRow invoice = invoices.invoice(exemption.invoiceId()).orElseThrow();
      if (invoice.status() != InvoiceStatus.ISSUED) {
        throw new ApiException.InvalidTransition("The invoice is no longer issued");
      }
      BigDecimal balance = payments.settlement(invoice.id()).balance(invoice.total());
      if (exemption.amount().compareTo(balance) > 0) {
        throw new ApiException.InvalidTransition(
            "The balance due is now lower than this exemption. Reject it and request again.");
      }
    }
    if (store.decideExemption(id, request.version(), decision, request.note().trim(), user.userId())
        == 0) {
      throw new ApiException.Conflict("This request was changed. Reload it.");
    }
    audit(
        user,
        exemption.facilityId(),
        "EXEMPTION_" + decision.name(),
        "Invoice",
        exemption.invoiceId(),
        Map.of("exemption", id.toString()));
    return store.exemption(id).orElseThrow();
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static BigDecimal checkDecimals(String currency, BigDecimal amount) {
    int digits = InvoiceMath.decimals(currency);
    BigDecimal stripped = amount.stripTrailingZeros();
    if (stripped.scale() > digits) {
      throw new ApiException.Invalid(
          "amount", currency + " allows at most " + digits + " decimal places");
    }
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }

  private static void checkDecidable(CurrentUser user, boolean waiting, UUID requester) {
    if (!waiting) {
      throw new ApiException.InvalidTransition("This request has already been decided");
    }
    if (requester.equals(user.userId())) {
      throw new ApiException.InvalidTransition(
          "You requested this, so another person must decide it");
    }
  }

  private static FacilityScope narrow(FacilityScope scope, UUID facilityId) {
    if (facilityId == null) {
      return scope;
    }
    return scope.includes(facilityId)
        ? new FacilityScope(false, Set.of(facilityId))
        : FacilityScope.NONE;
  }

  /**
   * Audited after commit; metadata holds ids, codes and receipt numbers, never amounts or reasons.
   */
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
