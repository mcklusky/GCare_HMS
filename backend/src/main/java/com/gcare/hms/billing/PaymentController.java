package com.gcare.hms.billing;

import com.gcare.hms.billing.PaymentApi.CloseShiftRequest;
import com.gcare.hms.billing.PaymentApi.CreateMethodRequest;
import com.gcare.hms.billing.PaymentApi.MethodView;
import com.gcare.hms.billing.PaymentApi.OpenShiftRequest;
import com.gcare.hms.billing.PaymentApi.PaymentView;
import com.gcare.hms.billing.PaymentApi.ReceiptView;
import com.gcare.hms.billing.PaymentApi.RecordPaymentRequest;
import com.gcare.hms.billing.PaymentApi.ReviewRequest;
import com.gcare.hms.billing.PaymentApi.ShiftView;
import com.gcare.hms.billing.PaymentApi.UpdateMethodRequest;
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

/** Payment methods, payments, receipts and cashier shifts (task 4.3; docs/19 §4). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Payments")
class PaymentController {

  private final PaymentService service;

  PaymentController(PaymentService service) {
    this.service = service;
  }

  @GetMapping("/payment-methods")
  @Operation(summary = "Payment methods", description = "Empty until an administrator adds them.")
  List<MethodView> methods() {
    return service.methods();
  }

  @PostMapping("/payment-methods")
  @Operation(summary = "Add a payment method", description = "Needs PAYMENT_METHOD_MANAGE.")
  ResponseEntity<MethodView> createMethod(@Valid @RequestBody CreateMethodRequest request) {
    MethodView created = service.createMethod(request);
    return ResponseEntity.created(URI.create("/api/v1/payment-methods/" + created.id()))
        .body(created);
  }

  @PutMapping("/payment-methods/{id}")
  @Operation(
      summary = "Rename or deactivate a method",
      description = "Needs PAYMENT_METHOD_MANAGE.")
  MethodView updateMethod(@PathVariable UUID id, @Valid @RequestBody UpdateMethodRequest request) {
    return service.updateMethod(id, request);
  }

  @PostMapping("/invoices/{id}/payments")
  @Operation(
      summary = "Record a payment",
      description =
          "Needs PAYMENT_RECORD and an open shift at the invoice's facility. Idempotent by"
              + " idempotency_key: a retry returns 200 with the same payment.")
  ResponseEntity<PaymentView> record(
      @PathVariable UUID id, @Valid @RequestBody RecordPaymentRequest request) {
    PaymentService.Recorded recorded = service.record(id, request);
    return recorded.created()
        ? ResponseEntity.created(URI.create("/api/v1/payments/" + recorded.payment().id()))
            .body(recorded.payment())
        : ResponseEntity.ok(recorded.payment());
  }

  @GetMapping("/invoices/{id}/payments")
  @Operation(
      summary = "Payments of an invoice",
      description = "Needs INVOICE_READ or PAYMENT_RECORD.")
  List<PaymentView> payments(@PathVariable UUID id) {
    return service.paymentsOf(id);
  }

  @GetMapping("/payments/{id}/receipt")
  @Operation(summary = "Receipt", description = "Basic, PROVISIONAL format. Every view is audited.")
  ReceiptView receipt(@PathVariable UUID id) {
    return service.receipt(id);
  }

  @PostMapping("/shifts")
  @Operation(
      summary = "Open your shift",
      description = "Needs PAYMENT_RECORD. One open shift each.")
  ResponseEntity<ShiftView> openShift(@Valid @RequestBody OpenShiftRequest request) {
    ShiftView created = service.openShift(request);
    return ResponseEntity.created(URI.create("/api/v1/shifts/" + created.id())).body(created);
  }

  @GetMapping("/shifts/mine")
  @Operation(summary = "Your recent shifts", description = "Needs PAYMENT_RECORD.")
  List<ShiftView> myShifts() {
    return service.myShifts();
  }

  @GetMapping("/shifts/to-review")
  @Operation(summary = "Closed shifts waiting for sign-off", description = "Needs SHIFT_REVIEW.")
  List<ShiftView> toReview(@RequestParam(name = "facility_id", required = false) UUID facilityId) {
    return service.shiftsToReview(facilityId);
  }

  @GetMapping("/shifts/{id}")
  @Operation(summary = "A shift", description = "Your own, or any in scope with SHIFT_REVIEW.")
  ShiftView shift(@PathVariable UUID id) {
    return service.shift(id);
  }

  @PostMapping("/shifts/{id}/close")
  @Operation(
      summary = "Close your shift",
      description = "Counted cash per currency; a difference needs a note.")
  ShiftView close(@PathVariable UUID id, @Valid @RequestBody CloseShiftRequest request) {
    return service.closeShift(id, request);
  }

  @PostMapping("/shifts/{id}/review")
  @Operation(summary = "Sign off a closed shift", description = "Needs SHIFT_REVIEW; not your own.")
  ShiftView review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
    return service.reviewShift(id, request);
  }
}
