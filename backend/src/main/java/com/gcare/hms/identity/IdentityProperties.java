package com.gcare.hms.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sign-in settings ({@code gcare.identity.*}). Session lengths are placeholders until the security
 * owner approves values (docs/project-record/open-decisions.md, OD-03 follow-ups).
 *
 * @param registrationId Spring OAuth2 client registration used for sign-in
 * @param postLoginRedirect where the browser goes after sign-in (the frontend); a {@code
 *     login_error} query parameter is added when sign-in is refused
 * @param postLogoutRedirect where the identity provider sends the browser after sign-out
 * @param sessionMaxLifetime absolute session lifetime, independent of activity
 */
@ConfigurationProperties("gcare.identity")
record IdentityProperties(
    @DefaultValue("keycloak") String registrationId,
    @DefaultValue("/") String postLoginRedirect,
    String postLogoutRedirect,
    @DefaultValue("12h") Duration sessionMaxLifetime) {}
