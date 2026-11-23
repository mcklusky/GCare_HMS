package com.gcare.hms.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.configuration.SettingDefinition.Scope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Versioned configuration with two-person approval, and facility branding (task 5.2; docs/21). */
public final class ConfigApi {

  private ConfigApi() {}

  public enum VersionStatus {
    PROPOSED,
    ACTIVE,
    SUPERSEDED,
    REJECTED
  }

  public record UserRef(UUID id, @JsonProperty("display_name") String displayName) {}

  public record SettingInfo(
      String key, String title, String description, Scope scope, String permission) {}

  public record ProposeRequest(
      @JsonProperty("facility_id") UUID facilityId,
      @NotNull JsonNode value,
      @NotBlank @Size(max = 500) String reason) {}

  public record DecisionRequest(long version, @NotBlank @Size(max = 500) String note) {}

  public record VersionView(
      UUID id,
      String key,
      @JsonProperty("facility_id") UUID facilityId,
      @JsonProperty("version_number") int versionNumber,
      JsonNode value,
      VersionStatus status,
      String reason,
      @JsonProperty("proposed_by") UserRef proposedBy,
      @JsonProperty("proposed_at") Instant proposedAt,
      @JsonProperty("decided_by") UserRef decidedBy,
      @JsonProperty("decided_at") Instant decidedAt,
      @JsonProperty("decision_note") String decisionNote,
      long version) {}

  public record LogoUpload(
      @NotBlank
          @Pattern(regexp = "^image/(png|jpeg)$", message = "must be image/png or image/jpeg")
          @JsonProperty("content_type")
          String contentType,
      @NotBlank @Size(max = 360_000) @JsonProperty("data_base64") String dataBase64) {}

  public record LogoView(UUID id, @JsonProperty("facility_id") UUID facilityId) {}

  /** Approved branding of a facility (defaults to the facility name when none is approved). */
  public record BrandingView(
      @JsonProperty("facility_id") UUID facilityId,
      @JsonProperty("display_name") String displayName,
      @JsonProperty("address_lines") List<String> addressLines,
      String phone,
      @JsonProperty("registration_number") String registrationNumber,
      @JsonProperty("receipt_footer") String receiptFooter,
      @JsonProperty("logo_id") UUID logoId,
      boolean configured) {}
}
