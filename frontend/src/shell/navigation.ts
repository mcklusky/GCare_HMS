import { IconBuildingHospital, IconHome } from '@tabler/icons-react'
import { Permission } from '../api/types'

export interface NavItem {
  label: string
  to: string
  icon: typeof IconHome
  /** Shown only to users holding this permission. Hiding is for usability; the API enforces. */
  permission?: string
}

/** Main navigation. Add items as features arrive; keep the permission in step with the API. */
export const NAV_ITEMS: NavItem[] = [
  { label: 'Home', to: '/', icon: IconHome },
  { label: 'Facilities', to: '/facilities', icon: IconBuildingHospital, permission: Permission.FACILITY_READ },
]

export function visibleNavItems(permissions: readonly string[]): NavItem[] {
  return NAV_ITEMS.filter((item) => !item.permission || permissions.includes(item.permission))
}
