package com.gcare.hms.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A department within a facility (FR-001). Must belong to the facility's organization. */
@Entity
@Table(name = "departments")
public class Department {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "facility_id", nullable = false, updatable = false)
  private UUID facilityId;

  @Column(nullable = false, length = 50, updatable = false)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UnitStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected Department() {}

  public Department(UUID organizationId, UUID facilityId, String code, String name) {
    this.id = UUID.randomUUID();
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
    this.facilityId = Objects.requireNonNull(facilityId, "facilityId");
    this.code = Objects.requireNonNull(code, "code");
    this.name = Objects.requireNonNull(name, "name");
    this.status = UnitStatus.ACTIVE;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public void deactivate() {
    this.status = UnitStatus.INACTIVE;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getFacilityId() {
    return facilityId;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public UnitStatus getStatus() {
    return status;
  }

  public long getVersion() {
    return version;
  }
}
