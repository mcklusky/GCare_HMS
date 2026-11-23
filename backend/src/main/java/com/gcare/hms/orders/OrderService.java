package com.gcare.hms.orders;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.orders.OrderApi.CancelRequest;
import com.gcare.hms.orders.OrderApi.CatalogItem;
import com.gcare.hms.orders.OrderApi.CreateItemRequest;
import com.gcare.hms.orders.OrderApi.CreateOrderRequest;
import com.gcare.hms.orders.OrderApi.ItemStatus;
import com.gcare.hms.orders.OrderApi.OrderEvent;
import com.gcare.hms.orders.OrderApi.OrderStatus;
import com.gcare.hms.orders.OrderApi.OrderView;
import com.gcare.hms.orders.OrderApi.Page;
import com.gcare.hms.orders.OrderApi.UpdateItemRequest;
import com.gcare.hms.orders.OrderApi.VersionRequest;
import com.gcare.hms.orders.OrderStore.OrderRow;
import com.gcare.hms.orders.OrderStore.VisitRow;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.FacilityScope;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
import org.springframework.util.StringUtils;

/**
 * Order catalog and orders/referrals (task 3.3; FR-009; docs/18 §8, PROVISIONAL). The catalog is
 * managed by administrators and starts empty; orders keep a snapshot of the item. Status: REQUESTED
 * → IN_PROGRESS → COMPLETED, or CANCELLED with a reason. Every change is in the order's history and
 * audited; facility scope is checked on every call (404 outside scope).
 */
@Service
public class OrderService {

  public static final int MAX_PAGE_SIZE = 100;
  private static final Set<OrderStatus> ACTIVE =
      EnumSet.of(OrderStatus.REQUESTED, OrderStatus.IN_PROGRESS);

  private final OrderStore store;
  private final AuditService audit;

  OrderService(OrderStore store, AuditService audit) {
    this.store = store;
    this.audit = audit;
  }

  // --- catalog ---------------------------------------------------------------------------------

  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.ORDER_CREATE
          + "', '"
          + Permissions.ORDER_READ
          + "', '"
          + Permissions.ORDER_CATALOG_MANAGE
          + "')")
  @Transactional(readOnly = true)
  public List<CatalogItem> catalog(boolean includeInactive) {
    CurrentUser user = CurrentUser.require();
    return store.items(includeInactive && user.hasPermission(Permissions.ORDER_CATALOG_MANAGE));
  }

  @PreAuthorize("hasAuthority('" + Permissions.ORDER_CATALOG_MANAGE + "')")
  @Transactional
  public CatalogItem createItem(CreateItemRequest request) {
    CurrentUser user = CurrentUser.require();
    checkDepartment(user, request.performingDepartmentId());
    UUID id = UUID.randomUUID();
    try {
      store.insertItem(
          id,
          user.organizationId(),
          request.code(),
          request.name().trim(),
          request.category(),
          request.performingDepartmentId(),
          user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict("An item with this code already exists");
    }
    audit(
        user,
        null,
        "ORDER_CATALOG_ITEM_CREATED",
        "OrderCatalogItem",
        id,
        Map.of("code", request.code()));
    return store.item(id).orElseThrow();
  }

  @PreAuthorize("hasAuthority('" + Permissions.ORDER_CATALOG_MANAGE + "')")
  @Transactional
  public CatalogItem updateItem(UUID id, UpdateItemRequest request) {
    CurrentUser user = CurrentUser.require();
    CatalogItem current =
        store.item(id).orElseThrow(() -> new ApiException.NotFound("Catalog item"));
    checkDepartment(user, request.performingDepartmentId());
    if (store.updateItem(
            id,
            request.version(),
            request.name().trim(),
            request.performingDepartmentId(),
            request.status())
        == 0) {
      throw new ApiException.Conflict("Someone else changed this item. Reload and try again.");
    }
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("code", current.code());
    if (current.status() != request.status()) {
      metadata.put("status", request.status().name());
    }
    audit(user, null, "ORDER_CATALOG_ITEM_UPDATED", "OrderCatalogItem", id, metadata);
    return store.item(id).orElseThrow();
  }

  private void checkDepartment(CurrentUser user, UUID departmentId) {
    if (departmentId == null) {
      return;
    }
    UUID facility =
        store
            .activeDepartmentFacility(departmentId)
            .orElseThrow(() ->
                new ApiException.Invalid(
                    "performing_department_id", "is not an active department"));
    if (!user.scopeOf(Permissions.ORDER_CATALOG_MANAGE).includes(facility)) {
      throw new ApiException.Invalid("performing_department_id", "is not an active department");
    }
  }

  // --- orders ----------------------------------------------------------------------------------

  @PreAuthorize("hasAuthority('" + Permissions.ORDER_CREATE + "')")
  @Transactional
  public OrderView create(UUID encounterId, CreateOrderRequest request) {
    CurrentUser user = CurrentUser.require();
    VisitRow visit = visit(user, encounterId, Permissions.ORDER_CREATE);
    if (!"WAITING".equals(visit.status()) && !"IN_CONSULTATION".equals(visit.status())) {
      throw new ApiException.InvalidTransition("Orders can only be placed on an open visit");
    }
    CatalogItem item =
        store
            .item(request.catalogItemId())
            .filter(i -> i.status() == ItemStatus.ACTIVE)
            .orElseThrow(
                () -> new ApiException.Invalid("catalog_item_id", "is not an active catalog item"));
    if (item.facilityId() != null && !item.facilityId().equals(visit.facilityId())) {
      throw new ApiException.Invalid("catalog_item_id", "is not available at this facility");
    }
    UUID id = UUID.randomUUID();
    String indication =
        StringUtils.hasText(request.indication()) ? request.indication().trim() : null;
    store.insertOrder(
        id, user.organizationId(), visit, item, request.priority(), indication, user.userId());
    store.insertEvent(
        user.organizationId(), id, "REQUESTED", null, OrderStatus.REQUESTED, null, user.userId());
    audit(
        user,
        visit.facilityId(),
        "ORDER_REQUESTED",
        "Order",
        id,
        Map.of(
            "encounter",
            encounterId.toString(),
            "catalog_item",
            item.id().toString(),
            "priority",
            request.priority().name()));
    return view(store.order(id).orElseThrow());
  }

  @PreAuthorize("hasAuthority('" + Permissions.ORDER_READ + "')")
  @Transactional(readOnly = true)
  public List<OrderView> ordersOfVisit(UUID encounterId) {
    CurrentUser user = CurrentUser.require();
    VisitRow visit = visit(user, encounterId, Permissions.ORDER_READ);
    List<OrderView> result = views(store.ordersOfVisit(encounterId));
    audit(user, visit.facilityId(), "ORDERS_VIEWED", "Encounter", encounterId, Map.of());
    return result;
  }

  /** A patient's orders across visits at facilities in scope, newest first (patient chart). */
  @PreAuthorize("hasAuthority('" + Permissions.ORDER_READ + "')")
  @Transactional(readOnly = true)
  public List<OrderView> ordersOfPatient(UUID patientId) {
    CurrentUser user = CurrentUser.require();
    FacilityScope scope = user.scopeOf(Permissions.ORDER_READ);
    List<OrderView> result =
        views(
            store.ordersOfPatient(patientId, MAX_PAGE_SIZE).stream()
                .filter(o -> scope.includes(o.facilityId()))
                .toList());
    audit(user, null, "PATIENT_ORDERS_VIEWED", "Patient", patientId, Map.of());
    return result;
  }

  /** Department worklist; default active orders (REQUESTED, IN_PROGRESS). Urgent first. */
  @PreAuthorize("hasAuthority('" + Permissions.ORDER_READ + "')")
  @Transactional(readOnly = true)
  public Page<OrderView> worklist(
      UUID facilityId, UUID departmentId, List<OrderStatus> statuses, int page, int size) {
    CurrentUser user = CurrentUser.require();
    if (page < 0) {
      throw new ApiException.Invalid("page", "must be 0 or more");
    }
    int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    FacilityScope scope = user.scopeOf(Permissions.ORDER_READ);
    Set<UUID> facilities = scope.facilityIds();
    boolean all = scope.allFacilities();
    if (facilityId != null) {
      if (!scope.includes(facilityId)) {
        throw new ApiException.NotFound("Facility");
      }
      facilities = Set.of(facilityId);
      all = false;
    }
    Set<OrderStatus> wanted =
        statuses == null || statuses.isEmpty() ? ACTIVE : EnumSet.copyOf(statuses);
    List<OrderView> items =
        views(store.worklist(facilities, all, departmentId, wanted, page, pageSize));
    long total = store.countWorklist(facilities, all, departmentId, wanted);
    audit(
        user,
        facilityId,
        "ORDER_WORKLIST_VIEWED",
        "Order",
        null,
        Map.of("results", Long.toString(total)));
    return new Page<>(items, page, pageSize, total);
  }

  @PreAuthorize("hasAuthority('" + Permissions.ORDER_PROCESS + "')")
  @Transactional
  public OrderView start(UUID id, VersionRequest request) {
    CurrentUser user = CurrentUser.require();
    OrderRow o = order(user, id, Permissions.ORDER_PROCESS);
    if (o.status() != OrderStatus.REQUESTED) {
      throw new ApiException.InvalidTransition("Only a requested order can be started");
    }
    return move(user, o, request.version(), "STARTED", OrderStatus.IN_PROGRESS, null);
  }

  @PreAuthorize("hasAuthority('" + Permissions.ORDER_PROCESS + "')")
  @Transactional
  public OrderView complete(UUID id, VersionRequest request) {
    CurrentUser user = CurrentUser.require();
    OrderRow o = order(user, id, Permissions.ORDER_PROCESS);
    if (o.status() != OrderStatus.IN_PROGRESS) {
      throw new ApiException.InvalidTransition("Only an order in progress can be completed");
    }
    return move(user, o, request.version(), "COMPLETED", OrderStatus.COMPLETED, null);
  }

  /** By the ordering side (ORDER_CREATE) or the performing side (ORDER_PROCESS), with a reason. */
  @PreAuthorize(
      "hasAnyAuthority('" + Permissions.ORDER_CREATE + "', '" + Permissions.ORDER_PROCESS + "')")
  @Transactional
  public OrderView cancel(UUID id, CancelRequest request) {
    CurrentUser user = CurrentUser.require();
    OrderRow o = store.order(id).orElseThrow(() -> new ApiException.NotFound("Order"));
    if (!user.scopeOf(Permissions.ORDER_CREATE).includes(o.facilityId())
        && !user.scopeOf(Permissions.ORDER_PROCESS).includes(o.facilityId())) {
      throw new ApiException.NotFound("Order");
    }
    if (!o.status().active()) {
      throw new ApiException.InvalidTransition(
          "Only a requested or in-progress order can be cancelled");
    }
    return move(
        user, o, request.version(), "CANCELLED", OrderStatus.CANCELLED, request.reason().trim());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private VisitRow visit(CurrentUser user, UUID encounterId, String permission) {
    VisitRow visit =
        store.visit(encounterId).orElseThrow(() -> new ApiException.NotFound("Encounter"));
    if (!user.scopeOf(permission).includes(visit.facilityId())) {
      throw new ApiException.NotFound("Encounter");
    }
    return visit;
  }

  private OrderRow order(CurrentUser user, UUID id, String permission) {
    OrderRow o = store.order(id).orElseThrow(() -> new ApiException.NotFound("Order"));
    if (!user.scopeOf(permission).includes(o.facilityId())) {
      throw new ApiException.NotFound("Order");
    }
    return o;
  }

  private OrderView move(
      CurrentUser user, OrderRow o, long version, String event, OrderStatus to, String reason) {
    if (store.transition(o.id(), version, to, reason) == 0) {
      throw new ApiException.Conflict("Someone else changed this order. Reload and try again.");
    }
    store.insertEvent(user.organizationId(), o.id(), event, o.status(), to, reason, user.userId());
    audit(
        user,
        o.facilityId(),
        "ORDER_" + event,
        "Order",
        o.id(),
        Map.of("from", o.status().name(), "to", to.name()));
    return view(store.order(o.id()).orElseThrow());
  }

  private OrderView view(OrderRow o) {
    return views(List.of(o)).getFirst();
  }

  private List<OrderView> views(List<OrderRow> rows) {
    Map<UUID, List<OrderEvent>> events = store.events(rows.stream().map(OrderRow::id).toList());
    return rows.stream()
        .map(o ->
            new OrderView(
                o.id(),
                o.encounterId(),
                o.facilityId(),
                o.patient(),
                o.item(),
                o.performingDepartment(),
                o.priority(),
                o.status(),
                o.indication(),
                o.requestedBy(),
                o.requestedAt(),
                o.updatedAt(),
                o.cancelReason(),
                events.getOrDefault(o.id(), List.of()),
                o.version()))
        .toList();
  }

  /** Audited after commit; metadata holds codes and ids only, never indications or reasons. */
  private void audit(
      CurrentUser user,
      UUID facilityId,
      String action,
      String type,
      UUID resourceId,
      Map<String, String> metadata) {
    AuditEvent event =
        new AuditEvent(
            user.organizationId(),
            facilityId,
            user.userId(),
            action,
            type,
            resourceId == null ? null : resourceId.toString(),
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
