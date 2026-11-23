package com.gcare.hms.identity;

import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.context.event.EventListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Audits permission denials for signed-in users (FR-026). Requests without a signed-in user are not
 * audited here; they get 401 and carry no actor.
 */
@Component
class AccessDeniedAuditor {

  private final IdentityAudit audit;

  AccessDeniedAuditor(IdentityAudit audit) {
    this.audit = audit;
  }

  @EventListener
  void onDenied(AuthorizationDeniedEvent<?> event) {
    Authentication authentication = event.getAuthentication().get();
    if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)) {
      return;
    }
    Object target = event.getObject();
    String resourceType;
    String resourceId;
    HttpServletRequest request = null;
    if (target instanceof MethodInvocation invocation) {
      resourceType = "Operation";
      resourceId =
          invocation.getMethod().getDeclaringClass().getSimpleName()
              + "."
              + invocation.getMethod().getName();
    } else if (target instanceof HttpServletRequest http) {
      request = http;
      resourceType = "Endpoint";
      resourceId = http.getMethod() + " " + http.getRequestURI();
    } else {
      resourceType = "Unknown";
      resourceId = null;
    }
    audit.record(
        user.organizationId(),
        user.userId(),
        "ACCESS_DENIED",
        resourceType,
        truncate(resourceId),
        AuditOutcome.DENIED,
        request,
        Map.of());
  }

  private static String truncate(String value) {
    return value != null && value.length() > 100 ? value.substring(0, 100) : value;
  }
}
