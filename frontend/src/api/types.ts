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
} as const

export type PermissionCode = (typeof Permission)[keyof typeof Permission]
