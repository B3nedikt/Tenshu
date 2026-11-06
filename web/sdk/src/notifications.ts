// Notifications entry point: per-realm notification types + the
// stream-shaped observe operation. Backed by `:notification-service`
// on the Kotlin side.

import * as bundle from './_bundle'

export interface Notification {
  readonly uuid: string
  // Epoch milliseconds (the canonical `UnixInstant` wire unit per
  // `core/.../time/Instant.kt`). `new Date(notification.timestamp)` is the
  // natural way to consume it. See tenshu-sdk-js/.../internal/CompositeSnapshot.kt
  // for the unwrap rule.
  readonly timestamp: number
}

export const Notifications = bundle.Notifications.getInstance()
