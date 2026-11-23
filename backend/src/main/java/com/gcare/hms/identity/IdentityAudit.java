package com.gcare.hms.identity;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Audit records for sign-in, sign-out, session and access-denied events (FR-026). Writes each event
 * in its organization's context, which row-level security requires; events without an organization
 * (unknown identity, provider error) are platform-level.
 */
@Component
class IdentityAudit {

  static final String RESOURCE_SESSION = "Session";

  private final AuditService audit;

  IdentityAudit(AuditService audit) {
    this.audit = audit;
  }

  void record(
      UUID organizationId,
      UUID actorId,
      String action,
      String resourceType,
      String resourceId,
      AuditOutcome outcome,
      HttpServletRequest request,
      Map<String, String> metadata) {
    AuditEvent event =
        new AuditEvent(
            organizationId,
            null,
            actorId,
            action,
            resourceType,
            resourceId,
            outcome,
            null,
            Instant.now(),
            request != null ? request.getRemoteAddr() : null,
            metadata);
    if (organizationId == null) {
      audit.record(event);
    } else {
      OrganizationContext.runAs(organizationId, () -> audit.record(event));
    }
  }
}
