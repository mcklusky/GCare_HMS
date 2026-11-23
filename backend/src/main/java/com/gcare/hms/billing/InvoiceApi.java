package com.gcare.hms.billing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Visit invoices (task 4.2; FR-017; docs/19 §3). One currency per invoice, never converted; every
 * line copies the approved price and tax rate in effect when it was added.
 */
public final class InvoiceApi {

  private InvoiceApi() {}

  public enum InvoiceStatus {
    DRAFT,
    ISSUED,
    VOID
  }

  public enum LineSource {
    ORDER,
    MANUAL
  }

  public enum LineStatus {
    ACTIVE,
    REMOVED
  }

  /** Why an order of the visit is not on the invoice yet; {@code null} means it can be added. */
  public enum UnbilledProblem {
    NO_PRICE,
    NO_TAX_RATE
  }

  public record CreateInvoiceRequest(
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code, e.g. USD")
          String currency) {}

  public record AddLineRequest(
      long version,
      @NotNull @JsonProperty("service_id") UUID serviceId,
      @NotNull @Min(1) @Max(999) Integer quantity,
      @NotBlank @Size(max = 500) String reason) {}

  public record VersionRequest(long version) {}

  public record ReasonRequest(long version, @NotBlank @Size(max = 500) String reason) {}

  public record EncounterRef(
      UUID id,
      String status,
      @JsonProperty("department_name") String departmentName,
      @JsonProperty("arrived_at") Instant arrivedAt) {}

  public record LineView(
      UUID id,
      LineSource source,
      @JsonProperty("order_id") UUID orderId,
      /** Current status of the order, for display (a cancelled order must be removed). */
      @JsonProperty("order_status") String orderStatus,
      @JsonProperty("service_id") UUID serviceId,
      @JsonProperty("service_code") String serviceCode,
      @JsonProperty("service_name") String serviceName,
      int quantity,
      @JsonProperty("unit_price") BigDecimal unitPrice,
      @JsonProperty("tax_rate_code") String taxRateCode,
      @JsonProperty("tax_rate_percent") BigDecimal taxRatePercent,
      @JsonProperty("net_amount") BigDecimal netAmount,
      @JsonProperty("tax_amount") BigDecimal taxAmount,
      @JsonProperty("line_total") BigDecimal lineTotal,
      LineStatus status,
      String reason,
      @JsonProperty("added_by") UserRef addedBy,
      @JsonProperty("added_at") Instant addedAt,
      @JsonProperty("removed_reason") String removedReason,
      @JsonProperty("removed_by") UserRef removedBy,
      @JsonProperty("removed_at") Instant removedAt) {}

  public record UnbilledOrder(
      @JsonProperty("order_id") UUID orderId,
      @JsonProperty("item_name") String itemName,
      @JsonProperty("service_code") String serviceCode,
      UnbilledProblem problem) {}

  public record InvoiceSummary(
      UUID id,
      @JsonProperty("invoice_number") String invoiceNumber,
      InvoiceStatus status,
      String currency,
      @JsonProperty("facility_id") UUID facilityId,
      @JsonProperty("encounter_id") UUID encounterId,
      PatientSummary patient,
      /** Fixed total once issued; the running total of active lines while a draft. */
      BigDecimal total,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("issued_at") Instant issuedAt,
      long version) {}

  public record InvoiceView(
      UUID id,
      @JsonProperty("invoice_number") String invoiceNumber,
      InvoiceStatus status,
      String currency,
      @JsonProperty("facility_id") UUID facilityId,
      EncounterRef encounter,
      PatientSummary patient,
      List<LineView> lines,
      @JsonProperty("unbilled_orders") List<UnbilledOrder> unbilledOrders,
      BigDecimal subtotal,
      @JsonProperty("tax_total") BigDecimal taxTotal,
      BigDecimal total,
      @JsonProperty("replaces_invoice_id") UUID replacesInvoiceId,
      @JsonProperty("created_by") UserRef createdBy,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("issued_by") UserRef issuedBy,
      @JsonProperty("issued_at") Instant issuedAt,
      @JsonProperty("void_reason") String voidReason,
      @JsonProperty("voided_by") UserRef voidedBy,
      @JsonProperty("voided_at") Instant voidedAt,
      long version) {}

  /** A visit at a facility in scope that has no draft or issued invoice. */
  public record BillableVisit(
      @JsonProperty("encounter_id") UUID encounterId,
      @JsonProperty("facility_id") UUID facilityId,
      PatientSummary patient,
      @JsonProperty("department_name") String departmentName,
      String status,
      @JsonProperty("arrived_at") Instant arrivedAt) {}
}
