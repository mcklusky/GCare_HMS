package com.gcare.hms.configuration;

import com.gcare.hms.configuration.ConfigApi.BrandingView;
import com.gcare.hms.configuration.ConfigApi.DecisionRequest;
import com.gcare.hms.configuration.ConfigApi.LogoUpload;
import com.gcare.hms.configuration.ConfigApi.LogoView;
import com.gcare.hms.configuration.ConfigApi.ProposeRequest;
import com.gcare.hms.configuration.ConfigApi.SettingInfo;
import com.gcare.hms.configuration.ConfigApi.VersionView;
import com.gcare.hms.configuration.ConfigStore.Logo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned configuration and facility branding (task 5.2; docs/21). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Configuration")
class ConfigController {

  private final ConfigurationService service;

  ConfigController(ConfigurationService service) {
    this.service = service;
  }

  @GetMapping("/settings")
  @Operation(summary = "Settings you can manage")
  List<SettingInfo> settings() {
    return service.settings();
  }

  @GetMapping("/settings/pending")
  @Operation(summary = "Proposed changes you can decide")
  List<VersionView> pending() {
    return service.pending();
  }

  @GetMapping("/settings/{key}/versions")
  @Operation(
      summary = "All versions of a setting",
      description = "facility_id for facility settings.")
  List<VersionView> versions(
      @PathVariable String key,
      @RequestParam(name = "facility_id", required = false) UUID facilityId) {
    return service.versions(key, facilityId);
  }

  @PostMapping("/settings/{key}/versions")
  @Operation(summary = "Propose a new version", description = "A different person must approve it.")
  ResponseEntity<VersionView> propose(
      @PathVariable String key, @Valid @RequestBody ProposeRequest request) {
    VersionView created = service.propose(key, request);
    return ResponseEntity.created(URI.create("/api/v1/settings/versions/" + created.id()))
        .body(created);
  }

  @PostMapping("/settings/versions/{id}/approve")
  @Operation(
      summary = "Approve a proposed version",
      description = "Not your own; it becomes active.")
  VersionView approve(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.decide(id, true, request);
  }

  @PostMapping("/settings/versions/{id}/reject")
  @Operation(summary = "Reject a proposed version", description = "Not your own.")
  VersionView reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.decide(id, false, request);
  }

  @GetMapping("/facilities/{id}/branding")
  @Operation(summary = "Approved branding of a facility you work at")
  BrandingView branding(@PathVariable UUID id) {
    return service.branding(id);
  }

  @PostMapping("/facilities/{id}/logos")
  @Operation(
      summary = "Upload a logo",
      description = "PNG or JPEG, at most 256 KiB. Used once approved branding refers to it.")
  ResponseEntity<LogoView> uploadLogo(
      @PathVariable UUID id, @Valid @RequestBody LogoUpload upload) {
    LogoView created = service.uploadLogo(id, upload);
    return ResponseEntity.created(URI.create("/api/v1/facility-logos/" + created.id()))
        .body(created);
  }

  @GetMapping("/facility-logos/{id}")
  @Operation(summary = "Logo image")
  ResponseEntity<byte[]> logo(@PathVariable UUID id) {
    Logo logo = service.logo(id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(logo.contentType()))
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
        .header("Content-Security-Policy", "default-src 'none'")
        .body(logo.bytes());
  }
}
