import { afterEach, describe, expect, it } from 'vitest'
import { formatDateTime, setDisplayZone, zonedLocalToIso, zoneNote } from './time'

describe('facility time', () => {
  afterEach(() => setDisplayZone(undefined))

  it('reads a typed wall-clock time in the facility zone, whatever the workstation zone', () => {
    setDisplayZone('Africa/Monrovia') // UTC+0
    expect(zonedLocalToIso('2026-11-23T10:04')).toBe('2026-11-23T10:04:00.000Z')
    setDisplayZone('Africa/Nairobi') // UTC+3
    expect(zonedLocalToIso('2026-11-23T10:04')).toBe('2026-11-23T07:04:00.000Z')
  })

  it('handles daylight saving time', () => {
    setDisplayZone('America/New_York')
    expect(zonedLocalToIso('2026-07-01T10:00')).toBe('2026-07-01T14:00:00.000Z') // EDT, UTC-4
    expect(zonedLocalToIso('2026-12-01T10:00')).toBe('2026-12-01T15:00:00.000Z') // EST, UTC-5
  })

  it('shows instants in the facility zone and says which zone inputs use', () => {
    setDisplayZone('Africa/Nairobi')
    const iso = '2026-11-23T07:04:00Z'
    expect(formatDateTime(iso)).toBe(new Date(iso).toLocaleString(undefined, { timeZone: 'Africa/Nairobi' }))
    expect(zoneNote()).toBe('Facility time (Africa/Nairobi)')
    setDisplayZone(undefined)
    expect(zoneNote()).toBe('Local time of this computer')
  })
})
