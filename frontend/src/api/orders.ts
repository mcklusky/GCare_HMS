import { apiFetch } from './client'
import type { DepartmentRef, UserRef } from './encounters'
import type { Page, PatientSummary } from './patients'

/** Order catalog, orders and referrals (backend orders module; docs/18 §8). */

export type Category = 'LAB' | 'IMAGING' | 'PROCEDURE' | 'REFERRAL'
export type Priority = 'ROUTINE' | 'URGENT'
export type OrderStatus = 'REQUESTED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'

export interface CatalogItem {
  id: string
  code: string
  name: string
  category: Category
  performing_department: DepartmentRef | null
  facility_id: string | null
  status: 'ACTIVE' | 'INACTIVE'
  version: number
}

export interface OrderEvent {
  event_type: 'REQUESTED' | 'STARTED' | 'COMPLETED' | 'CANCELLED'
  from_status: OrderStatus | null
  to_status: OrderStatus
  reason: string | null
  actor: UserRef
  occurred_at: string
}

export interface Order {
  id: string
  encounter_id: string
  facility_id: string
  patient: PatientSummary
  item: { id: string; code: string; name: string; category: Category }
  performing_department: DepartmentRef | null
  priority: Priority
  status: OrderStatus
  indication: string | null
  requested_by: UserRef
  requested_at: string
  updated_at: string
  cancel_reason: string | null
  events: OrderEvent[]
  version: number
}

const enc = encodeURIComponent

export const listCatalog = (includeInactive = false, signal?: AbortSignal) =>
  apiFetch<CatalogItem[]>(`/api/v1/order-catalog${includeInactive ? '?include_inactive=true' : ''}`, { signal })

export const createCatalogItem = (body: {
  code: string
  name: string
  category: Category
  performing_department_id: string | null
}) => apiFetch<CatalogItem>('/api/v1/order-catalog', { method: 'POST', body })

export const updateCatalogItem = (
  item: CatalogItem,
  body: { name: string; performing_department_id: string | null; status: CatalogItem['status'] },
) =>
  apiFetch<CatalogItem>(`/api/v1/order-catalog/${enc(item.id)}`, {
    method: 'PUT',
    body: { version: item.version, ...body },
  })

export const listVisitOrders = (encounterId: string, signal?: AbortSignal) =>
  apiFetch<Order[]>(`/api/v1/encounters/${enc(encounterId)}/orders`, { signal })

export const placeOrder = (
  encounterId: string,
  body: { catalog_item_id: string; priority: Priority; indication?: string },
) => apiFetch<Order>(`/api/v1/encounters/${enc(encounterId)}/orders`, { method: 'POST', body })

export const listWorklist = (facilityId: string, signal?: AbortSignal) =>
  apiFetch<Page<Order>>(`/api/v1/orders?facility_id=${enc(facilityId)}&size=100`, { signal })

export const orderAction = (order: Order, action: 'start' | 'complete' | 'cancel', reason?: string) =>
  apiFetch<Order>(`/api/v1/orders/${enc(order.id)}/${action}`, {
    method: 'POST',
    body: reason === undefined ? { version: order.version } : { version: order.version, reason },
  })

export const CATEGORY_LABEL: Record<Category, string> = {
  LAB: 'Laboratory',
  IMAGING: 'Imaging',
  PROCEDURE: 'Procedure',
  REFERRAL: 'Referral',
}

export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  REQUESTED: 'Requested',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
}

export const PRIORITY_LABEL: Record<Priority, string> = { ROUTINE: 'Routine', URGENT: 'Urgent' }
