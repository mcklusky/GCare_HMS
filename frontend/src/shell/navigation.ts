import {
  IconBuildingHospital,
  IconCash,
  IconChartBar,
  IconChecklist,
  IconCashRegister,
  IconCreditCard,
  IconDiscount,
  IconPlugConnectedX,
  IconPhoto,
  IconRadioactive,
  IconLink,
  IconFileText,
  IconAlertOctagon,
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

export const NAV_SECTIONS = [
  'Overview',
  'Patients & visits',
  'Diagnostics',
  'Billing',
  'Operations',
  'Administration',
] as const
export type NavSection = (typeof NAV_SECTIONS)[number]

export interface NavItem {
  label: string
  to: string
  icon: typeof IconHome
  /** Sidebar group. */
  section: NavSection
  /** Shown only to users holding this permission (or any of a list). Hiding is for usability; the API enforces. */
  permission?: string | readonly string[]
}

/** Main navigation. Add items as features arrive; keep the permission in step with the API. */
export const NAV_ITEMS: NavItem[] = [
  { label: 'Home', to: '/', section: 'Overview', icon: IconHome },
  {
    label: 'Patients',
    to: '/patients',
    section: 'Patients & visits',
    icon: IconUsers,
    permission: Permission.PATIENT_READ,
  },
  {
    label: 'Duplicate review',
    to: '/patients/duplicates',
    section: 'Patients & visits',
    icon: IconCopy,
    permission: Permission.PATIENT_DUPLICATE_REVIEW,
  },
  {
    label: 'My workspace',
    to: '/workspace',
    section: 'Patients & visits',
    icon: IconLayoutDashboard,
    permission: Permission.ENCOUNTER_MANAGE,
  },
  {
    label: 'Queue',
    to: '/queue',
    section: 'Patients & visits',
    icon: IconStethoscope,
    permission: Permission.ENCOUNTER_READ,
  },
  {
    label: 'Imaging worklist',
    to: '/imaging',
    section: 'Diagnostics',
    icon: IconRadioactive,
    permission: Permission.IMAGING_WORKLIST,
  },
  {
    label: 'Studies to review',
    to: '/imaging/studies',
    section: 'Diagnostics',
    icon: IconLink,
    permission: Permission.IMAGING_LINK,
  },
  {
    label: 'Reporting',
    to: '/radiology/reporting',
    section: 'Diagnostics',
    icon: IconFileText,
    permission: Permission.RADIOLOGY_REPORT_WRITE,
  },
  {
    label: 'Critical results',
    to: '/critical-results',
    section: 'Diagnostics',
    icon: IconAlertOctagon,
    permission: [Permission.CRITICAL_RESULT_ACKNOWLEDGE, Permission.CRITICAL_RESULT_OVERSIGHT],
  },
  {
    label: 'Worklist',
    to: '/orders',
    section: 'Patients & visits',
    icon: IconClipboardList,
    permission: Permission.ORDER_PROCESS,
  },
  {
    label: 'Invoices',
    to: '/billing/invoices',
    section: 'Billing',
    icon: IconFileInvoice,
    permission: Permission.INVOICE_READ,
  },
  {
    label: 'My shift',
    to: '/billing/shift',
    section: 'Billing',
    icon: IconCashRegister,
    permission: Permission.PAYMENT_RECORD,
  },
  {
    label: 'Shift sign-off',
    to: '/billing/shift-reviews',
    section: 'Billing',
    icon: IconReportMoney,
    permission: Permission.SHIFT_REVIEW,
  },
  {
    label: 'Refund & exemption approvals',
    to: '/billing/adjustment-approvals',
    section: 'Billing',
    icon: IconReceiptRefund,
    permission: [Permission.REFUND_APPROVE, Permission.EXEMPTION_APPROVE],
  },
  { label: 'Tariffs', to: '/billing/tariffs', section: 'Billing', icon: IconCash, permission: Permission.BILLING_READ },
  {
    label: 'Price approvals',
    to: '/billing/approvals',
    section: 'Billing',
    icon: IconChecklist,
    permission: Permission.PRICE_APPROVE,
  },
  {
    label: 'Reports',
    to: '/reports',
    section: 'Operations',
    icon: IconChartBar,
    permission: [Permission.REPORT_OPERATIONS_READ, Permission.REPORT_REVENUE_READ],
  },
  {
    label: 'Downtime',
    to: '/downtime',
    section: 'Operations',
    icon: IconPlugConnectedX,
    permission: [Permission.DOWNTIME_MANAGE, Permission.DOWNTIME_RECORD],
  },
  {
    label: 'Facilities',
    to: '/facilities',
    section: 'Administration',
    icon: IconBuildingHospital,
    permission: Permission.FACILITY_READ,
  },
  {
    label: 'Order catalog',
    to: '/admin/order-catalog',
    section: 'Administration',
    icon: IconListDetails,
    permission: Permission.ORDER_CATALOG_MANAGE,
  },
  {
    label: 'Payment methods',
    to: '/admin/payment-methods',
    section: 'Administration',
    icon: IconCreditCard,
    permission: Permission.PAYMENT_METHOD_MANAGE,
  },
  {
    label: 'Settings',
    to: '/admin/settings',
    section: 'Administration',
    icon: IconSettings,
    permission: [Permission.CONFIG_CLINICAL_MANAGE, Permission.CONFIG_ORGANIZATION_MANAGE],
  },
  {
    label: 'Imaging archive',
    to: '/admin/imaging-archive',
    section: 'Administration',
    icon: IconPhoto,
    permission: Permission.IMAGING_ARCHIVE_ADMIN,
  },
  {
    label: 'Exemption reasons',
    to: '/admin/exemption-reasons',
    section: 'Administration',
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

/** Visible items grouped by sidebar section, in section order; empty sections are left out. */
export function visibleNavSections(permissions: readonly string[]): { section: NavSection; items: NavItem[] }[] {
  const items = visibleNavItems(permissions)
  return NAV_SECTIONS.map((section) => ({ section, items: items.filter((i) => i.section === section) })).filter(
    (g) => g.items.length > 0,
  )
}

/** Where a path sits in the navigation (for the breadcrumb and page eyebrow); null when it is not a nav page. */
export function navTrail(pathname: string): { section: NavSection; label: string } | null {
  let best: NavItem | null = null
  for (const item of NAV_ITEMS) {
    const match = item.to === '/' ? pathname === '/' : pathname === item.to || pathname.startsWith(item.to + '/')
    if (match && (!best || item.to.length > best.to.length)) best = item
  }
  return best && { section: best.section, label: best.label }
}
