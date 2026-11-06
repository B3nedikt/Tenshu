// Device-fingerprint formatting helpers. The fingerprint is derived
// from the device's Ed25519 public key (32 bytes); we surface the first
// 8 bytes as 4 colon-separated hex pairs — short enough to be visually
// scannable, long enough to disambiguate against typo-realistic
// collisions. Matches the convention from `:control-shared`'s device
// rendering on Android/desktop so users see consistent fingerprints
// across surfaces.

export function formatFingerprint(publicKey: Int8Array | null | undefined): string {
  if (publicKey === null || publicKey === undefined || publicKey.byteLength === 0) {
    return '—'
  }
  const length = Math.min(8, publicKey.byteLength)
  let out = ''
  for (let i = 0; i < length; i++) {
    const byte = (publicKey[i] ?? 0) & 0xff
    if (i > 0 && i % 2 === 0) out += ':'
    out += byte.toString(16).padStart(2, '0')
  }
  return out
}

// The roster label: the user-chosen name, then the device's self-published
// hardware model when it is known and says something the name does not.
// ⚠ Mirrors `deviceDisplayLabel` in `:identity-service` (DeviceHardware.kt),
// which the Android/desktop/iOS rosters use, so every surface renders the
// same pair the same way. Change both or neither.
export function formatDeviceLabel(name: string, hardwareName: string | null | undefined): string {
  const hardware = (hardwareName ?? '').trim()
  const label = name.trim()
  if (hardware === '' || hardware.toLowerCase() === label.toLowerCase()) return label
  if (label === '') return hardware
  return `${label} · ${hardware}`
}
