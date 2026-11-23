package com.gcare.hms.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.identity.UserDirectory.IdentityLink;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/auth/logout} (CSRF-protected). Ends the GCare session, audits it, and returns
 * the identity provider's sign-out URL so the frontend can end the Keycloak session too (OIDC
 * RP-initiated logout). Without that second step, the next sign-in would not ask for a password.
 */
final class SignOutHandlers implements LogoutHandler, LogoutSuccessHandler {

  /** Response body. {@code logout_url} is null when there was no signed-in session. */
  record LogoutResponse(@JsonProperty("logout_url") String logoutUrl) {}

  private final UserDirectory directory;
  private final IdentityAudit audit;
  private final ClientRegistrationRepository clients;
  private final JsonMapper json;
  private final String postLogoutRedirect;

  SignOutHandlers(
      UserDirectory directory,
      IdentityAudit audit,
      ClientRegistrationRepository clients,
      JsonMapper json,
      String postLogoutRedirect) {
    this.directory = directory;
    this.audit = audit;
    this.clients = clients;
    this.json = json;
    this.postLogoutRedirect = postLogoutRedirect;
  }

  /** Runs before the session is invalidated. */
  @Override
  public void logout(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
    if (!(authentication instanceof OAuth2AuthenticationToken token)
        || !(token.getPrincipal() instanceof OidcUser oidc)) {
      return;
    }
    String issuer = oidc.getIssuer() != null ? oidc.getIssuer().toString() : null;
    Optional<IdentityLink> link = directory.findLink(issuer, oidc.getSubject());
    audit.record(
        link.map(IdentityLink::organizationId).orElse(null),
        link.map(IdentityLink::userId).orElse(null),
        "SIGNED_OUT",
        IdentityAudit.RESOURCE_SESSION,
        null,
        AuditOutcome.SUCCESS,
        request,
        Map.of());
  }

  @Override
  public void onLogoutSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    response.setStatus(HttpStatus.OK.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    json.writeValue(
        response.getOutputStream(), new LogoutResponse(providerLogoutUrl(authentication)));
  }

  private String providerLogoutUrl(Authentication authentication) {
    if (!(authentication instanceof OAuth2AuthenticationToken token)
        || !(token.getPrincipal() instanceof OidcUser oidc)) {
      return null;
    }
    ClientRegistration registration =
        clients.findByRegistrationId(token.getAuthorizedClientRegistrationId());
    if (registration == null) {
      return null;
    }
    Object endSession =
        registration.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
    if (endSession == null) {
      return null;
    }
    UriComponentsBuilder url =
        UriComponentsBuilder.fromUriString(endSession.toString())
            .queryParam("client_id", registration.getClientId())
            .queryParam("id_token_hint", oidc.getIdToken().getTokenValue());
    if (postLogoutRedirect != null && !postLogoutRedirect.isBlank()) {
      url.queryParam("post_logout_redirect_uri", postLogoutRedirect);
    }
    return url.encode().build().toUriString();
  }
}
