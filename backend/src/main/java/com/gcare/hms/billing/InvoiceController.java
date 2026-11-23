package com.gcare.hms.billing;

import com.gcare.hms.billing.InvoiceApi.AddLineRequest;
import com.gcare.hms.billing.InvoiceApi.BillableVisit;
import com.gcare.hms.billing.InvoiceApi.CreateInvoiceRequest;
import com.gcare.hms.billing.InvoiceApi.InvoiceStatus;
import com.gcare.hms.billing.InvoiceApi.InvoiceSummary;
import com.gcare.hms.billing.InvoiceApi.InvoiceView;
import com.gcare.hms.billing.InvoiceApi.ReasonRequest;
import com.gcare.hms.billing.InvoiceApi.VersionRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Visit invoices (task 4.2; docs/19 §3). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Invoices")
class InvoiceController {

  private final InvoiceService service;

  InvoiceController(InvoiceService service) {
    this.service = service;
  }

  @GetMapping("/invoices")
  @Operation(
      summary = "Invoices",
      description = "Needs INVOICE_READ. Default drafts and issued invoices, newest first (200).")
  List<InvoiceSummary> invoices(
      @RequestParam(name = "facility_id", required = false) UUID facilityId,
      @RequestParam(name = "status", required = false) List<InvoiceStatus> statuses) {
    return service.invoices(facilityId, statuses);
  }

  @GetMapping("/invoices/billable-visits")
  @Operation(
      summary = "Visits without an invoice",
      description = "Needs INVOICE_MANAGE. Not cancelled, arrived in the last `days` (7, max 90).")
  List<BillableVisit> billableVisits(
      @RequestParam(name = "facility_id", required = false) UUID facilityId,
      @RequestParam(required = false) Integer days) {
    return service.billableVisits(facilityId, days);
  }

  @GetMapping("/encounters/{id}/invoices")
  @Operation(summary = "Invoices of a visit", description = "Needs INVOICE_READ.")
  List<InvoiceSummary> invoicesOfVisit(@PathVariable UUID id) {
    return service.invoicesOfVisit(id);
  }

  @PostMapping("/encounters/{id}/invoices")
  @Operation(
      summary = "Start an invoice",
      description =
          "Needs INVOICE_MANAGE. Draft in the chosen currency; the visit's order charges are added.")
  ResponseEntity<InvoiceView> create(
      @PathVariable UUID id, @Valid @RequestBody CreateInvoiceRequest request) {
    InvoiceView created = service.create(id, request);
    return ResponseEntity.created(URI.create("/api/v1/invoices/" + created.id())).body(created);
  }

  @GetMapping("/invoices/{id}")
  @Operation(summary = "Invoice with its lines", description = "Needs INVOICE_READ.")
  InvoiceView get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping("/invoices/{id}/order-charges")
  @Operation(summary = "Add new order charges", description = "Needs INVOICE_MANAGE. Draft only.")
  InvoiceView addOrderCharges(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
    return service.addOrderCharges(id, request);
  }

  @PostMapping("/invoices/{id}/lines")
  @Operation(
      summary = "Add a charge",
      description = "Needs INVOICE_MANAGE. Draft only; uses today's approved price.")
  InvoiceView addLine(@PathVariable UUID id, @Valid @RequestBody AddLineRequest request) {
    return service.addLine(id, request);
  }

  @PostMapping("/invoices/{id}/lines/{lineId}/remove")
  @Operation(summary = "Remove a charge", description = "Needs INVOICE_MANAGE. Draft only.")
  InvoiceView removeLine(
      @PathVariable UUID id, @PathVariable UUID lineId, @Valid @RequestBody ReasonRequest request) {
    return service.removeLine(id, lineId, request);
  }

  @PostMapping("/invoices/{id}/issue")
  @Operation(
      summary = "Issue an invoice",
      description = "Needs INVOICE_MANAGE. Fixes totals and assigns the number.")
  InvoiceView issue(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
    return service.issue(id, request);
  }

  @PostMapping("/invoices/{id}/void")
  @Operation(
      summary = "Void an invoice",
      description = "Discard a draft (INVOICE_MANAGE) or void an issued invoice (INVOICE_VOID).")
  InvoiceView voidInvoice(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
    return service.voidInvoice(id, request);
  }
}
