package com.gcare.hms.encounter;

import com.gcare.hms.configuration.ConfigurationService;
import com.gcare.hms.configuration.SettingDefinition;
import com.gcare.hms.encounter.TriageProperties.Limit;
import com.gcare.hms.encounter.TriageProperties.Measure;
import com.gcare.hms.encounter.TriageProperties.UrgencyLevel;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The triage scale and alarm limits in force (task 5.2; docs/21). An approved {@code TRIAGE}
 * configuration version wins; otherwise the deployment's {@code gcare.triage.*} properties apply,
 * which are empty by default. Values are clinical rules entered by a clinical lead and approved by
 * a second one; engineering never supplies them.
 */
@Component
public class TriageConfig implements SettingDefinition {

  public static final String KEY = "TRIAGE";

  private final TriageProperties deployment;
  private final ConfigurationService configuration;

  TriageConfig(TriageProperties deployment, @Lazy ConfigurationService configuration) {
    this.deployment = deployment;
    this.configuration = configuration;
  }

  /** The scale and limits to use now. */
  public TriageProperties current() {
    return configuration.active(KEY, null).map(TriageConfig::parse).orElse(deployment);
  }

  // --- setting definition ----------------------------------------------------------------------

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public String title() {
    return "Triage urgency scale and alarm limits";
  }

  @Override
  public String description() {
    return "Clinical rules: urgency levels (code, label, rank; lower rank is seen first) and"
        + " low/high alarm limits per vital sign. Set by a clinical lead; empty means not configured.";
  }

  @Override
  public Scope scope() {
    return Scope.ORGANIZATION;
  }

  @Override
  public String permission() {
    return Permissions.CONFIG_CLINICAL_MANAGE;
  }

  @Override
  public JsonNode normalize(JsonNode value, UUID facilityId) {
    if (value == null || !value.isObject()) {
      throw new ApiException.Invalid("value", "must be an object");
    }
    TriageProperties parsed = parse(value);
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    var levels = out.putArray("urgency_levels");
    for (UrgencyLevel l : parsed.urgencyLevels()) {
      levels.addObject().put("code", l.code()).put("label", l.label()).put("rank", l.rank());
    }
    ObjectNode limits = out.putObject("alarm_limits");
    for (Measure m : Measure.values()) {
      Limit limit = parsed.alarmLimits().get(m.name());
      if (limit != null) {
        ObjectNode n = limits.putObject(m.name());
        if (limit.low() != null) {
          n.put("low", limit.low());
        }
        if (limit.high() != null) {
          n.put("high", limit.high());
        }
      }
    }
    return out;
  }

  /** Checks structure only (unique codes and ranks, known measures, low below high). */
  static TriageProperties parse(JsonNode value) {
    List<UrgencyLevel> levels = new ArrayList<>();
    Set<Integer> ranks = new HashSet<>();
    Set<String> codes = new HashSet<>();
    JsonNode given = value.path("urgency_levels");
    if (!given.isMissingNode() && !given.isNull() && !given.isArray()) {
      throw new ApiException.Invalid("value.urgency_levels", "must be a list");
    }
    for (JsonNode l : given) {
      String code = l.path("code").asString("").trim();
      String label = l.path("label").asString("").trim();
      int rank = l.path("rank").asInt(0);
      if (!code.matches("^[A-Z0-9_]{1,30}$")) {
        throw new ApiException.Invalid(
            "value.urgency_levels", "codes use capital letters, digits or _ (at most 30)");
      }
      if (label.isEmpty() || label.length() > 60) {
        throw new ApiException.Invalid(
            "value.urgency_levels", "each level needs a label (at most 60)");
      }
      if (rank < 1 || rank > 99 || !ranks.add(rank) || !codes.add(code)) {
        throw new ApiException.Invalid(
            "value.urgency_levels", "codes and ranks (1 to 99) must be unique");
      }
      levels.add(new UrgencyLevel(code, label, rank));
    }
    Map<String, Limit> limits = new LinkedHashMap<>();
    JsonNode givenLimits = value.path("alarm_limits");
    if (!givenLimits.isMissingNode() && !givenLimits.isNull() && !givenLimits.isObject()) {
      throw new ApiException.Invalid("value.alarm_limits", "must be an object");
    }
    for (String measure : givenLimits.propertyNames()) {
      try {
        Measure.valueOf(measure);
      } catch (IllegalArgumentException e) {
        throw new ApiException.Invalid("value.alarm_limits", measure + " is not a known measure");
      }
      JsonNode n = givenLimits.get(measure);
      BigDecimal low = number(n, "low", measure);
      BigDecimal high = number(n, "high", measure);
      if (low == null && high == null) {
        throw new ApiException.Invalid(
            "value.alarm_limits", measure + " needs a low or high limit");
      }
      if (low != null && high != null && low.compareTo(high) >= 0) {
        throw new ApiException.Invalid("value.alarm_limits", measure + ": low must be below high");
      }
      limits.put(measure, new Limit(low, high));
    }
    return new TriageProperties(levels, limits);
  }

  private static BigDecimal number(JsonNode n, String field, String measure) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    try {
      return new BigDecimal(v.asString());
    } catch (NumberFormatException e) {
      throw new ApiException.Invalid(
          "value.alarm_limits", measure + "." + field + " must be a number");
    }
  }
}
