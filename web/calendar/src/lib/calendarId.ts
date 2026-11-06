// The id `calendar.save` derives for a NEW calendar: `"cal-" + name.trim().lowercase().hashCode()`
// as an unsigned hex (`Calendar.saveCalendar`, mirroring native so two devices creating "Work"
// offline converge on one row).
//
// ⚠⚠ Why the app computes it too: a create whose derived id already exists is an UPSERT on that
// row — creating "Work" next to an existing "Work" (or next to a calendar first created as "Work"
// and since renamed) would silently rename and recolour it. The dialog refuses that case instead.
export function derivedCalendarId(name: string): string {
  // Kotlin's `lowercase()` is locale-invariant, like `toLowerCase()` here; `String.hashCode` is
  // Java's 31-polynomial over UTF-16 code units.
  const s = name.trim().toLowerCase()
  let h = 0
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0
  return `cal-${(h >>> 0).toString(16)}`
}
