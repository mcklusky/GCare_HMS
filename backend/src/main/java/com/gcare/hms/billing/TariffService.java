package com.gcare.hms.billing;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.billing.BillingApi.Approvals;
import com.gcare.hms.billing.BillingApi.CreateServiceRequest;
import com.gcare.hms.billing.BillingApi.DecisionRequest;
import com.gcare.hms.billing.BillingApi.DecisionStatus;
import com.gcare.hms.billing.BillingApi.EffectivePrice;
import com.gcare.hms.billing.BillingApi.PriceView;
import com.gcare.hms.billing.BillingApi.ProposePriceRequest;
import com.gcare.hms.billing.BillingApi.ProposeTaxRateRequest;
import com.gcare.hms.billing.BillingApi.ServiceStatus;
import com.gcare.hms.billing.BillingApi.ServiceView;
import com.gcare.hms.billing.BillingApi.TaxRateView;
import com.gcare.hms.billing.BillingApi.UpdateServiceRequest;
import com.gcare.hms.billing.BillingStore.ServiceRow;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Billable services, dated prices and tax rates (task 4.1; FR-012; docs/19). Prices start empty and
 * are entered by finance; a different person approves each price and tax rate; approved rows never
 * change. A price change is a new row with a later effective date, so earlier invoices keep the
 * price they were made with. Billing is organization-wide: the permission is checked, not the
 * facility. Dates are UTC calendar dates (pilot facility time zone is UTC; see docs/19).
 */
@Service
public class TariffService {

  private final BillingStore store;
  private final AuditService audit;
  private final Clock clock = Clock.systemUTC();

  TariffService(BillingStore store, AuditService audit) {
    this.store = store;
    this.audit = audit;
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  // --- services --------------------------------------------------------------------------------

  /** Services with the approved prices in effect today. */
  @PreAuthorize("hasAuthority('" + Permissions.BILLING_READ + "')")
  @Transactional(readOnly = true)
  public List<ServiceView> services() {
    Map<UUID, List<PriceView>> current = new HashMap<>();
    store
        .effectivePrices(today())
        .forEach(p -> current.computeIfAbsent(p.serviceId(), k -> new ArrayList<>()).add(p));
    return store.services().stream()
        .map(s -> view(s, current.getOrDefault(s.id(), List.of())))
        .toList();
  }

  @PreAuthorize("hasAuthority('" + Permissions.BILLING_CATALOG_MANAGE + "')")
  @Transactional
  public ServiceView createService(CreateServiceRequest request) {
    CurrentUser user = CurrentUser.require();
    checkOrderItem(request.orderCatalogItemId());
    UUID id = UUID.randomUUID();
    try {
      store.insertService(
          id,
          user.organizationId(),
          request.code(),
          request.name().trim(),
          request.orderCatalogItemId(),
          user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict(
          "A service with this code, or for this orderable item, already exists");
    }
    audit(user, "BILLABLE_SERVICE_CREATED", "BillableService", id, Map.of("code", request.code()));
    return view(store.service(id).orElseThrow(), List.of());
  }

  @PreAuthorize("hasAuthority('" + Permissions.BILLING_CATALOG_MANAGE + "')")
  @Transactional
  public ServiceView updateService(UUID id, UpdateServiceRequest request) {
    CurrentUser user = CurrentUser.require();
    ServiceRow current = store.service(id).orElseThrow(() -> new ApiException.NotFound("Service"));
    checkOrderItem(request.orderCatalogItemId());
    try {
      if (store.updateService(
              id,
              request.version(),
              request.name().trim(),
              request.orderCatalogItemId(),
              request.status())
          == 0) {
        throw new ApiException.Conflict("Someone else changed this service. Reload and try again.");
      }
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict("Another service is already linked to this orderable item");
    }
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("code", current.code());
    if (current.status() != request.status()) {
      metadata.put("status", request.status().name());
    }
    audit(user, "BILLABLE_SERVICE_UPDATED", "BillableService", id, metadata);
    return view(store.service(id).orElseThrow(), List.of());
  }

  private void checkOrderItem(UUID itemId) {
    if (itemId != null && !store.orderItemExists(itemId)) {
      throw new ApiException.Invalid("order_catalog_item_id", "is not an orderable item");
    }
  }

  // --- prices ----------------------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.BILLING_READ + "')")
  @Transactional(readOnly = true)
  public List<PriceView> pricesOf(UUID serviceId) {
    store.service(serviceId).orElseThrow(() -> new ApiException.NotFound("Service"));
    return store.pricesOf(serviceId);
  }

  /** Proposes a price from a date that is not in the past. Needs approval by someone else. */
  @PreAuthorize("hasAuthority('" + Permissions.PRICE_PROPOSE + "')")
  @Transactional
  public PriceView proposePrice(UUID serviceId, ProposePriceRequest request) {
    CurrentUser user = CurrentUser.require();
    ServiceRow service =
        store.service(serviceId).orElseThrow(() -> new ApiException.NotFound("Service"));
    if (service.status() != ServiceStatus.ACTIVE) {
      throw new ApiException.InvalidTransition("This service is inactive");
    }
    BigDecimal amount = checkAmount(request.currency(), request.amount());
    notInThePast("effective_from", request.effectiveFrom());
    if (request.taxRateCode() != null && !store.approvedRateExists(request.taxRateCode())) {
      throw new ApiException.Invalid("tax_rate_code", "has no approved tax rate");
    }
    UUID id = UUID.randomUUID();
    try {
      store.insertPrice(
          id,
          user.organizationId(),
          serviceId,
          request.currency(),
          amount,
          request.taxRateCode(),
          request.effectiveFrom(),
          request.reason().trim(),
          user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict(
          "A price for this currency and date is already proposed or approved");
    }
    audit(
        user,
        "PRICE_PROPOSED",
        "ServicePrice",
        id,
        Map.of("service", serviceId.toString(), "currency", request.currency()));
    return store.price(id).orElseThrow();
  }

  @PreAuthorize("hasAuthority('" + Permissions.PRICE_APPROVE + "')")
  @Transactional
  public PriceView decidePrice(UUID priceId, DecisionStatus decision, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    PriceView price = store.price(priceId).orElseThrow(() -> new ApiException.NotFound("Price"));
    checkDecidable(user, price.status(), price.proposedBy().id());
    if (decision == DecisionStatus.APPROVED) {
      if (price.effectiveFrom().isBefore(today())) {
        throw new ApiException.InvalidTransition(
            "The effective date has passed. Reject this proposal and propose the price again from a future date.");
      }
      if (price.taxRateCode() != null && !store.approvedRateExists(price.taxRateCode())) {
        throw new ApiException.InvalidTransition("The tax rate code has no approved tax rate");
      }
    }
    if (store.decidePrice(
            priceId, request.version(), decision, request.note().trim(), user.userId())
        == 0) {
      throw new ApiException.Conflict("This proposal was changed by someone else. Reload it.");
    }
    audit(
        user,
        "PRICE_" + decision.name(),
        "ServicePrice",
        priceId,
        Map.of("service", price.serviceId().toString()));
    return store.price(priceId).orElseThrow();
  }

  // --- tax rates -------------------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.BILLING_READ + "')")
  @Transactional(readOnly = true)
  public List<TaxRateView> taxRates() {
    return store.rates();
  }

  @PreAuthorize("hasAuthority('" + Permissions.PRICE_PROPOSE + "')")
  @Transactional
  public TaxRateView proposeTaxRate(ProposeTaxRateRequest request) {
    CurrentUser user = CurrentUser.require();
    if (request.ratePercent().stripTrailingZeros().scale() > 4) {
      throw new ApiException.Invalid("rate_percent", "has more than 4 decimal places");
    }
    notInThePast("effective_from", request.effectiveFrom());
    UUID id = UUID.randomUUID();
    try {
      store.insertRate(
          id,
          user.organizationId(),
          request.code(),
          request.name().trim(),
          request.ratePercent(),
          request.effectiveFrom(),
          request.reason().trim(),
          user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict(
          "A rate with this code and date is already proposed or approved");
    }
    audit(user, "TAX_RATE_PROPOSED", "TaxRate", id, Map.of("code", request.code()));
    return store.rate(id).orElseThrow();
  }

  @PreAuthorize("hasAuthority('" + Permissions.PRICE_APPROVE + "')")
  @Transactional
  public TaxRateView decideTaxRate(UUID rateId, DecisionStatus decision, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    TaxRateView rate = store.rate(rateId).orElseThrow(() -> new ApiException.NotFound("Tax rate"));
    checkDecidable(user, rate.status(), rate.proposedBy().id());
    if (decision == DecisionStatus.APPROVED && rate.effectiveFrom().isBefore(today())) {
      throw new ApiException.InvalidTransition(
          "The effective date has passed. Reject this proposal and propose the rate again from a future date.");
    }
    if (store.decideRate(rateId, request.version(), decision, request.note().trim(), user.userId())
        == 0) {
      throw new ApiException.Conflict("This proposal was changed by someone else. Reload it.");
    }
    audit(user, "TAX_RATE_" + decision.name(), "TaxRate", rateId, Map.of("code", rate.code()));
    return store.rate(rateId).orElseThrow();
  }

  // --- lookups ---------------------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.PRICE_APPROVE + "')")
  @Transactional(readOnly = true)
  public Approvals pending() {
    return new Approvals(
        store.pricesWithStatus(DecisionStatus.PROPOSED),
        store.ratesWithStatus(DecisionStatus.PROPOSED));
  }

  /** The approved price (and its tax rate) in effect for a service, currency and date. */
  @PreAuthorize("hasAuthority('" + Permissions.BILLING_READ + "')")
  @Transactional(readOnly = true)
  public EffectivePrice effective(UUID serviceId, String currency, LocalDate date) {
    LocalDate on = date == null ? today() : date;
    PriceView price =
        store
            .effectivePrice(serviceId, currency, on)
            .orElseThrow(() ->
                new ApiException.NotFound("Approved price for this service, currency and date"));
    TaxRateView rate =
        price.taxRateCode() == null
            ? null
            : store
                .effectiveRate(price.taxRateCode(), on)
                .orElseThrow(() ->
                    new ApiException.InvalidTransition(
                        "The price's tax rate has no approved rate in effect on this date"));
    return new EffectivePrice(serviceId, currency, on, price, rate);
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** Valid ISO 4217 currency and no more decimals than its minor unit allows. */
  private static BigDecimal checkAmount(String code, BigDecimal amount) {
    Currency currency;
    try {
      currency = Currency.getInstance(code);
    } catch (IllegalArgumentException e) {
      throw new ApiException.Invalid("currency", "is not an ISO 4217 currency code");
    }
    int digits = currency.getDefaultFractionDigits();
    if (digits < 0) {
      throw new ApiException.Invalid("currency", "is not a currency that can be charged");
    }
    BigDecimal normalized = amount.stripTrailingZeros();
    if (normalized.scale() > digits) {
      throw new ApiException.Invalid(
          "amount", code + " allows at most " + digits + " decimal places");
    }
    if (normalized.precision() - normalized.scale() > 15) {
      throw new ApiException.Invalid("amount", "is too large");
    }
    return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
  }

  private void notInThePast(String field, LocalDate date) {
    if (date.isBefore(today())) {
      throw new ApiException.Invalid(
          field, "must be today or later; prices never change retroactively");
    }
  }

  private static void checkDecidable(CurrentUser user, DecisionStatus status, UUID proposer) {
    if (status != DecisionStatus.PROPOSED) {
      throw new ApiException.InvalidTransition("This proposal has already been decided");
    }
    if (proposer.equals(user.userId())) {
      throw new ApiException.InvalidTransition(
          "You proposed this, so another person must decide it");
    }
  }

  private static ServiceView view(ServiceRow s, List<PriceView> current) {
    return new ServiceView(
        s.id(), s.code(), s.name(), s.orderCatalogItemId(), s.status(), current, s.version());
  }

  /** Audited after commit; metadata holds codes and ids, never amounts or reasons. */
  private void audit(
      CurrentUser user, String action, String type, UUID id, Map<String, String> metadata) {
    AuditEvent event =
        new AuditEvent(
            user.organizationId(),
            null,
            user.userId(),
            action,
            type,
            id.toString(),
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
