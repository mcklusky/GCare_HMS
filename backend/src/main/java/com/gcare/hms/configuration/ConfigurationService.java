package com.gcare.hms.configuration;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.configuration.ConfigApi.BrandingView;
import com.gcare.hms.configuration.ConfigApi.DecisionRequest;
import com.gcare.hms.configuration.ConfigApi.LogoUpload;
import com.gcare.hms.configuration.ConfigApi.LogoView;
import com.gcare.hms.configuration.ConfigApi.ProposeRequest;
import com.gcare.hms.configuration.ConfigApi.SettingInfo;
import com.gcare.hms.configuration.ConfigApi.VersionStatus;
import com.gcare.hms.configuration.ConfigApi.VersionView;
import com.gcare.hms.configuration.ConfigStore.Logo;
import com.gcare.hms.configuration.SettingDefinition.Scope;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Versioned configuration with two-person approval (task 5.2; FR-042; docs/21). Decided 2026-11-23:
 * a change is a new proposed version; a different person holding the setting's permission approves
 * it and it becomes active; every version is kept. Rollback is proposing an earlier value again.
 * Settings are contributed by modules as {@link SettingDefinition} beans and never carry built-in
 * values.
 */
@Service
public class ConfigurationService {

  public static final String FACILITY_BRANDING = "FACILITY_BRANDING";
  private static final int MAX_LOGO_BYTES = 262_144;

  private final ConfigStore store;
  private final AuditService audit;
  private final JsonMapper json;
  private final Map<String, SettingDefinition> definitions = new LinkedHashMap<>();

  ConfigurationService(
      ConfigStore store, AuditService audit, JsonMapper json, List<SettingDefinition> settings) {
    this.store = store;
    this.audit = audit;
    this.json = json;
    for (SettingDefinition d : settings) {
      if (definitions.put(d.key(), d) != null) {
        throw new IllegalStateException("Duplicate setting key " + d.key());
      }
    }
  }

  // --- for other modules (no permission check: they read the active value for their own rules) ---

  /** The active value of a setting, if one has been approved. */
  @Transactional(readOnly = true)
  public Optional<JsonNode> active(String key, UUID facilityId) {
    return store.active(key, facilityId).map(VersionView::value);
  }

  /** Approved branding of a facility, falling back to the facility's name. */
  @Transactional(readOnly = true)
  public BrandingView brandingOf(UUID facilityId) {
    String name = store.facilityName(facilityId).orElse("");
    Optional<JsonNode> value = active(FACILITY_BRANDING, facilityId);
    if (value.isEmpty()) {
      return new BrandingView(facilityId, name, List.of(), null, null, null, null, false);
    }
    JsonNode v = value.get();
    List<String> lines = new ArrayList<>();
    v.path("address_lines").forEach(l -> lines.add(l.asString()));
    return new BrandingView(
        facilityId,
        text(v, "display_name") == null ? name : text(v, "display_name"),
        lines,
        text(v, "phone"),
        text(v, "registration_number"),
        text(v, "receipt_footer"),
        text(v, "logo_id") == null ? null : UUID.fromString(text(v, "logo_id")),
        true);
  }

  // --- settings and versions -------------------------------------------------------------------

  /** Settings the caller may manage. */
  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.CONFIG_CLINICAL_MANAGE
          + "', '"
          + Permissions.CONFIG_ORGANIZATION_MANAGE
          + "')")
  public List<SettingInfo> settings() {
    CurrentUser user = CurrentUser.require();
    return definitions.values().stream()
        .filter(d -> user.hasPermission(d.permission()))
        .map(d -> new SettingInfo(d.key(), d.title(), d.description(), d.scope(), d.permission()))
        .toList();
  }

  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.CONFIG_CLINICAL_MANAGE
          + "', '"
          + Permissions.CONFIG_ORGANIZATION_MANAGE
          + "')")
  @Transactional(readOnly = true)
  public List<VersionView> versions(String key, UUID facilityId) {
    CurrentUser user = CurrentUser.require();
    SettingDefinition d = definitionFor(user, key, facilityId);
    return store.versions(d.key(), facilityId);
  }

  /** Proposals waiting for a decision that the caller may decide (any setting they manage). */
  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.CONFIG_CLINICAL_MANAGE
          + "', '"
          + Permissions.CONFIG_ORGANIZATION_MANAGE
          + "')")
  @Transactional(readOnly = true)
  public List<VersionView> pending() {
    CurrentUser user = CurrentUser.require();
    return store.pending().stream().filter(v -> mayManage(user, v.key(), v.facilityId())).toList();
  }

  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.CONFIG_CLINICAL_MANAGE
          + "', '"
          + Permissions.CONFIG_ORGANIZATION_MANAGE
          + "')")
  @Transactional
  public VersionView propose(String key, ProposeRequest request) {
    CurrentUser user = CurrentUser.require();
    SettingDefinition d = definitionFor(user, key, request.facilityId());
    JsonNode value = d.normalize(request.value(), request.facilityId());
    UUID id = UUID.randomUUID();
    int number = store.nextNumber(d.key(), request.facilityId());
    try {
      store.insert(
          id,
          user.organizationId(),
          d.key(),
          request.facilityId(),
          number,
          json.writeValueAsString(value),
          request.reason().trim(),
          user.userId());
    } catch (DuplicateKeyException e) {
      throw new ApiException.Conflict(
          "A change to this setting is already waiting for approval. Decide it first.");
    }
    audit(user, request.facilityId(), "CONFIG_PROPOSED", id, d.key(), number);
    return store.version(id).orElseThrow();
  }

  @PreAuthorize(
      "hasAnyAuthority('"
          + Permissions.CONFIG_CLINICAL_MANAGE
          + "', '"
          + Permissions.CONFIG_ORGANIZATION_MANAGE
          + "')")
  @Transactional
  public VersionView decide(UUID id, boolean approve, DecisionRequest request) {
    CurrentUser user = CurrentUser.require();
    VersionView v =
        store
            .version(id)
            .filter(x -> mayManage(user, x.key(), x.facilityId()))
            .orElseThrow(() -> new ApiException.NotFound("Configuration version"));
    if (v.status() != VersionStatus.PROPOSED) {
      throw new ApiException.InvalidTransition("This version has already been decided");
    }
    if (v.proposedBy().id().equals(user.userId())) {
      throw new ApiException.InvalidTransition(
          "You proposed this change, so another person must decide it");
    }
    if (approve) {
      store.supersedeActive(v.key(), v.facilityId());
    }
    if (store.decide(
            id,
            request.version(),
            approve ? VersionStatus.ACTIVE : VersionStatus.REJECTED,
            request.note().trim(),
            user.userId())
        == 0) {
      throw new ApiException.Conflict("This proposal was changed. Reload it.");
    }
    audit(
        user,
        v.facilityId(),
        approve ? "CONFIG_APPROVED" : "CONFIG_REJECTED",
        id,
        v.key(),
        v.versionNumber());
    return store.version(id).orElseThrow();
  }

  // --- branding and logos ----------------------------------------------------------------------

  /** Approved branding of a facility in any of the caller's scopes. */
  @Transactional(readOnly = true)
  public BrandingView branding(UUID facilityId) {
    CurrentUser user = CurrentUser.require();
    if (!user.anyScope().includes(facilityId)) {
      throw new ApiException.NotFound("Facility");
    }
    return brandingOf(facilityId);
  }

  @PreAuthorize("hasAuthority('" + Permissions.CONFIG_ORGANIZATION_MANAGE + "')")
  @Transactional
  public LogoView uploadLogo(UUID facilityId, LogoUpload upload) {
    CurrentUser user = CurrentUser.require();
    if (!user.scopeOf(Permissions.CONFIG_ORGANIZATION_MANAGE).includes(facilityId)
        || store.facilityName(facilityId).isEmpty()) {
      throw new ApiException.NotFound("Facility");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(upload.dataBase64());
    } catch (IllegalArgumentException e) {
      throw new ApiException.Invalid("data_base64", "is not valid base64");
    }
    if (bytes.length == 0 || bytes.length > MAX_LOGO_BYTES) {
      throw new ApiException.Invalid("data_base64", "the image must be at most 256 KiB");
    }
    if (!matches(upload.contentType(), bytes)) {
      throw new ApiException.Invalid("data_base64", "is not a " + upload.contentType() + " image");
    }
    UUID id = UUID.randomUUID();
    store.insertLogo(
        id,
        user.organizationId(),
        facilityId,
        upload.contentType(),
        bytes,
        sha256(bytes),
        user.userId());
    audit(user, facilityId, "LOGO_UPLOADED", id, FACILITY_BRANDING, 0);
    return new LogoView(id, facilityId);
  }

  /**
   * A logo's bytes: for anyone working at the facility once approved branding uses it, and for
   * branding managers of the facility (to review a proposal).
   */
  @Transactional(readOnly = true)
  public Logo logo(UUID id) {
    CurrentUser user = CurrentUser.require();
    Logo logo = store.logo(id).orElseThrow(() -> new ApiException.NotFound("Logo"));
    boolean manager =
        user.scopeOf(Permissions.CONFIG_ORGANIZATION_MANAGE).includes(logo.facilityId());
    boolean inUse =
        user.anyScope().includes(logo.facilityId())
            && id.equals(brandingOf(logo.facilityId()).logoId());
    if (!manager && !inUse) {
      throw new ApiException.NotFound("Logo");
    }
    return logo;
  }

  /** Whether a logo exists for the facility (branding validation). */
  @Transactional(readOnly = true)
  public boolean logoBelongsTo(UUID logoId, UUID facilityId) {
    return store.logoBelongsTo(logoId, facilityId);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private SettingDefinition definitionFor(CurrentUser user, String key, UUID facilityId) {
    SettingDefinition d = definitions.get(key);
    if (d == null) {
      throw new ApiException.NotFound("Setting");
    }
    if (!user.hasPermission(d.permission())) {
      throw new AccessDeniedException("This setting needs " + d.permission());
    }
    if (d.scope() == Scope.FACILITY) {
      if (facilityId == null) {
        throw new ApiException.Invalid("facility_id", "is required for this setting");
      }
      if (!user.scopeOf(d.permission()).includes(facilityId)
          || store.facilityName(facilityId).isEmpty()) {
        throw new ApiException.NotFound("Facility");
      }
    } else if (facilityId != null) {
      throw new ApiException.Invalid("facility_id", "must be empty for an organization setting");
    }
    return d;
  }

  private boolean mayManage(CurrentUser user, String key, UUID facilityId) {
    SettingDefinition d = definitions.get(key);
    if (d == null || !user.hasPermission(d.permission())) {
      return false;
    }
    return d.scope() == Scope.ORGANIZATION || user.scopeOf(d.permission()).includes(facilityId);
  }

  private static String text(JsonNode v, String field) {
    JsonNode n = v.get(field);
    return n == null || n.isNull() ? null : n.asString();
  }

  private static boolean matches(String type, byte[] b) {
    if ("image/png".equals(type)) {
      return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }
    return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Audited after commit; metadata holds the setting key and version number, never values. */
  private void audit(
      CurrentUser user, UUID facilityId, String action, UUID id, String key, int number) {
    AuditEvent event =
        new AuditEvent(
            user.organizationId(),
            facilityId,
            user.userId(),
            action,
            "Configuration",
            id.toString(),
            AuditOutcome.SUCCESS,
            null,
            Instant.now(),
            null,
            Map.of("setting", key, "version", Integer.toString(number)));
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
