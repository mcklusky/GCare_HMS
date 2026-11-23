package com.gcare.hms.orders;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.encounter.EncounterApi.DepartmentRef;
import com.gcare.hms.encounter.EncounterApi.UserRef;
import com.gcare.hms.patient.PatientApi.PatientSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Order catalog, orders and referrals (task 3.3; FR-009). snake_case on the wire. */
public final class OrderApi {

  private OrderApi() {}

  public enum Category {
    LAB,
    IMAGING,
    PROCEDURE,
    REFERRAL
  }

  public enum Priority {
    ROUTINE,
    URGENT
  }

  public enum OrderStatus {
    REQUESTED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    boolean active() {
      return this == REQUESTED || this == IN_PROGRESS;
    }
  }

  public enum ItemStatus {
    ACTIVE,
    INACTIVE
  }

  // --- catalog ---------------------------------------------------------------------------------

  public record CreateItemRequest(
      @NotBlank
          @Size(max = 50)
          @Pattern(
              regexp = "^[A-Z0-9][A-Z0-9_-]*$",
              message = "use capital letters, digits, - or _")
          String code,
      @NotBlank @Size(max = 200) String name,
      @NotNull Category category,
      @JsonProperty("performing_department_id") UUID performingDepartmentId) {}

  /** Code and category never change; everything else is replaced. */
  public record UpdateItemRequest(
      long version,
      @NotBlank @Size(max = 200) String name,
      @JsonProperty("performing_department_id") UUID performingDepartmentId,
      @NotNull ItemStatus status) {}

  public record CatalogItem(
      UUID id,
      String code,
      String name,
      Category category,
      @JsonProperty("performing_department") DepartmentRef performingDepartment,
      @JsonProperty("facility_id") UUID facilityId,
      ItemStatus status,
      long version) {}

  // --- orders ----------------------------------------------------------------------------------

  public record CreateOrderRequest(
      @NotNull @JsonProperty("catalog_item_id") UUID catalogItemId,
      @NotNull Priority priority,
      @Size(max = 1000) String indication) {}

  public record VersionRequest(long version) {}

  public record CancelRequest(long version, @NotBlank @Size(max = 500) String reason) {}

  public record ItemSnapshot(UUID id, String code, String name, Category category) {}

  public record OrderEvent(
      @JsonProperty("event_type") String eventType,
      @JsonProperty("from_status") OrderStatus fromStatus,
      @JsonProperty("to_status") OrderStatus toStatus,
      String reason,
      UserRef actor,
      @JsonProperty("occurred_at") Instant occurredAt) {}

  public record OrderView(
      UUID id,
      @JsonProperty("encounter_id") UUID encounterId,
      @JsonProperty("facility_id") UUID facilityId,
      PatientSummary patient,
      ItemSnapshot item,
      @JsonProperty("performing_department") DepartmentRef performingDepartment,
      Priority priority,
      OrderStatus status,
      String indication,
      @JsonProperty("requested_by") UserRef requestedBy,
      @JsonProperty("requested_at") Instant requestedAt,
      @JsonProperty("updated_at") Instant updatedAt,
      @JsonProperty("cancel_reason") String cancelReason,
      List<OrderEvent> events,
      long version) {}

  public record Page<T>(List<T> items, int page, int size, long total) {}
}
