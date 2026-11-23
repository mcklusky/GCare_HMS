package com.gcare.hms.identity.bootstrap;

import com.gcare.hms.organization.HostingModel;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Input for the first-administrator bootstrap ({@code gcare.bootstrap.*}). All values come from the
 * operator at run time; none are committed for real deployments.
 */
@ConfigurationProperties("gcare.bootstrap")
record BootstrapProperties(
    boolean enabled, Organization organization, Facility facility, Admin admin) {

  record Organization(
      String code,
      String name,
      String countryCode,
      @DefaultValue("DEDICATED") HostingModel hostingModel) {}

  record Facility(String code, String name, String timezone) {}

  /**
   * The administrator's GCare account and the Keycloak account it is linked to.
   *
   * @param issuer the Keycloak realm URL exactly as it appears in tokens ({@code iss})
   * @param subject the Keycloak user ID ({@code sub})
   */
  record Admin(String username, String displayName, String email, String issuer, String subject) {}
}
