// A registered app (`apps/registry/apps/<id>.ts`) framed inside the apex chrome — the one embed every
// hosted app uses (it replaced the per-app `DriveEmbed` / `ContactsEmbed`, web-app-suite.md F4).
// The frame's origin is the same one the gateway admits for that app, by construction: both read
// the registry entry's `origin()`.
import { AppEmbed } from './AppEmbed'
import type { HostedApp } from './registry'

export function HostedAppEmbed({ app }: { app: HostedApp }) {
  return <AppEmbed origin={app.origin()} title={app.title} />
}
