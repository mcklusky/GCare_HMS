/** Response shapes of /api/v1 (see the backend OpenAPI document at /v3/api-docs). */

export type UnitStatus = 'ACTIVE' | 'INACTIVE'

export interface Facility {
  id: string
  code: string
  name: string
  timezone: string
  status: UnitStatus
}

export interface Department {
  id: string
  facility_id: string
  code: string
  name: string
  status: UnitStatus
}

export interface OrganizationSummary {
  id: string
  code: string
  name: string
}

export interface Me {
  id: string
  username: string
  display_name: string
  organization: OrganizationSummary
  permissions: string[]
  facilities: Facility[]
}

export interface LogoutResponse {
  logout_url: string | null
}

/** Permission codes (backend shared/security/Permissions.java). */
export const Permission = {
  FACILITY_READ: 'FACILITY_READ',
  FACILITY_MANAGE: 'FACILITY_MANAGE',
  USER_READ: 'USER_READ',
  USER_MANAGE: 'USER_MANAGE',
  AUDIT_READ: 'AUDIT_READ',
  PATIENT_READ: 'PATIENT_READ',
  PATIENT_REGISTER: 'PATIENT_REGISTER',
  PATIENT_UPDATE: 'PATIENT_UPDATE',
  PATIENT_DUPLICATE_REVIEW: 'PATIENT_DUPLICATE_REVIEW',
  PATIENT_LINK_REQUEST: 'PATIENT_LINK_REQUEST',
  PATIENT_LINK_APPROVE: 'PATIENT_LINK_APPROVE',
  ENCOUNTER_READ: 'ENCOUNTER_READ',
  ENCOUNTER_OPEN: 'ENCOUNTER_OPEN',
  ENCOUNTER_MANAGE: 'ENCOUNTER_MANAGE',
  ENCOUNTER_REOPEN: 'ENCOUNTER_REOPEN',
  OBSERVATION_READ: 'OBSERVATION_READ',
  OBSERVATION_RECORD: 'OBSERVATION_RECORD',
  CLINICAL_NOTE_READ: 'CLINICAL_NOTE_READ',
  CLINICAL_NOTE_WRITE: 'CLINICAL_NOTE_WRITE',
  ORDER_CATALOG_MANAGE: 'ORDER_CATALOG_MANAGE',
  ORDER_READ: 'ORDER_READ',
  ORDER_CREATE: 'ORDER_CREATE',
  ORDER_PROCESS: 'ORDER_PROCESS',
  BILLING_READ: 'BILLING_READ',
  BILLING_CATALOG_MANAGE: 'BILLING_CATALOG_MANAGE',
  PRICE_PROPOSE: 'PRICE_PROPOSE',
  PRICE_APPROVE: 'PRICE_APPROVE',
  INVOICE_READ: 'INVOICE_READ',
  INVOICE_MANAGE: 'INVOICE_MANAGE',
  INVOICE_VOID: 'INVOICE_VOID',
  PAYMENT_METHOD_MANAGE: 'PAYMENT_METHOD_MANAGE',
  PAYMENT_RECORD: 'PAYMENT_RECORD',
  SHIFT_REVIEW: 'SHIFT_REVIEW',
  REFUND_REQUEST: 'REFUND_REQUEST',
  REFUND_APPROVE: 'REFUND_APPROVE',
  EXEMPTION_REQUEST: 'EXEMPTION_REQUEST',
  EXEMPTION_APPROVE: 'EXEMPTION_APPROVE',
  EXEMPTION_REASON_MANAGE: 'EXEMPTION_REASON_MANAGE',
  REPORT_OPERATIONS_READ: 'REPORT_OPERATIONS_READ',
  REPORT_REVENUE_READ: 'REPORT_REVENUE_READ',
  REPORT_EXPORT: 'REPORT_EXPORT',
} as const

export type PermissionCode = (typeof Permission)[keyof typeof Permission]
