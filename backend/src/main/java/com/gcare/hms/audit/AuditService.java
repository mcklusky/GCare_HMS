package com.gcare.hms.audit;

/**
 * Records audit events. The record is committed independently of the caller's transaction, so
 * denied and failed actions stay audited even when the business transaction rolls back.
 *
 * <p>Call it from the organization context of the event ({@code OrganizationContext}); row-level
 * security rejects events for another organization.
 */
public interface AuditService {

  void record(AuditEvent event);
}
