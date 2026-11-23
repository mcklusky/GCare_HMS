package com.gcare.hms.billing;

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
import com.gcare.hms.billing.PaymentApi.ReceiptView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Refunds and fee exemptions (task 4.4; docs/19 §5). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Refunds and exemptions")
class AdjustmentController {

  private final AdjustmentService service;

  AdjustmentController(AdjustmentService service) {
    this.service = service;
  }

  @GetMapping("/exemption-reasons")
  @Operation(summary = "Exemption reasons", description = "Empty until an administrator adds them.")
  List<ReasonView> reasons() {
    return service.reasons();
  }

  @PostMapping("/exemption-reasons")
  @Operation(summary = "Add an exemption reason", description = "Needs EXEMPTION_REASON_MANAGE.")
  ResponseEntity<ReasonView> createReason(@Valid @RequestBody CreateReasonRequest request) {
    ReasonView created = service.createReason(request);
    return ResponseEntity.created(URI.create("/api/v1/exemption-reasons/" + created.id()))
        .body(created);
  }

  @PutMapping("/exemption-reasons/{id}")
  @Operation(
      summary = "Rename or deactivate a reason",
      description = "Needs EXEMPTION_REASON_MANAGE.")
  ReasonView updateReason(@PathVariable UUID id, @Valid @RequestBody UpdateReasonRequest request) {
    return service.updateReason(id, request);
  }

  @GetMapping("/invoices/{id}/adjustments")
  @Operation(summary = "Refunds and exemptions of an invoice")
  Adjustments adjustments(@PathVariable UUID id) {
    return service.adjustmentsOf(id);
  }

  @GetMapping("/adjustments/pending")
  @Operation(
      summary = "Requests waiting for a decision",
      description = "Refunds (REFUND_APPROVE) and exemptions (EXEMPTION_APPROVE).")
  Adjustments pending(@RequestParam(name = "facility_id", required = false) UUID facilityId) {
    return service.pending(facilityId);
  }

  @PostMapping("/payments/{id}/refunds")
  @Operation(summary = "Request a refund", description = "Needs REFUND_REQUEST.")
  ResponseEntity<RefundView> requestRefund(
      @PathVariable UUID id, @Valid @RequestBody RefundRequest request) {
    RefundView created = service.requestRefund(id, request);
    return ResponseEntity.created(URI.create("/api/v1/refunds/" + created.id())).body(created);
  }

  @PostMapping("/refunds/{id}/approve")
  @Operation(summary = "Approve a refund", description = "Needs REFUND_APPROVE; not your own.")
  RefundView approveRefund(@PathVariable UUID id, @Valid @RequestBody DecideRequest request) {
    return service.decideRefund(id, RefundStatus.APPROVED, request);
  }

  @PostMapping("/refunds/{id}/reject")
  @Operation(summary = "Reject a refund", description = "Needs REFUND_APPROVE; not your own.")
  RefundView rejectRefund(@PathVariable UUID id, @Valid @RequestBody DecideRequest request) {
    return service.decideRefund(id, RefundStatus.REJECTED, request);
  }

  @PostMapping("/refunds/{id}/pay")
  @Operation(
      summary = "Pay out an approved refund",
      description = "Needs REFUND_REQUEST and an open shift; idempotent by idempotency_key.")
  RefundView payRefund(@PathVariable UUID id, @Valid @RequestBody PayOutRequest request) {
    return service.payRefund(id, request);
  }

  @GetMapping("/refunds/{id}/receipt")
  @Operation(summary = "Refund receipt", description = "Basic, PROVISIONAL format; audited.")
  ReceiptView refundReceipt(@PathVariable UUID id) {
    return service.refundReceipt(id);
  }

  @PostMapping("/invoices/{id}/exemptions")
  @Operation(summary = "Request an exemption", description = "Needs EXEMPTION_REQUEST.")
  ResponseEntity<ExemptionView> requestExemption(
      @PathVariable UUID id, @Valid @RequestBody ExemptionRequest request) {
    ExemptionView created = service.requestExemption(id, request);
    return ResponseEntity.created(URI.create("/api/v1/exemptions/" + created.id())).body(created);
  }

  @PostMapping("/exemptions/{id}/approve")
  @Operation(
      summary = "Approve an exemption",
      description = "Needs EXEMPTION_APPROVE; not your own.")
  ExemptionView approveExemption(@PathVariable UUID id, @Valid @RequestBody DecideRequest request) {
    return service.decideExemption(id, ExemptionStatus.APPROVED, request);
  }

  @PostMapping("/exemptions/{id}/reject")
  @Operation(
      summary = "Reject an exemption",
      description = "Needs EXEMPTION_APPROVE; not your own.")
  ExemptionView rejectExemption(@PathVariable UUID id, @Valid @RequestBody DecideRequest request) {
    return service.decideExemption(id, ExemptionStatus.REJECTED, request);
  }
}
