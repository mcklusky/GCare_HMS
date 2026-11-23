package com.gcare.hms.billing;

import com.gcare.hms.configuration.ConfigurationService;
import com.gcare.hms.configuration.SettingDefinition;
import com.gcare.hms.shared.security.Permissions;
import com.gcare.hms.shared.web.ApiException;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Operational billing options (task 5.2; docs/21): how many days back "visits without an invoice"
 * looks. Without an approved version the engineering default of 7 days applies (an operational
 * convenience, not a financial rule).
 */
@Component
class BillingOptionsSetting implements SettingDefinition {

  static final String KEY = "BILLING_OPTIONS";
  static final int DEFAULT_DAYS = 7;
  static final int MAX_DAYS = 90;

  private final ConfigurationService configuration;

  BillingOptionsSetting(@Lazy ConfigurationService configuration) {
    this.configuration = configuration;
  }

  /** Look-back days for visits without an invoice. */
  int billableVisitDays() {
    return configuration
        .active(KEY, null)
        .map(v -> v.path("billable_visit_days").asInt(DEFAULT_DAYS))
        .orElse(DEFAULT_DAYS);
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public String title() {
    return "Billing options";
  }

  @Override
  public String description() {
    return "How many days back the cashier's list of visits without an invoice looks (1 to 90; default 7).";
  }

  @Override
  public Scope scope() {
    return Scope.ORGANIZATION;
  }

  @Override
  public String permission() {
    return Permissions.CONFIG_ORGANIZATION_MANAGE;
  }

  @Override
  public JsonNode normalize(JsonNode value, UUID facilityId) {
    JsonNode days = value == null ? null : value.get("billable_visit_days");
    if (value == null
        || !value.isObject()
        || value.size() != 1
        || days == null
        || !days.isIntegralNumber()
        || days.asInt() < 1
        || days.asInt() > MAX_DAYS) {
      throw new ApiException.Invalid(
          "value.billable_visit_days", "must be a whole number of days from 1 to " + MAX_DAYS);
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("billable_visit_days", days.asInt());
    return out;
  }
}
