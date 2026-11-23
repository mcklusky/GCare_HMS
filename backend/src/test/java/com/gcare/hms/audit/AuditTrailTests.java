package com.gcare.hms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gcare.hms.TestcontainersConfiguration;
import com.gcare.hms.shared.tenancy.OrganizationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Audit trail (FR-026): redacted, organization-scoped, append-only, independent of rollback. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuditTrailTests {

  @Autowired private AuditService audit;
  @Autowired private JdbcClient jdbc;
  @Autowired private PlatformTransactionManager transactions;

  private UUID orgA;
  private UUID orgB;

  @BeforeEach
  void setUp() {
    orgA = createOrganization();
    orgB = createOrganization();
  }

  @Test
  void storesEventWithSensitiveMetadataRedacted() {
    String resourceId = UUID.randomUUID().toString();
    OrganizationContext.runAs(
        orgA,
        () ->
            audit.record(
                new AuditEvent(
                    orgA,
                    null,
                    UUID.randomUUID(),
                    "PATIENT_VIEWED",
                    "Patient",
                    resourceId,
                    AuditOutcome.SUCCESS,
                    "Treatment",
                    Instant.now(),
                    "10.0.0.5",
                    Map.of(
                        "reason", "scheduled visit",
                        "password", "hunter2",
                        "patientName", "Test Patient",
                        "noteContent", "free text"))));

    String metadata =
        asSchemaOwner(() ->
            jdbc.sql("SELECT metadata::text FROM audit_events WHERE resource_id = :id")
                .param("id", resourceId)
                .query(String.class)
                .single());

    assertThat(metadata)
        .contains("\"reason\": \"scheduled visit\"")
        .doesNotContain("hunter2")
        .doesNotContain("Test Patient")
        .doesNotContain("free text")
        .contains(MetadataRedactor.REDACTED);
  }

  @Test
  void auditRowsAreAppendOnlyEvenForSchemaOwner() {
    String resourceId = recordFor(orgA);

    assertThatThrownBy(() ->
            asSchemaOwner(() ->
                jdbc.sql("UPDATE audit_events SET action = 'X' WHERE resource_id = :id")
                    .param("id", resourceId)
                    .update()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() ->
            asSchemaOwner(() ->
                jdbc.sql("DELETE FROM audit_events WHERE resource_id = :id")
                    .param("id", resourceId)
                    .update()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void organizationReadsOnlyItsOwnAuditEvents() {
    recordFor(orgA);
    recordFor(orgB);

    List<UUID> visibleOrgs =
        OrganizationContext.callAs(
            orgA,
            () ->
                jdbc.sql("SELECT DISTINCT organization_id FROM audit_events")
                    .query(UUID.class)
                    .list());

    assertThat(visibleOrgs).containsExactly(orgA);
  }

  @Test
  void cannotWriteAuditEventForAnotherOrganization() {
    assertThatThrownBy(() ->
            OrganizationContext.runAs(
                orgA,
                () ->
                    audit.record(
                        AuditEvent.of(orgB, null, "X", "Test", "1", AuditOutcome.SUCCESS))))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void auditSurvivesRollbackOfTheCallersTransaction() {
    String resourceId = UUID.randomUUID().toString();
    OrganizationContext.runAs(
        orgA,
        () ->
            new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                  audit.record(
                      AuditEvent.of(
                          orgA,
                          null,
                          "REFUND_REQUESTED",
                          "Payment",
                          resourceId,
                          AuditOutcome.DENIED));
                  status.setRollbackOnly();
                }));

    assertThat(countByResource(resourceId)).isEqualTo(1);
  }

  @Test
  void platformEventsWithoutOrganizationAreStoredButNotVisibleToOrganizations() {
    String resourceId = UUID.randomUUID().toString();
    audit.record(
        AuditEvent.of(null, null, "LOGIN_FAILED", "Session", resourceId, AuditOutcome.FAILURE));

    assertThat(countByResource(resourceId)).isEqualTo(1);
    assertThat(
            OrganizationContext.callAs(
                orgA,
                () ->
                    jdbc.sql("SELECT count(*) FROM audit_events WHERE resource_id = :id")
                        .param("id", resourceId)
                        .query(Long.class)
                        .single()))
        .isZero();
  }

  private String recordFor(UUID organizationId) {
    String resourceId = UUID.randomUUID().toString();
    OrganizationContext.runAs(
        organizationId,
        () ->
            audit.record(
                AuditEvent.of(
                    organizationId,
                    null,
                    "FACILITY_VIEWED",
                    "Facility",
                    resourceId,
                    AuditOutcome.SUCCESS)));
    return resourceId;
  }

  private long countByResource(String resourceId) {
    return asSchemaOwner(() ->
        jdbc.sql("SELECT count(*) FROM audit_events WHERE resource_id = :id")
            .param("id", resourceId)
            .query(Long.class)
            .single());
  }

  private static <T> T asSchemaOwner(java.util.function.Supplier<T> action) {
    return OrganizationContext.callAsSchemaOwner(action);
  }

  private UUID createOrganization() {
    UUID id = UUID.randomUUID();
    asSchemaOwner(() ->
        jdbc.sql(
                "INSERT INTO organizations (id, code, name, country_code, status, hosting_model)"
                    + " VALUES (:id, :code, 'Test Org (synthetic)', 'LR', 'ACTIVE', 'DEDICATED')")
            .param("id", id)
            .param("code", "ORG-" + id.toString().substring(0, 8))
            .update());
    return id;
  }
}
