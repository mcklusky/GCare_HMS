import { describe, expect, it } from 'vitest'
import { navTrail } from './navigation'

describe('navTrail', () => {
  it('names the home page under Overview', () => {
    expect(navTrail('/')).toEqual({ section: 'Overview', label: 'Home' })
  })

  it('matches sub-pages to the longest navigation prefix', () => {
    expect(navTrail('/patients')?.label).toBe('Patients')
    expect(navTrail('/patients/123')?.label).toBe('Patients')
  })

  it('does not match partial path segments or unknown pages', () => {
    expect(navTrail('/patientsx')).toBeNull()
    expect(navTrail('/nowhere')).toBeNull()
  })
})
