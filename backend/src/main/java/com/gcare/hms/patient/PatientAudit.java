package com.gcare.hms.patient;

import com.gcare.hms.audit.AuditEvent;
import com.gcare.hms.audit.AuditOutcome;
import com.gcare.hms.audit.AuditService;
import com.gcare.hms.shared.security.CurrentUser;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Audit events for patient records, shared by registration and linking. */
@Component
class PatientAudit {

  private final AuditService audit;

  PatientAudit(AuditService audit) {
    this.audit = audit;
  }

  /**
   * Records the event only once the transaction has committed, so a rolled-back change is never
   * audited as done. Metadata never contains names, reasons or other patient values.
   */
  void afterCommit(
      CurrentUser user,
      UUID facilityId,
      String action,
      UUID patientId,
      Map<String, String> metadata) {
    AuditEvent event =
        new AuditEvent(
            user.organizationId(),
            facilityId,
            user.userId(),
            action,
            "Patient",
            patientId != null ? patientId.toString() : null,
            AuditOutcome.SUCCESS,
            null,
            Instant.now(),
            null,
            metadata);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              audit.record(event);
            }
          });
    } else {
      audit.record(event);
    }
  }
}
