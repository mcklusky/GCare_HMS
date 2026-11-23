import { Badge } from '@mantine/core'
import { IconCircleCheck, IconCircleMinus } from '@tabler/icons-react'
import type { UnitStatus } from '../api/types'

const LOOK: Record<UnitStatus, { label: string; color: string; icon: typeof IconCircleCheck }> = {
  ACTIVE: { label: 'Active', color: 'teal', icon: IconCircleCheck },
  INACTIVE: { label: 'Inactive', color: 'gray', icon: IconCircleMinus },
}

/** Status with text and icon, never colour alone (02-UI-UX). */
export function StatusBadge({ status }: { status: UnitStatus }) {
  const look = LOOK[status]
  const Icon = look.icon
  return (
    <Badge variant="light" color={look.color} leftSection={<Icon size={12} aria-hidden />}>
      {look.label}
    </Badge>
  )
}
