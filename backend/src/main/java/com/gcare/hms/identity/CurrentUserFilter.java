package com.gcare.hms.identity;

import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.identity.UserDirectory.IdentityLink;
import com.gcare.hms.shared.security.CurrentUser;
import com.gcare.hms.shared.security.CurrentUserAuthentication;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs on every request with a signed-in session, before authorization decisions.
 *
 * <ol>
 *   <li>Ends the session if it is older than the absolute lifetime.
 *   <li>Loads the GCare user for the session's identity from the database. If the identity is not
 *       linked, or the user or organization is no longer active, the session is ended and the
 *       request continues unauthenticated (so protected endpoints answer 401).
 *   <li>Replaces the request's authentication with {@link CurrentUserAuthentication} (permissions
 *       as authorities) and runs the rest of the request in that user's {@link
 *       OrganizationContext}, which row-level security enforces.
 * </ol>
 *
 * Not a Spring bean on purpose: as a bean, Boot would also register it as a servlet filter.
 */
final class CurrentUserFilter extends OncePerRequestFilter {

  private final UserDirectory directory;
  private final IdentityAudit audit;
  private final Duration sessionMaxLifetime;

  CurrentUserFilter(UserDirectory directory, IdentityAudit audit, Duration sessionMaxLifetime) {
    this.directory = directory;
    this.audit = audit;
    this.sessionMaxLifetime = sessionMaxLifetime;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof OAuth2AuthenticationToken token)
        || !(token.getPrincipal() instanceof OidcUser oidc)) {
      chain.doFilter(request, response);
      return;
    }

    Optional<IdentityLink> link =
        directory.findLink(
            oidc.getIssuer() != null ? oidc.getIssuer().toString() : null, oidc.getSubject());

    if (sessionTooOld(request)) {
      endSession(request, link, "MAX_LIFETIME_REACHED");
      chain.doFilter(request, response);
      return;
    }

    Optional<CurrentUser> user = link.flatMap(directory::loadActive);
    if (user.isEmpty()) {
      endSession(request, link, link.isPresent() ? "ACCOUNT_NOT_ACTIVE" : "IDENTITY_NOT_LINKED");
      chain.doFilter(request, response);
      return;
    }

    CurrentUser currentUser = user.get();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new CurrentUserAuthentication(currentUser));
    SecurityContextHolder.setContext(context);

    try {
      OrganizationContext.callAsChecked(
          currentUser.organizationId(),
          () -> {
            chain.doFilter(request, response);
            return null;
          });
    } catch (IOException | ServletException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new ServletException(e);
    }
  }

  private boolean sessionTooOld(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return false;
    }
    Instant created = Instant.ofEpochMilli(session.getCreationTime());
    return created.plus(sessionMaxLifetime).isBefore(Instant.now());
  }

  private void endSession(HttpServletRequest request, Optional<IdentityLink> link, String reason) {
    audit.record(
        link.map(IdentityLink::organizationId).orElse(null),
        link.map(IdentityLink::userId).orElse(null),
        "SESSION_ENDED",
        IdentityAudit.RESOURCE_SESSION,
        null,
        AuditOutcome.DENIED,
        request,
        Map.of("reason", reason));
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
  }
}
