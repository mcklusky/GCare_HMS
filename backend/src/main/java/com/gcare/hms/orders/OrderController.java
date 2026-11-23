package com.gcare.hms.orders;

import com.gcare.hms.orders.OrderApi.CancelRequest;
import com.gcare.hms.orders.OrderApi.CatalogItem;
import com.gcare.hms.orders.OrderApi.CreateItemRequest;
import com.gcare.hms.orders.OrderApi.CreateOrderRequest;
import com.gcare.hms.orders.OrderApi.OrderStatus;
import com.gcare.hms.orders.OrderApi.OrderView;
import com.gcare.hms.orders.OrderApi.Page;
import com.gcare.hms.orders.OrderApi.UpdateItemRequest;
import com.gcare.hms.orders.OrderApi.VersionRequest;
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

/** Order catalog, orders and referrals (docs/18 §8). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Orders")
class OrderController {

  private final OrderService service;

  OrderController(OrderService service) {
    this.service = service;
  }

  @GetMapping("/order-catalog")
  @Operation(
      summary = "Orderable items",
      description = "Active items; include_inactive=true for ORDER_CATALOG_MANAGE.")
  List<CatalogItem> catalog(
      @RequestParam(name = "include_inactive", defaultValue = "false") boolean includeInactive) {
    return service.catalog(includeInactive);
  }

  @PostMapping("/order-catalog")
  @Operation(
      summary = "Add an orderable item",
      description = "Needs ORDER_CATALOG_MANAGE. Code and category are fixed.")
  ResponseEntity<CatalogItem> createItem(@Valid @RequestBody CreateItemRequest request) {
    CatalogItem created = service.createItem(request);
    return ResponseEntity.created(URI.create("/api/v1/order-catalog/" + created.id()))
        .body(created);
  }

  @PutMapping("/order-catalog/{id}")
  @Operation(
      summary = "Change an item",
      description = "Name, performing department, status. Never deleted.")
  CatalogItem updateItem(@PathVariable UUID id, @Valid @RequestBody UpdateItemRequest request) {
    return service.updateItem(id, request);
  }

  @PostMapping("/encounters/{id}/orders")
  @Operation(
      summary = "Place an order or referral",
      description = "Needs ORDER_CREATE. Open visits only.")
  ResponseEntity<OrderView> create(
      @PathVariable UUID id, @Valid @RequestBody CreateOrderRequest request) {
    OrderView created = service.create(id, request);
    return ResponseEntity.created(URI.create("/api/v1/orders/" + created.id())).body(created);
  }

  @GetMapping("/encounters/{id}/orders")
  @Operation(summary = "Orders of a visit", description = "Needs ORDER_READ.")
  List<OrderView> ordersOfVisit(@PathVariable UUID id) {
    return service.ordersOfVisit(id);
  }

  @GetMapping("/patients/{id}/orders")
  @Operation(
      summary = "Orders of a patient",
      description = "Needs ORDER_READ. Across visits in scope, newest first.")
  List<OrderView> ordersOfPatient(@PathVariable UUID id) {
    return service.ordersOfPatient(id);
  }

  @GetMapping("/orders")
  @Operation(
      summary = "Department worklist",
      description = "Needs ORDER_READ. Default: REQUESTED and IN_PROGRESS; urgent first.")
  Page<OrderView> worklist(
      @RequestParam(name = "facility_id", required = false) UUID facilityId,
      @RequestParam(name = "department_id", required = false) UUID departmentId,
      @RequestParam(required = false) List<OrderStatus> status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return service.worklist(facilityId, departmentId, status, page, size);
  }

  @PostMapping("/orders/{id}/start")
  @Operation(summary = "Start", description = "Needs ORDER_PROCESS. REQUESTED -> IN_PROGRESS.")
  OrderView start(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
    return service.start(id, request);
  }

  @PostMapping("/orders/{id}/complete")
  @Operation(summary = "Complete", description = "Needs ORDER_PROCESS. IN_PROGRESS -> COMPLETED.")
  OrderView complete(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
    return service.complete(id, request);
  }

  @PostMapping("/orders/{id}/cancel")
  @Operation(summary = "Cancel", description = "Needs ORDER_CREATE or ORDER_PROCESS and a reason.")
  OrderView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
    return service.cancel(id, request);
  }
}
