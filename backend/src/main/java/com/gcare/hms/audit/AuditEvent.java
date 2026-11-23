package com.gcare.hms.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One security-relevant access or change (FR-026). {@code metadata} is context only (identifiers,
 * reasons, field names) and is redacted before storage; never put clinical content or secrets in
 * it.
 *
 * @param organizationId organization in scope; {@code null} only for platform or pre-authentication
 *     events
 * @param actorId authenticated user, or {@code null} for anonymous/system events
 * @param action verb-style code, e.g. {@code PATIENT_VIEWED}, {@code NOTE_SIGNED}
 * @param resourceType affected resource type, e.g. {@code Patient}
 * @param resourceId affected resource identifier (never a name or other demographic value)
 * @param purpose declared purpose of access where applicable (e.g. break-glass reason)
 */
public record AuditEvent(
    UUID organizationId,
    UUID facilityId,
    UUID actorId,
    String action,
    String resourceType,
    String resourceId,
    AuditOutcome outcome,
    String purpose,
    Instant occurredAt,
    String sourceAddress,
    Map<String, String> metadata) {

  public AuditEvent {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(outcome, "outcome");
    occurredAt = occurredAt != null ? occurredAt : Instant.now();
    metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
  }

  /** Minimal event; use the canonical constructor for the optional fields. */
  public static AuditEvent of(
      UUID organizationId,
      UUID actorId,
      String action,
      String resourceType,
      String resourceId,
      AuditOutcome outcome) {
    return new AuditEvent(
        organizationId,
        null,
        actorId,
        action,
        resourceType,
        resourceId,
        outcome,
        null,
        null,
        null,
        null);
  }
}
