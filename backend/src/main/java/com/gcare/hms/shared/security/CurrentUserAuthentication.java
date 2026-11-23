package com.gcare.hms.shared.security;

import java.util.stream.Collectors;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Request-scoped authentication built from the database after the session's identity has been
 * checked. Authorities are permission codes. It is never stored in the HTTP session; the session
 * only holds the identity-provider sign-in.
 */
public final class CurrentUserAuthentication extends AbstractAuthenticationToken {

  private final CurrentUser user;

  public CurrentUserAuthentication(CurrentUser user) {
    super(
        user.permissions().stream()
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toUnmodifiableList()));
    this.user = user;
    setAuthenticated(true);
  }

  @Override
  public CurrentUser getPrincipal() {
    return user;
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public String getName() {
    return user.userId().toString();
  }
}
