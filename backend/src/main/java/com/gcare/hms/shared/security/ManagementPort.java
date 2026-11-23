package com.gcare.hms.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * Remembers the port of the separate management server (health details, metrics; docs/23) so its
 * requests can get their own security rules. Until that server has started nothing matches.
 */
@Component
class ManagementPort implements ApplicationListener<WebServerInitializedEvent>, RequestMatcher {

  private volatile int port = -1;

  @Override
  public void onApplicationEvent(WebServerInitializedEvent event) {
    if ("management".equals(event.getApplicationContext().getServerNamespace())) {
      port = event.getWebServer().getPort();
    }
  }

  @Override
  public boolean matches(HttpServletRequest request) {
    return port > 0 && request.getLocalPort() == port;
  }
}
