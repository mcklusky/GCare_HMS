package com.gcare.hms.billing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Billable services, dated prices and tax rates (task 4.1; FR-012; docs/19). Money is {@link
 * BigDecimal} with an explicit ISO 4217 currency; nothing is converted between currencies.
 */
public final class BillingApi {

  private BillingApi() {}

  private static final String CODE = "^[A-Z0-9][A-Z0-9_-]*$";
  private static final String CODE_MESSAGE = "use capital letters, digits, - or _";

  public enum ServiceStatus {
    ACTIVE,
    INACTIVE
  }

  public enum DecisionStatus {
    PROPOSED,
    APPROVED,
    REJECTED
  }

  // --- services --------------------------------------------------------------------------------

  public record CreateServiceRequest(
      @NotBlank @Size(max = 50) @Pattern(regexp = CODE, message = CODE_MESSAGE) String code,
      @NotBlank @Size(max = 200) String name,
      @JsonProperty("order_catalog_item_id") UUID orderCatalogItemId) {}

  public record UpdateServiceRequest(
      long version,
      @NotBlank @Size(max = 200) String name,
      @JsonProperty("order_catalog_item_id") UUID orderCatalogItemId,
      @NotNull ServiceStatus status) {}

  public record ServiceView(
      UUID id,
      String code,
      String name,
      @JsonProperty("order_catalog_item_id") UUID orderCatalogItemId,
      ServiceStatus status,
      /** Approved prices in effect today, one per currency. */
      @JsonProperty("current_prices") List<PriceView> currentPrices,
      long version) {}

  // --- prices ----------------------------------------------------------------------------------

  public record ProposePriceRequest(
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code, e.g. USD")
          String currency,
      @NotNull @DecimalMin("0") BigDecimal amount,
      @Size(max = 30) @Pattern(regexp = CODE, message = CODE_MESSAGE) @JsonProperty("tax_rate_code")
          String taxRateCode,
      @NotNull @JsonProperty("effective_from") LocalDate effectiveFrom,
      @NotBlank @Size(max = 500) String reason) {}

  public record PriceView(
      UUID id,
      @JsonProperty("service_id") UUID serviceId,
      @JsonProperty("service_code") String serviceCode,
      @JsonProperty("service_name") String serviceName,
      String currency,
      BigDecimal amount,
      @JsonProperty("tax_rate_code") String taxRateCode,
      @JsonProperty("effective_from") LocalDate effectiveFrom,
      DecisionStatus status,
      String reason,
      @JsonProperty("proposed_by") UserRef proposedBy,
      @JsonProperty("proposed_at") Instant proposedAt,
      @JsonProperty("decided_by") UserRef decidedBy,
      @JsonProperty("decided_at") Instant decidedAt,
      @JsonProperty("decision_note") String decisionNote,
      long version) {}

  public record DecisionRequest(long version, @NotBlank @Size(max = 500) String note) {}

  // --- tax rates -------------------------------------------------------------------------------

  public record ProposeTaxRateRequest(
      @NotBlank @Size(max = 30) @Pattern(regexp = CODE, message = CODE_MESSAGE) String code,
      @NotBlank @Size(max = 200) String name,
      @NotNull @DecimalMin("0") @DecimalMax("100") @JsonProperty("rate_percent")
          BigDecimal ratePercent,
      @NotNull @JsonProperty("effective_from") LocalDate effectiveFrom,
      @NotBlank @Size(max = 500) String reason) {}

  public record TaxRateView(
      UUID id,
      String code,
      String name,
      @JsonProperty("rate_percent") BigDecimal ratePercent,
      @JsonProperty("effective_from") LocalDate effectiveFrom,
      DecisionStatus status,
      String reason,
      @JsonProperty("proposed_by") UserRef proposedBy,
      @JsonProperty("proposed_at") Instant proposedAt,
      @JsonProperty("decided_by") UserRef decidedBy,
      @JsonProperty("decided_at") Instant decidedAt,
      @JsonProperty("decision_note") String decisionNote,
      long version) {}

  // --- lookups ---------------------------------------------------------------------------------

  public record Approvals(
      List<PriceView> prices, @JsonProperty("tax_rates") List<TaxRateView> taxRates) {}

  /**
   * The price and tax rate in effect for a service, currency and date. Invoices (task 4.2) copy
   * these values; they never refer back to a price that could be re-read later.
   */
  public record EffectivePrice(
      @JsonProperty("service_id") UUID serviceId,
      String currency,
      LocalDate date,
      PriceView price,
      @JsonProperty("tax_rate") TaxRateView taxRate) {}
}
