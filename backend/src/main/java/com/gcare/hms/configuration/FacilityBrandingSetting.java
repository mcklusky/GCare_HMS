package com.gcare.hms.configuration;

import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Facility branding and receipt text (task 5.2; docs/21): display name, address lines, phone,
 * registration/tax number, receipt footer and an optional uploaded logo. Shown in the app header
 * and on receipts. Colours stay standard for accessibility.
 */
@Component
class FacilityBrandingSetting implements SettingDefinition {

  private static final Set<String> FIELDS =
      Set.of(
          "display_name",
          "address_lines",
          "phone",
          "registration_number",
          "receipt_footer",
          "logo_id");

  private final ConfigStore store;

  FacilityBrandingSetting(ConfigStore store) {
    this.store = store;
  }

  @Override
  public String key() {
    return ConfigurationService.FACILITY_BRANDING;
  }

  @Override
  public String title() {
    return "Facility branding and receipt text";
  }

  @Override
  public String description() {
    return "Name, address, phone, registration number, receipt footer and logo shown in the app and on receipts.";
  }

  @Override
  public Scope scope() {
    return Scope.FACILITY;
  }

  @Override
  public String permission() {
    return Permissions.CONFIG_ORGANIZATION_MANAGE;
  }

  @Override
  public JsonNode normalize(JsonNode value, UUID facilityId) {
    if (value == null || !value.isObject()) {
      throw new ApiException.Invalid("value", "must be an object");
    }
    value
        .propertyNames()
        .forEach(f -> {
          if (!FIELDS.contains(f)) {
            throw new ApiException.Invalid("value." + f, "is not a branding field");
          }
        });
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    String name = text(value, "display_name", 120);
    if (name == null) {
      throw new ApiException.Invalid("value.display_name", "is required");
    }
    out.put("display_name", name);
    ArrayNode lines = out.putArray("address_lines");
    JsonNode given = value.get("address_lines");
    if (given != null && !given.isNull()) {
      if (!given.isArray() || given.size() > 4) {
        throw new ApiException.Invalid("value.address_lines", "must be a list of at most 4 lines");
      }
      for (JsonNode l : given) {
        if (!l.isString() || l.asString().isBlank() || l.asString().length() > 120) {
          throw new ApiException.Invalid(
              "value.address_lines", "each line must be text of at most 120 characters");
        }
        lines.add(l.asString().trim());
      }
    }
    putIfPresent(out, "phone", text(value, "phone", 40));
    putIfPresent(out, "registration_number", text(value, "registration_number", 60));
    putIfPresent(out, "receipt_footer", text(value, "receipt_footer", 300));
    String logo = text(value, "logo_id", 36);
    if (logo != null) {
      UUID id;
      try {
        id = UUID.fromString(logo);
      } catch (IllegalArgumentException e) {
        throw new ApiException.Invalid("value.logo_id", "is not a logo id");
      }
      if (!store.logoBelongsTo(id, facilityId)) {
        throw new ApiException.Invalid("value.logo_id", "is not a logo uploaded for this facility");
      }
      out.put("logo_id", id.toString());
    }
    return out;
  }

  private static void putIfPresent(ObjectNode out, String field, String value) {
    if (value != null) {
      out.put(field, value);
    }
  }

  private static String text(JsonNode value, String field, int max) {
    JsonNode n = value.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    if (!n.isString()) {
      throw new ApiException.Invalid("value." + field, "must be text");
    }
    String s = n.asString().trim();
    if (s.isEmpty()) {
      return null;
    }
    if (s.length() > max) {
      throw new ApiException.Invalid("value." + field, "must be at most " + max + " characters");
    }
    return s;
  }
}
