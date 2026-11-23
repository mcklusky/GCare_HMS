package com.gcare.hms.identity;

import static com.gcare.hms.shared.security.Permissions.AUDIT_READ;
import static com.gcare.hms.shared.security.Permissions.ENCOUNTER_MANAGE;
import static com.gcare.hms.shared.security.Permissions.ENCOUNTER_OPEN;
import static com.gcare.hms.shared.security.Permissions.ENCOUNTER_READ;
import static com.gcare.hms.shared.security.Permissions.ENCOUNTER_REOPEN;
import static com.gcare.hms.shared.security.Permissions.FACILITY_MANAGE;
import static com.gcare.hms.shared.security.Permissions.FACILITY_READ;
import static com.gcare.hms.shared.security.Permissions.OBSERVATION_READ;
import static com.gcare.hms.shared.security.Permissions.OBSERVATION_RECORD;
import static com.gcare.hms.shared.security.Permissions.PATIENT_DUPLICATE_REVIEW;
import static com.gcare.hms.shared.security.Permissions.PATIENT_LINK_APPROVE;
import static com.gcare.hms.shared.security.Permissions.PATIENT_LINK_REQUEST;
import static com.gcare.hms.shared.security.Permissions.PATIENT_READ;
import static com.gcare.hms.shared.security.Permissions.PATIENT_REGISTER;
import static com.gcare.hms.shared.security.Permissions.PATIENT_UPDATE;
import static com.gcare.hms.shared.security.Permissions.USER_MANAGE;
import static com.gcare.hms.shared.security.Permissions.USER_READ;

import java.util.Set;

/**
 * Roles the platform creates in every organization (docs/05-API.md, minimum authorization matrix).
 * Only administrative and registration roles exist so far: clinical, billing and other roles come
 * with their features and the signed-off permission matrix
 * (docs/project-record/permission-matrix.md).
 */
public enum SystemRole {
  /** Scoped users and configuration; no implicit clinical access. */
  ORGANIZATION_ADMIN(
      "Organization administrator",
      "Manages facilities, departments and user accounts. No clinical access.",
      Set.of(FACILITY_READ, FACILITY_MANAGE, USER_READ, USER_MANAGE)),

  /** Reads the audit trail; no mutation. */
  AUDITOR("Auditor", "Reads the audit trail. Cannot change anything.", Set.of(AUDIT_READ)),

  /**
   * Patient demographics at the front desk (docs/05-API.md matrix: "Registration"). PROVISIONAL
   * until the permission matrix is signed off; duplicate review and linking come with task 2.2/2.3.
   */
  REGISTRATION_CLERK(
      "Registration clerk",
      "Searches, registers and corrects patients and opens visits at assigned facilities. No"
          + " clinical access.",
      Set.of(PATIENT_READ, PATIENT_REGISTER, PATIENT_UPDATE, ENCOUNTER_READ, ENCOUNTER_OPEN)),

  /**
   * Registration clerk plus the duplicate review queue and link requests. PROVISIONAL, like the
   * clerk role.
   */
  REGISTRATION_SUPERVISOR(
      "Registration supervisor",
      "Registration clerk duties plus reviewing possible duplicates and requesting links. No"
          + " clinical access.",
      Set.of(
          PATIENT_READ,
          PATIENT_REGISTER,
          PATIENT_UPDATE,
          PATIENT_DUPLICATE_REVIEW,
          PATIENT_LINK_REQUEST,
          ENCOUNTER_READ,
          ENCOUNTER_OPEN)),

  /**
   * Second person for patient identity decisions: reviews duplicates, requests and approves links
   * (never their own request; docs/17 §5). PROVISIONAL until the permission matrix is signed off.
   */
  HEALTH_RECORDS_MANAGER(
      "Health records manager",
      "Reviews possible duplicates and approves, rejects or reverses patient record links. No"
          + " clinical access.",
      Set.of(PATIENT_READ, PATIENT_DUPLICATE_REVIEW, PATIENT_LINK_REQUEST, PATIENT_LINK_APPROVE)),

  /**
   * Records triage observations for patients in the queue (docs/18). PROVISIONAL until the
   * permission matrix is signed off.
   */
  TRIAGE_NURSE(
      "Triage nurse",
      "Sees department queues and records triage observations.",
      Set.of(PATIENT_READ, ENCOUNTER_READ, OBSERVATION_READ, OBSERVATION_RECORD)),

  /**
   * Calls, transfers, closes and reopens visits and records observations (docs/18). PROVISIONAL
   * until the permission matrix is signed off.
   */
  CLINICIAN(
      "Clinician",
      "Sees patients from the department queue: calls, transfers, closes and reopens visits.",
      Set.of(
          PATIENT_READ,
          ENCOUNTER_READ,
          ENCOUNTER_MANAGE,
          ENCOUNTER_REOPEN,
          OBSERVATION_READ,
          OBSERVATION_RECORD));

  private final String displayName;
  private final String description;
  private final Set<String> permissions;

  SystemRole(String displayName, String description, Set<String> permissions) {
    this.displayName = displayName;
    this.description = description;
    this.permissions = permissions;
  }

  public String displayName() {
    return displayName;
  }

  public String description() {
    return description;
  }

  public Set<String> permissions() {
    return permissions;
  }
}
