/**
 * Times are stored as UTC instants and shown and entered in the time zone of the facility the user
 * works in, not the workstation's (follow-up decision 2026-11-23; docs/23). FacilityProvider sets the
 * zone; before that (sign-in screens) the workstation zone is used.
 */
let zone: string | undefined

export function setDisplayZone(timeZone: string | undefined) {
  zone = timeZone || undefined
}

export function displayZone(): string | undefined {
  return zone
}

export const formatDateTime = (iso: string | number | Date) =>
  new Date(iso).toLocaleString(undefined, { timeZone: zone })

export const formatDate = (iso: string | number | Date) =>
  new Date(iso).toLocaleDateString(undefined, { timeZone: zone })

export const formatTime = (iso: string | number | Date, options: Intl.DateTimeFormatOptions = {}) =>
  new Date(iso).toLocaleTimeString([], { ...options, timeZone: zone })

/** Offset of `timeZone` from UTC at the given instant, in milliseconds. */
function offsetAt(ms: number, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(new Date(ms))
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value)
  return Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'), get('second')) - ms
}

/**
 * A datetime-local input value ("2026-11-23T10:04"), read as a wall-clock time in the facility's zone,
 * as an ISO instant. Without a zone it falls back to the workstation's.
 */
export function zonedLocalToIso(value: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value)
  if (!zone || !m) return new Date(value).toISOString()
  const [, y, mo, d, h, mi, s] = m
  const wall = Date.UTC(+y, +mo - 1, +d, +h, +mi, s ? +s : 0)
  // Two passes settle the offset around daylight-saving changes.
  let ms = wall - offsetAt(wall, zone)
  ms = wall - offsetAt(ms, zone)
  return new Date(ms).toISOString()
}

/** A short note for datetime inputs, e.g. "Facility time (Africa/Monrovia)". */
export function zoneNote(): string {
  return zone ? `Facility time (${zone})` : 'Local time of this computer'
}
