/**
 * Hand-over from the header's quick patient search to the patient search page. Kept in memory only:
 * patient numbers never go into the URL, history state or browser storage (CLAUDE.md).
 */
let pending: string | null = null

export function setQuickSearch(patientNumber: string) {
  pending = patientNumber
}

/** The number typed in the header, once; later calls return null. */
export function takeQuickSearch(): string | null {
  const value = pending
  pending = null
  return value
}
