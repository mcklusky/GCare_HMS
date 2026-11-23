import {
  IconBuildingHospital,
  IconCash,
  IconChartBar,
  IconChecklist,
  IconCashRegister,
  IconCreditCard,
  IconDiscount,
  IconReceiptRefund,
  IconReportMoney,
  IconSettings,
  IconClipboardList,
  IconCopy,
  IconFileInvoice,
  IconHome,
  IconLayoutDashboard,
  IconListDetails,
  IconStethoscope,
  IconUsers,
} from '@tabler/icons-react'
import { Permission } from '../api/types'

export interface NavItem {
  label: string
  to: string
  icon: typeof IconHome
  /** Shown only to users holding this permission (or any of a list). Hiding is for usability; the API enforces. */
  permission?: string | readonly string[]
}

/** Main navigation. Add items as features arrive; keep the permission in step with the API. */
export const NAV_ITEMS: NavItem[] = [
  { label: 'Home', to: '/', icon: IconHome },
  { label: 'Patients', to: '/patients', icon: IconUsers, permission: Permission.PATIENT_READ },
  {
    label: 'Duplicate review',
    to: '/patients/duplicates',
    icon: IconCopy,
    permission: Permission.PATIENT_DUPLICATE_REVIEW,
  },
  { label: 'My workspace', to: '/workspace', icon: IconLayoutDashboard, permission: Permission.ENCOUNTER_MANAGE },
  { label: 'Queue', to: '/queue', icon: IconStethoscope, permission: Permission.ENCOUNTER_READ },
  { label: 'Worklist', to: '/orders', icon: IconClipboardList, permission: Permission.ORDER_PROCESS },
  { label: 'Invoices', to: '/billing/invoices', icon: IconFileInvoice, permission: Permission.INVOICE_READ },
  { label: 'My shift', to: '/billing/shift', icon: IconCashRegister, permission: Permission.PAYMENT_RECORD },
  {
    label: 'Shift sign-off',
    to: '/billing/shift-reviews',
    icon: IconReportMoney,
    permission: Permission.SHIFT_REVIEW,
  },
  {
    label: 'Refund & exemption approvals',
    to: '/billing/adjustment-approvals',
    icon: IconReceiptRefund,
    permission: [Permission.REFUND_APPROVE, Permission.EXEMPTION_APPROVE],
  },
  { label: 'Tariffs', to: '/billing/tariffs', icon: IconCash, permission: Permission.BILLING_READ },
  { label: 'Price approvals', to: '/billing/approvals', icon: IconChecklist, permission: Permission.PRICE_APPROVE },
  {
    label: 'Reports',
    to: '/reports',
    icon: IconChartBar,
    permission: [Permission.REPORT_OPERATIONS_READ, Permission.REPORT_REVENUE_READ],
  },
  { label: 'Facilities', to: '/facilities', icon: IconBuildingHospital, permission: Permission.FACILITY_READ },
  { label: 'Order catalog', to: '/admin/order-catalog', icon: IconListDetails, permission: Permission.ORDER_CATALOG_MANAGE },
  {
    label: 'Payment methods',
    to: '/admin/payment-methods',
    icon: IconCreditCard,
    permission: Permission.PAYMENT_METHOD_MANAGE,
  },
  {
    label: 'Settings',
    to: '/admin/settings',
    icon: IconSettings,
    permission: [Permission.CONFIG_CLINICAL_MANAGE, Permission.CONFIG_ORGANIZATION_MANAGE],
  },
  {
    label: 'Exemption reasons',
    to: '/admin/exemption-reasons',
    icon: IconDiscount,
    permission: Permission.EXEMPTION_REASON_MANAGE,
  },
]

export function visibleNavItems(permissions: readonly string[]): NavItem[] {
  return NAV_ITEMS.filter(
    (item) =>
      !item.permission ||
      (typeof item.permission === 'string' ? [item.permission] : item.permission).some((p) => permissions.includes(p)),
  )
}
