// Devices grouped by room: each room `home.devices` names, in its order (device order), then the
// devices with no room under "Unassigned". ⚠ A room is a string on the device (D5), not an entity.
import type { HomeDevice } from '@tenshu/sdk/home'

export interface RoomGroup {
  // null = unassigned.
  readonly room: string | null
  readonly devices: readonly HomeDevice[]
}

export function groupByRoom(devices: readonly HomeDevice[], rooms: readonly string[]): RoomGroup[] {
  const groups: RoomGroup[] = rooms.map((room) => ({ room, devices: devices.filter((d) => d.room === room) }))
  // A room the device list names but `rooms` lacks (a blank-only one, or a mismatch) still shows.
  const named = new Set(rooms)
  const rest = devices.filter((d) => d.room.trim() === '' || !named.has(d.room))
  if (rest.length > 0) groups.push({ room: null, devices: rest })
  return groups.filter((g) => g.devices.length > 0)
}
