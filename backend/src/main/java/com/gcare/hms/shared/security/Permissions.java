package com.gcare.hms.shared.security;

import java.util.Set;

/**
 * Permission codes checked in code. The same codes are in the {@code permissions} table (migration
 * V2); a test keeps both in step. Roles are sets of these codes, assigned per organization or per
 * facility.
 */
public final class Permissions {

  public static final String FACILITY_READ = "FACILITY_READ";
  public static final String FACILITY_MANAGE = "FACILITY_MANAGE";
  public static final String USER_READ = "USER_READ";
  public static final String USER_MANAGE = "USER_MANAGE";
  public static final String AUDIT_READ = "AUDIT_READ";
  public static final String PATIENT_READ = "PATIENT_READ";
  public static final String PATIENT_REGISTER = "PATIENT_REGISTER";
  public static final String PATIENT_UPDATE = "PATIENT_UPDATE";
  public static final String PATIENT_DUPLICATE_REVIEW = "PATIENT_DUPLICATE_REVIEW";
  public static final String PATIENT_LINK_REQUEST = "PATIENT_LINK_REQUEST";
  public static final String PATIENT_LINK_APPROVE = "PATIENT_LINK_APPROVE";
  public static final String ENCOUNTER_READ = "ENCOUNTER_READ";
  public static final String ENCOUNTER_OPEN = "ENCOUNTER_OPEN";
  public static final String ENCOUNTER_MANAGE = "ENCOUNTER_MANAGE";
  public static final String ENCOUNTER_REOPEN = "ENCOUNTER_REOPEN";
  public static final String OBSERVATION_READ = "OBSERVATION_READ";
  public static final String OBSERVATION_RECORD = "OBSERVATION_RECORD";
  public static final String CLINICAL_NOTE_READ = "CLINICAL_NOTE_READ";
  public static final String CLINICAL_NOTE_WRITE = "CLINICAL_NOTE_WRITE";
  public static final String ORDER_CATALOG_MANAGE = "ORDER_CATALOG_MANAGE";
  public static final String ORDER_READ = "ORDER_READ";
  public static final String ORDER_CREATE = "ORDER_CREATE";
  public static final String ORDER_PROCESS = "ORDER_PROCESS";
  public static final String BILLING_READ = "BILLING_READ";
  public static final String BILLING_CATALOG_MANAGE = "BILLING_CATALOG_MANAGE";
  public static final String PRICE_PROPOSE = "PRICE_PROPOSE";
  public static final String PRICE_APPROVE = "PRICE_APPROVE";
  public static final String INVOICE_READ = "INVOICE_READ";
  public static final String INVOICE_MANAGE = "INVOICE_MANAGE";
  public static final String INVOICE_VOID = "INVOICE_VOID";
  public static final String PAYMENT_METHOD_MANAGE = "PAYMENT_METHOD_MANAGE";
  public static final String PAYMENT_RECORD = "PAYMENT_RECORD";
  public static final String SHIFT_REVIEW = "SHIFT_REVIEW";
  public static final String REFUND_REQUEST = "REFUND_REQUEST";
  public static final String REFUND_APPROVE = "REFUND_APPROVE";
  public static final String EXEMPTION_REQUEST = "EXEMPTION_REQUEST";
  public static final String EXEMPTION_APPROVE = "EXEMPTION_APPROVE";
  public static final String EXEMPTION_REASON_MANAGE = "EXEMPTION_REASON_MANAGE";
  public static final String REPORT_OPERATIONS_READ = "REPORT_OPERATIONS_READ";
  public static final String REPORT_REVENUE_READ = "REPORT_REVENUE_READ";
  public static final String REPORT_EXPORT = "REPORT_EXPORT";
  public static final String CONFIG_CLINICAL_MANAGE = "CONFIG_CLINICAL_MANAGE";
  public static final String CONFIG_ORGANIZATION_MANAGE = "CONFIG_ORGANIZATION_MANAGE";
  public static final String DOWNTIME_MANAGE = "DOWNTIME_MANAGE";
  public static final String DOWNTIME_RECORD = "DOWNTIME_RECORD";
  public static final String IMAGING_ARCHIVE_ADMIN = "IMAGING_ARCHIVE_ADMIN";
  public static final String IMAGING_WORKLIST = "IMAGING_WORKLIST";
  public static final String IMAGING_LINK = "IMAGING_LINK";

  public static final Set<String> ALL =
      Set.of(
          FACILITY_READ,
          FACILITY_MANAGE,
          USER_READ,
          USER_MANAGE,
          AUDIT_READ,
          PATIENT_READ,
          PATIENT_REGISTER,
          PATIENT_UPDATE,
          PATIENT_DUPLICATE_REVIEW,
          PATIENT_LINK_REQUEST,
          PATIENT_LINK_APPROVE,
          ENCOUNTER_READ,
          ENCOUNTER_OPEN,
          ENCOUNTER_MANAGE,
          ENCOUNTER_REOPEN,
          OBSERVATION_READ,
          OBSERVATION_RECORD,
          CLINICAL_NOTE_READ,
          CLINICAL_NOTE_WRITE,
          ORDER_CATALOG_MANAGE,
          ORDER_READ,
          ORDER_CREATE,
          ORDER_PROCESS,
          BILLING_READ,
          BILLING_CATALOG_MANAGE,
          PRICE_PROPOSE,
          PRICE_APPROVE,
          INVOICE_READ,
          INVOICE_MANAGE,
          INVOICE_VOID,
          PAYMENT_METHOD_MANAGE,
          PAYMENT_RECORD,
          SHIFT_REVIEW,
          REFUND_REQUEST,
          REFUND_APPROVE,
          EXEMPTION_REQUEST,
          EXEMPTION_APPROVE,
          EXEMPTION_REASON_MANAGE,
          REPORT_OPERATIONS_READ,
          REPORT_REVENUE_READ,
          REPORT_EXPORT,
          CONFIG_CLINICAL_MANAGE,
          CONFIG_ORGANIZATION_MANAGE,
          DOWNTIME_MANAGE,
          DOWNTIME_RECORD,
          IMAGING_ARCHIVE_ADMIN,
          IMAGING_WORKLIST,
          IMAGING_LINK);

  private Permissions() {}
}
