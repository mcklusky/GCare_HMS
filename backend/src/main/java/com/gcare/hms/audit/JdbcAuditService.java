package com.gcare.hms.audit;

import com.gcare.hms.shared.web.CorrelationIdFilter;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Append-only audit writer backed by the {@code audit_events} table (migration V1). */
@Service
class JdbcAuditService implements AuditService {

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final TransactionTemplate independentTransaction;

  JdbcAuditService(JdbcClient jdbc, JsonMapper json, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.json = json;
    this.independentTransaction = new TransactionTemplate(transactions);
    this.independentTransaction.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void record(AuditEvent event) {
    String metadata = json.writeValueAsString(MetadataRedactor.redact(event.metadata()));
    independentTransaction.executeWithoutResult(status ->
        jdbc.sql(
                """
                INSERT INTO audit_events (
                    id, organization_id, facility_id, actor_id, action, resource_type,
                    resource_id, outcome, purpose, occurred_at, correlation_id,
                    source_address, metadata)
                VALUES (
                    :id, :organizationId, :facilityId, :actorId, :action, :resourceType,
                    :resourceId, :outcome, :purpose, :occurredAt, :correlationId,
                    :sourceAddress, CAST(:metadata AS jsonb))
                """)
            .param("id", UUID.randomUUID())
            .param("organizationId", event.organizationId())
            .param("facilityId", event.facilityId())
            .param("actorId", event.actorId())
            .param("action", event.action())
            .param("resourceType", event.resourceType())
            .param("resourceId", event.resourceId())
            .param("outcome", event.outcome().name())
            .param("purpose", event.purpose())
            .param("occurredAt", Timestamp.from(event.occurredAt()))
            .param("correlationId", CorrelationIdFilter.current())
            .param("sourceAddress", event.sourceAddress())
            .param("metadata", metadata)
            .update());
  }
}
