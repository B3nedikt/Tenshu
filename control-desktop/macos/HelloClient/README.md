# HelloClient — macOS native-bridge de-risk artifact

Phase 0 task 4 of [`desktop-macos-integration.md`](../../../docs/proposals/desktop-macos-integration.md).
The smallest possible native client that connects to the Control daemon's
group-container Unix socket and round-trips the JSON bridge envelope. If it
prints successful replies, the reachability + protocol assumptions hold.

## Build & run

```bash
swiftc -O -o hello-client main.swift

# 1. start the daemon (dev, unsigned — resolves ~/.tenshu/bridge.sock):
./gradlew :control-desktop:run        # from repo root

# 2. round-trip:
./hello-client                        # dev socket (~/.tenshu/bridge.sock)
./hello-client /path/to/bridge.sock   # explicit path
```

Socket resolution mirrors `core-common`'s `SocketPathResolver`:
`argv[1]` → `$TENSHU_TEAM_ID` (group container) → `$TENSHU_IPC_DIR` → `~/.tenshu`.

## Status — proven (2026-06-02)

The Swift client round-trips against the real Kotlin `NativeBridgeServer` over a
live `AF_UNIX` socket. Observed:

```
ping         → {"v":1,"id":"1","result":{"pong":true,"v":1,"message":"hello from swift"}}
getSyncState → {"v":1,"id":"2","result":{"state":"ACTIVE","enabled":true,"lastSyncTimestamp":123}}
```

**Still unproven (deferred — needs an Apple signing identity):** the same
round-trip from a *sandboxed* client against a *signed* `Control.app` binding in
the App Group container. That is Phase 0 task 3's verification — see the codesign
scaffold in `control-desktop/build.gradle.kts` and `entitlements/`.
