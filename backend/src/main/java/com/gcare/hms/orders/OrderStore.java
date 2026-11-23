package com.gcare.hms.orders;

import com.gcare.hms.encounter.EncounterApi.DepartmentRef;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import com.gcare.hms.orders.OrderApi.CatalogItem;
import com.gcare.hms.orders.OrderApi.Category;
import com.gcare.hms.orders.OrderApi.ItemSnapshot;
import com.gcare.hms.orders.OrderApi.ItemStatus;
import com.gcare.hms.orders.OrderApi.OrderEvent;
import com.gcare.hms.orders.OrderApi.OrderStatus;
import com.gcare.hms.orders.OrderApi.Priority;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import com.gcare.hms.patient.PatientStatus;
import com.gcare.hms.patient.Sex;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Orders persistence. Row-level security limits statements to the organization; nothing is deleted.
 * Encounter, patient, department and user data are read through read-only joins for checks and
 * display (docs/18 §6).
 */
@Repository
class OrderStore {

  /** What the order service needs to know about a visit. */
  record VisitRow(UUID id, UUID facilityId, UUID patientId, String status) {}

  record OrderRow(
      UUID id,
      UUID encounterId,
      UUID facilityId,
      PatientSummary patient,
      ItemSnapshot item,
      DepartmentRef performingDepartment,
      Priority priority,
      OrderStatus status,
      String indication,
      UserRef requestedBy,
      Instant requestedAt,
      Instant updatedAt,
      String cancelReason,
      long version) {}

  private static final String SELECT_ITEM =
      """
      SELECT c.*, d.code AS d_code, d.name AS d_name, d.facility_id AS d_facility
      FROM order_catalog_items c LEFT JOIN departments d ON d.id = c.performing_department_id
      """;

  private static final String SELECT_ORDER =
      """
      SELECT o.*, d.code AS d_code, d.name AS d_name,
             p.patient_number, p.status AS p_status, p.family_name, p.given_names, p.birth_date,
             p.birth_date_estimated, p.sex, p.details_incomplete,
             u.display_name AS requester_name
      FROM clinical_orders o
      JOIN patients p ON p.id = o.patient_id
      JOIN users u ON u.id = o.requested_by
      LEFT JOIN departments d ON d.id = o.performing_department_id
      """;

  private final JdbcClient jdbc;

  OrderStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // --- lookups ---------------------------------------------------------------------------------

  Optional<VisitRow> visit(UUID encounterId) {
    return jdbc.sql("SELECT id, facility_id, patient_id, status FROM encounters WHERE id = :id")
        .param("id", encounterId)
        .query((rs, i) ->
            new VisitRow(
                rs.getObject("id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("status")))
        .optional();
  }

  /** The department's facility if the department exists and is active. */
  Optional<UUID> activeDepartmentFacility(UUID departmentId) {
    return jdbc.sql("SELECT facility_id FROM departments WHERE id = :id AND status = 'ACTIVE'")
        .param("id", departmentId)
        .query(UUID.class)
        .optional();
  }

  // --- catalog ---------------------------------------------------------------------------------

  List<CatalogItem> items(boolean includeInactive) {
    return jdbc.sql(
            SELECT_ITEM
                + (includeInactive ? "" : " WHERE c.status = 'ACTIVE'")
                + " ORDER BY c.category, c.name, c.code")
        .query(OrderStore::itemRow)
        .list();
  }

  Optional<CatalogItem> item(UUID id) {
    return jdbc.sql(SELECT_ITEM + " WHERE c.id = :id")
        .param("id", id)
        .query(OrderStore::itemRow)
        .optional();
  }

  void insertItem(
      UUID id,
      UUID organizationId,
      String code,
      String name,
      Category category,
      UUID departmentId,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO order_catalog_items (id, organization_id, code, name, category,
                performing_department_id, status, created_by)
            VALUES (:id, :org, :code, :name, :category, :department, 'ACTIVE', :by)
            """)
        .param("id", id)
        .param("org", organizationId)
        .param("code", code)
        .param("name", name)
        .param("category", category.name())
        .param("department", departmentId)
        .param("by", by)
        .update();
  }

  int updateItem(UUID id, long expectedVersion, String name, UUID departmentId, ItemStatus status) {
    return jdbc.sql(
            """
            UPDATE order_catalog_items SET name = :name, performing_department_id = :department,
                status = :status, updated_at = now(), version = version + 1
            WHERE id = :id AND version = :version
            """)
        .param("name", name)
        .param("department", departmentId)
        .param("status", status.name())
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }

  // --- orders ----------------------------------------------------------------------------------

  void insertOrder(
      UUID id,
      UUID organizationId,
      VisitRow visit,
      CatalogItem item,
      Priority priority,
      String indication,
      UUID by) {
    jdbc.sql(
            """
            INSERT INTO clinical_orders (id, organization_id, encounter_id, patient_id, facility_id,
                catalog_item_id, item_code, item_name, item_category, performing_department_id,
                priority, status, indication, requested_by)
            VALUES (:id, :org, :encounter, :patient, :facility, :item, :code, :name, :category,
                :department, :priority, 'REQUESTED', :indication, :by)
            """)
        .param("id", id)
        .param("org", organizationId)
        .param("encounter", visit.id())
        .param("patient", visit.patientId())
        .param("facility", visit.facilityId())
        .param("item", item.id())
        .param("code", item.code())
        .param("name", item.name())
        .param("category", item.category().name())
        .param(
            "department",
            item.performingDepartment() == null ? null : item.performingDepartment().id())
        .param("priority", priority.name())
        .param("indication", indication)
        .param("by", by)
        .update();
  }

  Optional<OrderRow> order(UUID id) {
    return jdbc.sql(SELECT_ORDER + " WHERE o.id = :id")
        .param("id", id)
        .query(OrderStore::orderRow)
        .optional();
  }

  List<OrderRow> ordersOfVisit(UUID encounterId) {
    return jdbc.sql(SELECT_ORDER + " WHERE o.encounter_id = :id ORDER BY o.requested_at, o.id")
        .param("id", encounterId)
        .query(OrderStore::orderRow)
        .list();
  }

  /** Worklist: urgent first, then oldest request first. */
  List<OrderRow> worklist(
      Collection<UUID> facilities,
      boolean all,
      UUID departmentId,
      Collection<OrderStatus> statuses,
      int page,
      int size) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(facilities, all, departmentId, statuses, params);
    var q =
        jdbc.sql(
            SELECT_ORDER
                + where
                + " ORDER BY (o.priority = 'URGENT') DESC, o.requested_at, o.id LIMIT :limit OFFSET :offset");
    for (var e : params.entrySet()) {
      q = q.param(e.getKey(), e.getValue());
    }
    return q.param("limit", size)
        .param("offset", (long) page * size)
        .query(OrderStore::orderRow)
        .list();
  }

  long countWorklist(
      Collection<UUID> facilities,
      boolean all,
      UUID departmentId,
      Collection<OrderStatus> statuses) {
    Map<String, Object> params = new LinkedHashMap<>();
    var q =
        jdbc.sql(
            "SELECT count(*) FROM clinical_orders o"
                + where(facilities, all, departmentId, statuses, params));
    for (var e : params.entrySet()) {
      q = q.param(e.getKey(), e.getValue());
    }
    return q.query(Long.class).single();
  }

  private static String where(
      Collection<UUID> facilities,
      boolean all,
      UUID departmentId,
      Collection<OrderStatus> statuses,
      Map<String, Object> params) {
    List<String> where = new ArrayList<>();
    if (!all) {
      where.add("o.facility_id IN (:facilities)");
      params.put("facilities", facilities);
    }
    if (departmentId != null) {
      where.add("o.performing_department_id = :department");
      params.put("department", departmentId);
    }
    where.add("o.status IN (:statuses)");
    params.put("statuses", statuses.stream().map(Enum::name).toList());
    return " WHERE " + String.join(" AND ", where);
  }

  int transition(UUID id, long expectedVersion, OrderStatus to, String cancelReason) {
    return jdbc.sql(
            """
            UPDATE clinical_orders SET status = :status, cancel_reason = :reason, updated_at = now(),
                version = version + 1
            WHERE id = :id AND version = :version
            """)
        .param("status", to.name())
        .param("reason", cancelReason)
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }

  void insertEvent(
      UUID organizationId,
      UUID orderId,
      String type,
      OrderStatus from,
      OrderStatus to,
      String reason,
      UUID actor) {
    jdbc.sql(
            """
            INSERT INTO order_events (id, organization_id, order_id, event_type, from_status,
                to_status, reason, actor_id)
            VALUES (:id, :org, :order, :type, :from, :to, :reason, :actor)
            """)
        .param("id", UUID.randomUUID())
        .param("org", organizationId)
        .param("order", orderId)
        .param("type", type)
        .param("from", from == null ? null : from.name())
        .param("to", to.name())
        .param("reason", reason)
        .param("actor", actor)
        .update();
  }

  Map<UUID, List<OrderEvent>> events(Collection<UUID> orderIds) {
    Map<UUID, List<OrderEvent>> result = new LinkedHashMap<>();
    if (orderIds.isEmpty()) {
      return result;
    }
    jdbc.sql(
            """
            SELECT ev.*, u.display_name AS actor_name FROM order_events ev
            JOIN users u ON u.id = ev.actor_id
            WHERE ev.order_id IN (:ids) ORDER BY ev.occurred_at, ev.id
            """)
        .param("ids", orderIds)
        .query((rs, i) -> {
          String from = rs.getString("from_status");
          result
              .computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new OrderEvent(
                      rs.getString("event_type"),
                      from == null ? null : OrderStatus.valueOf(from),
                      OrderStatus.valueOf(rs.getString("to_status")),
                      rs.getString("reason"),
                      new UserRef(rs.getObject("actor_id", UUID.class), rs.getString("actor_name")),
                      instant(rs, "occurred_at")));
          return null;
        })
        .list();
    return result;
  }

  // --- mapping ---------------------------------------------------------------------------------

  private static CatalogItem itemRow(ResultSet rs, int i) throws SQLException {
    UUID dept = rs.getObject("performing_department_id", UUID.class);
    return new CatalogItem(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        Category.valueOf(rs.getString("category")),
        dept == null
            ? null
            : new DepartmentRef(dept, rs.getString("d_code"), rs.getString("d_name")),
        dept == null ? null : rs.getObject("d_facility", UUID.class),
        ItemStatus.valueOf(rs.getString("status")),
        rs.getLong("version"));
  }

  private static OrderRow orderRow(ResultSet rs, int i) throws SQLException {
    UUID dept = rs.getObject("performing_department_id", UUID.class);
    PatientSummary patient =
        new PatientSummary(
            rs.getObject("patient_id", UUID.class),
            rs.getString("patient_number"),
            PatientStatus.valueOf(rs.getString("p_status")),
            rs.getString("family_name"),
            rs.getString("given_names"),
            rs.getObject("birth_date", LocalDate.class),
            rs.getBoolean("birth_date_estimated"),
            Sex.valueOf(rs.getString("sex")),
            rs.getBoolean("details_incomplete"));
    return new OrderRow(
        rs.getObject("id", UUID.class),
        rs.getObject("encounter_id", UUID.class),
        rs.getObject("facility_id", UUID.class),
        patient,
        new ItemSnapshot(
            rs.getObject("catalog_item_id", UUID.class),
            rs.getString("item_code"),
            rs.getString("item_name"),
            Category.valueOf(rs.getString("item_category"))),
        dept == null
            ? null
            : new DepartmentRef(dept, rs.getString("d_code"), rs.getString("d_name")),
        Priority.valueOf(rs.getString("priority")),
        OrderStatus.valueOf(rs.getString("status")),
        rs.getString("indication"),
        new UserRef(rs.getObject("requested_by", UUID.class), rs.getString("requester_name")),
        instant(rs, "requested_at"),
        instant(rs, "updated_at"),
        rs.getString("cancel_reason"),
        rs.getLong("version"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
