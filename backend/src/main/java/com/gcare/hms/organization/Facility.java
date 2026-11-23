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
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** A hospital or site within an organization (FR-001). */
@Entity
@Table(name = "facilities")
public class Facility {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(nullable = false, length = 50, updatable = false)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(columnDefinition = "text")
  private String address;

  @Column(nullable = false, length = 64)
  private String timezone;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UnitStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected Facility() {}

  public Facility(UUID organizationId, String code, String name, ZoneId timezone) {
    this.id = UUID.randomUUID();
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
    this.code = Objects.requireNonNull(code, "code");
    this.name = Objects.requireNonNull(name, "name");
    this.timezone = Objects.requireNonNull(timezone, "timezone").getId();
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

  public void rename(String newName) {
    this.name = Objects.requireNonNull(newName, "newName");
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

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getAddress() {
    return address;
  }

  public ZoneId getTimezone() {
    return ZoneId.of(timezone);
  }

  public UnitStatus getStatus() {
    return status;
  }

  public long getVersion() {
    return version;
  }
}
