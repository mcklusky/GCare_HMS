package com.gcare.hms.identity;

import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.identity.UserDirectory.IdentityLink;
import com.gcare.hms.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Completes the Keycloak sign-in. A valid Keycloak sign-in is only accepted when it is linked to an
 * active GCare user; otherwise the new session is discarded at once. Every outcome is audited.
 */
final class SignInHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

  /** Value of the {@code login_error} parameter when the account cannot use GCare. */
  static final String ERROR_ACCOUNT_NOT_ENABLED = "account_not_enabled";

  /** Value of the {@code login_error} parameter when the identity provider sign-in failed. */
  static final String ERROR_SIGN_IN_FAILED = "sign_in_failed";

  private final UserDirectory directory;
  private final IdentityAudit audit;
  private final String postLoginRedirect;

  SignInHandlers(UserDirectory directory, IdentityAudit audit, String postLoginRedirect) {
    this.directory = directory;
    this.audit = audit;
    this.postLoginRedirect = postLoginRedirect;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    OidcUser oidc = (OidcUser) authentication.getPrincipal();
    String issuer = oidc.getIssuer() != null ? oidc.getIssuer().toString() : null;
    Optional<IdentityLink> link = directory.findLink(issuer, oidc.getSubject());
    Optional<CurrentUser> user = link.flatMap(directory::loadActive);

    if (user.isEmpty()) {
      audit.record(
          link.map(IdentityLink::organizationId).orElse(null),
          link.map(IdentityLink::userId).orElse(null),
          "SIGN_IN_REFUSED",
          IdentityAudit.RESOURCE_SESSION,
          null,
          AuditOutcome.DENIED,
          request,
          link.isPresent()
              ? Map.of("reason", "ACCOUNT_NOT_ACTIVE")
              : Map.of("reason", "IDENTITY_NOT_LINKED", "subject", oidc.getSubject()));
      HttpSession session = request.getSession(false);
      if (session != null) {
        session.invalidate();
      }
      SecurityContextHolder.clearContext();
      response.sendRedirect(withError(ERROR_ACCOUNT_NOT_ENABLED));
      return;
    }

    directory.recordSignIn(user.get());
    audit.record(
        user.get().organizationId(),
        user.get().userId(),
        "SIGNED_IN",
        IdentityAudit.RESOURCE_SESSION,
        null,
        AuditOutcome.SUCCESS,
        request,
        Map.of());
    response.sendRedirect(postLoginRedirect);
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    String error =
        exception instanceof OAuth2AuthenticationException oauth
            ? oauth.getError().getErrorCode()
            : exception.getClass().getSimpleName();
    audit.record(
        null,
        null,
        "SIGN_IN_FAILED",
        IdentityAudit.RESOURCE_SESSION,
        null,
        AuditOutcome.FAILURE,
        request,
        Map.of("error", error));
    response.sendRedirect(withError(ERROR_SIGN_IN_FAILED));
  }

  private String withError(String code) {
    return UriComponentsBuilder.fromUriString(postLoginRedirect)
        .queryParam("login_error", code)
        .build()
        .toUriString();
  }
}
