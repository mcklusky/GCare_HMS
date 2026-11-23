package com.gcare.hms.billing;

import com.gcare.hms.billing.BillingApi.Approvals;
import com.gcare.hms.billing.BillingApi.CreateServiceRequest;
import com.gcare.hms.billing.BillingApi.DecisionRequest;
import com.gcare.hms.billing.BillingApi.DecisionStatus;
import com.gcare.hms.billing.BillingApi.EffectivePrice;
import com.gcare.hms.billing.BillingApi.PriceView;
import com.gcare.hms.billing.BillingApi.ProposePriceRequest;
import com.gcare.hms.billing.BillingApi.ProposeTaxRateRequest;
import com.gcare.hms.billing.BillingApi.ServiceView;
import com.gcare.hms.billing.BillingApi.TaxRateView;
import com.gcare.hms.billing.BillingApi.UpdateServiceRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Billable services, prices and tax rates (docs/19). */
@RestController
@RequestMapping("/api/v1/billing")
@Tag(name = "Tariffs")
class TariffController {

  private final TariffService service;

  TariffController(TariffService service) {
    this.service = service;
  }

  @GetMapping("/services")
  @Operation(
      summary = "Billable services",
      description = "Needs BILLING_READ. With the approved prices in effect today.")
  List<ServiceView> services() {
    return service.services();
  }

  @PostMapping("/services")
  @Operation(summary = "Add a billable service", description = "Needs BILLING_CATALOG_MANAGE.")
  ResponseEntity<ServiceView> createService(@Valid @RequestBody CreateServiceRequest request) {
    ServiceView created = service.createService(request);
    return ResponseEntity.created(URI.create("/api/v1/billing/services/" + created.id()))
        .body(created);
  }

  @PutMapping("/services/{id}")
  @Operation(
      summary = "Change a billable service",
      description = "Name, linked orderable item, status. Never deleted.")
  ServiceView updateService(
      @PathVariable UUID id, @Valid @RequestBody UpdateServiceRequest request) {
    return service.updateService(id, request);
  }

  @GetMapping("/services/{id}/prices")
  @Operation(summary = "Price history of a service", description = "All proposals and decisions.")
  List<PriceView> prices(@PathVariable UUID id) {
    return service.pricesOf(id);
  }

  @PostMapping("/services/{id}/prices")
  @Operation(
      summary = "Propose a price",
      description = "Needs PRICE_PROPOSE. ISO 4217 currency; effective today or later.")
  ResponseEntity<PriceView> proposePrice(
      @PathVariable UUID id, @Valid @RequestBody ProposePriceRequest request) {
    PriceView created = service.proposePrice(id, request);
    return ResponseEntity.created(URI.create("/api/v1/billing/prices/" + created.id()))
        .body(created);
  }

  @PostMapping("/prices/{id}/approve")
  @Operation(
      summary = "Approve a price",
      description = "Needs PRICE_APPROVE; not your own proposal.")
  PriceView approvePrice(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.decidePrice(id, DecisionStatus.APPROVED, request);
  }

  @PostMapping("/prices/{id}/reject")
  @Operation(
      summary = "Reject a price",
      description = "Needs PRICE_APPROVE; not your own proposal.")
  PriceView rejectPrice(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.decidePrice(id, DecisionStatus.REJECTED, request);
  }

  @GetMapping("/tax-rates")
  @Operation(
      summary = "Tax rates",
      description = "Needs BILLING_READ. Empty until finance proposes and approves rates (OD-11).")
  List<TaxRateView> taxRates() {
    return service.taxRates();
  }

  @PostMapping("/tax-rates")
  @Operation(summary = "Propose a tax rate", description = "Needs PRICE_PROPOSE.")
  ResponseEntity<TaxRateView> proposeTaxRate(@Valid @RequestBody ProposeTaxRateRequest request) {
    TaxRateView created = service.proposeTaxRate(request);
    return ResponseEntity.created(URI.create("/api/v1/billing/tax-rates/" + created.id()))
        .body(created);
  }

  @PostMapping("/tax-rates/{id}/approve")
  @Operation(
      summary = "Approve a tax rate",
      description = "Needs PRICE_APPROVE; not your own proposal.")
  TaxRateView approveTaxRate(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.decideTaxRate(id, DecisionStatus.APPROVED, request);
  }

  @PostMapping("/tax-rates/{id}/reject")
  @Operation(
      summary = "Reject a tax rate",
      description = "Needs PRICE_APPROVE; not your own proposal.")
  TaxRateView rejectTaxRate(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.decideTaxRate(id, DecisionStatus.REJECTED, request);
  }

  @GetMapping("/approvals")
  @Operation(summary = "Proposals waiting for approval", description = "Needs PRICE_APPROVE.")
  Approvals approvals() {
    return service.pending();
  }

  @GetMapping("/effective-price")
  @Operation(
      summary = "Price in effect",
      description = "Approved price and tax rate for a service, currency and date (default today).")
  EffectivePrice effective(
      @RequestParam(name = "service_id") UUID serviceId,
      @RequestParam String currency,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return service.effective(serviceId, currency, date);
  }
}
