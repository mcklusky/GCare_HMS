package com.gcare.hms.billing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.billing.BillingApi.ServiceStatus;
import com.gcare.hms.billing.PaymentApi.MethodRef;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Refunds of payments and fee exemptions, each approved by a different person (task 4.4; FR-018,
 * FR-019; docs/19 §5). Amounts are in the invoice's currency; nothing is converted.
 */
public final class AdjustmentApi {

  private AdjustmentApi() {}

  public enum RefundStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    PAID
  }

  public enum ExemptionStatus {
    REQUESTED,
    APPROVED,
    REJECTED
  }

  // --- reasons ---------------------------------------------------------------------------------

  public record CreateReasonRequest(
      @NotBlank
          @Size(max = 30)
          @Pattern(
              regexp = "^[A-Z0-9][A-Z0-9_-]*$",
              message = "use capital letters, digits, - or _")
          String code,
      @NotBlank @Size(max = 200) String name) {}

  public record UpdateReasonRequest(
      long version, @NotBlank @Size(max = 200) String name, @NotNull ServiceStatus status) {}

  public record ReasonView(UUID id, String code, String name, ServiceStatus status, long version) {}

  // --- requests --------------------------------------------------------------------------------

  public record RefundRequest(
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
      @NotBlank @Size(max = 500) String reason) {}

  public record ExemptionRequest(
      @NotNull @JsonProperty("reason_id") UUID reasonId,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
      @NotBlank @Size(max = 500) String note) {}

  public record DecideRequest(long version, @NotBlank @Size(max = 500) String note) {}

  public record PayOutRequest(
      long version, @NotNull @JsonProperty("idempotency_key") UUID idempotencyKey) {}

  // --- views -----------------------------------------------------------------------------------

  public record RefundView(
      UUID id,
      @JsonProperty("invoice_id") UUID invoiceId,
      @JsonProperty("invoice_number") String invoiceNumber,
      @JsonProperty("facility_id") UUID facilityId,
      @JsonProperty("payment_id") UUID paymentId,
      @JsonProperty("payment_receipt_number") String paymentReceiptNumber,
      MethodRef method,
      String currency,
      BigDecimal amount,
      String reason,
      RefundStatus status,
      @JsonProperty("requested_by") UserRef requestedBy,
      @JsonProperty("requested_at") Instant requestedAt,
      @JsonProperty("decided_by") UserRef decidedBy,
      @JsonProperty("decided_at") Instant decidedAt,
      @JsonProperty("decision_note") String decisionNote,
      @JsonProperty("paid_by") UserRef paidBy,
      @JsonProperty("paid_at") Instant paidAt,
      @JsonProperty("receipt_number") String receiptNumber,
      /** Invoice balance right after the refund was paid out. */
      @JsonProperty("balance_after") BigDecimal balanceAfter,
      long version) {}

  public record ExemptionView(
      UUID id,
      @JsonProperty("invoice_id") UUID invoiceId,
      @JsonProperty("invoice_number") String invoiceNumber,
      @JsonProperty("facility_id") UUID facilityId,
      ReasonView reason,
      String currency,
      BigDecimal amount,
      String note,
      ExemptionStatus status,
      @JsonProperty("requested_by") UserRef requestedBy,
      @JsonProperty("requested_at") Instant requestedAt,
      @JsonProperty("decided_by") UserRef decidedBy,
      @JsonProperty("decided_at") Instant decidedAt,
      @JsonProperty("decision_note") String decisionNote,
      long version) {}

  /** Refunds and exemptions of one invoice. */
  public record Adjustments(List<RefundView> refunds, List<ExemptionView> exemptions) {}
}
