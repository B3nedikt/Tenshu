# Tenshu iOS IPC: App Groups & POSIX Sockets — FIRST-PARTY ONLY

> ⚠️ **This is not the platform's iOS connection model.** The App Group + Unix Domain Socket
> design below works **only between apps signed under Tenshu's own Apple Team ID** (e.g. a
> first-party Control + Drive, if both ever ship on iOS). It **does not generalise to
> third-party apps**: App Groups are bound to a single development team, so a third-party
> developer's app can never join `group.org.tenshu`, and re-signing third-party binaries under
> one umbrella team to force shared membership violates Apple's Developer Program License
> Agreement and App Review Guidelines.
>
> **EPC is the *third-party* iOS connection model**, not the model for all apps. See
> [`docs/proposals/ios-proxy-architecture.md`](../proposals/ios-proxy-architecture.md) and the
> whitepaper's *External Proxy Connection* section.

> ⚠⚠ **STATUS 2026-09-07 — this design was never built, and the case it was written for is
> now served differently.** The document says it should be retired if first-party
> co-residence never ships. Co-residence *did* ship — as **route 1**
> ([`ios-first-party-route-1-migration.md`](../proposals/ios-first-party-route-1-migration.md),
> Decision settled 2026-07-18): every first-party iOS app runs an **in-process** proxy runtime
> over a shared App Group replica directory, with no IPC hop at all. No `iosMain` Unix-socket
> transport exists in the repo and none is planned. Read this only as background on why an
> App Group UDS was considered; read route 1 §8–§9 for what first-party iOS actually does.

This document outlines an **optional, first-party-only** Inter-Process Communication (IPC)
shortcut between Tenshu applications that Tenshu itself signs on iOS. It is a latency
optimisation for that narrow case, not the cross-app substrate.

## 1. The Challenge: Sandboxing

Unlike macOS or Android, iOS apps are strictly sandboxed. App A cannot see the files or memory of
App B. Apple's only sanctioned shared-container mechanism, **App Groups**, is scoped to a single
development team — which is why this design covers same-team (first-party) apps only. Cross-vendor
communication on iOS must instead use EPC over a network channel (see the linked architecture doc).

## 2. Architecture (first-party apps only)

### Shared Container (App Groups)

For apps under the same Tenshu Team ID, we can use **App Groups** (`group.org.tenshu`) to create a
shared space on the filesystem that both the **Control** and **Drive** apps can access.

* **Socket Path**: The Unix Domain Socket file will live in this shared container:
  `Library/Group Containers/group.org.tenshu/ipc.sock`

### Native POSIX Transport

Since iOS is a **Kotlin/Native** target (not JVM), we cannot use `java.nio`.

* **Implementation**: We implement `TaskTransport` in `iosMain` using the native C `platform.posix`
  APIs.
* **Mechanism**:
    * `socket(AF_UNIX, SOCK_STREAM, 0)` to create the pipe.
    * `connect()` or `bind()`/`listen()` using the path from the App Group container.
* **Framing**: We reuse the same 4-byte length-prefix framing protocol used on Desktop to ensure
  binary compatibility.

## 3. Lifecycle & Background Strategy

iOS will suspend background apps, which kills active socket connections.

1. **Active Sync**: When both apps are foregrounded (e.g., during a setup phase), they use the
   high-speed POSIX socket.
2. **Passive / suspended state**: The earlier idea here — Drive reading shared session metadata and
   keys directly from a binary file in the App Group when Control is suspended — is **superseded**.
   The platform answer to background suspension is the relay acting as a rendezvous + wake fabric,
   not a co-resident proxy holding a live socket. See
   [`ios-proxy-architecture.md`](../proposals/ios-proxy-architecture.md) §3. A first-party
   foreground-only socket fast-path (tracked there as open question O1) is the most this document's
   mechanism should aspire to.

## 4. Security

* **Access Control**: Only apps signed with the same Team ID and belonging to the same App Group can
  access the socket file — which is exactly why this is first-party only.
* **Encryption**: All data sent over the socket is already encrypted via the Tenshu protocol,
  providing double protection (FS-level + Protocol-level).

## 5. Implementation Plan

Only pursue this if a first-party co-resident Control + Drive on iOS is actually shipped; otherwise
the EPC path in `ios-proxy-architecture.md` is the work.

1. **Multiplatform Path Utility**: Create a common utility to resolve the IPC path (Home dir on
   Desktop vs. App Group on iOS).
2. **iosMain actual**: Implement the `UnixSocketTaskTransport` using POSIX C interop.
3. **App Group Setup**: Configure the iOS project entitlements for both first-party apps.
