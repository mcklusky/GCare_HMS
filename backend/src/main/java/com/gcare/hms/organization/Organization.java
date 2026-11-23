package com.gcare.hms.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A hospital customer (detail 04, section 3.1). Created by platform operations only. */
@Entity
@Table(name = "organizations")
public class Organization {

  @Id private UUID id;

  @Column(nullable = false, length = 50, updatable = false)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(name = "country_code", nullable = false, length = 2)
  private String countryCode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private OrganizationStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "hosting_model", nullable = false, length = 20)
  private HostingModel hostingModel;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false)
  private Instant updatedAt;

  @Version private long version;

  protected Organization() {}

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public OrganizationStatus getStatus() {
    return status;
  }

  public HostingModel getHostingModel() {
    return hostingModel;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
