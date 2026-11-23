package com.gcare.hms.configuration;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * A setting that can be versioned and approved (task 5.2; docs/21). Modules contribute their own
 * definitions as Spring beans; the configuration module stores, approves and serves the versions. A
 * definition never supplies default values: an empty setting means "not configured".
 */
public interface SettingDefinition {

  enum Scope {
    ORGANIZATION,
    FACILITY
  }

  /** Stable key, e.g. {@code TRIAGE}. */
  String key();

  String title();

  String description();

  Scope scope();

  /** Permission needed to see, propose and approve versions. */
  String permission();

  /**
   * Checks a proposed value and returns it in canonical form, or throws {@code
   * ApiException.Invalid} with a field path. {@code facilityId} is null for organization settings.
   */
  JsonNode normalize(JsonNode value, UUID facilityId);
}
