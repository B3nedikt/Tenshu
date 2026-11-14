// A registered app (`apps/registry/apps/<id>.ts`) framed inside the apex chrome — the one embed every
// hosted app uses (it replaced the per-app `DriveEmbed` / `ContactsEmbed`, web-app-suite.md F4).
// The frame's origin is the same one the gateway admits for that app, by construction: both read
// the registry entry's `origin()`.
//
// It also keeps the apex URL and the app's place in step (web-apps-layout.md §3.4, `appNav.ts`):
// - the frame OPENS where the apex URL points (`/drive/files#/Taxes` → `<drive>/files#/Taxes`);
// - the app REPORTS each move (`tenshu.nav`), and the apex pushes or replaces its own entry;
// - a move the app did not report (back/forward, the launcher) is SENT to it (`tenshu.route`), and
//   re-sent when the app says it is listening (`tenshu.ready`) — a message sent earlier is lost.
import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { AppEmbed } from './AppEmbed'
import { READY_MESSAGE, ROUTE_MESSAGE, apexUrl, appPathFor, readNavMessage } from './appNav'
import type { HostedApp } from './registry'

export function HostedAppEmbed({ app }: { app: HostedApp }) {
  // ⚠ Keyed by app: react-router renders sibling routes without keys, so a switch from Drive to
  // Photos would otherwise REUSE this instance — the new app opening at the old one's place (its
  // fragment included), and the `src` change pushing an entry into the frame's own history.
  return <HostedAppFrame key={app.id} app={app} />
}

function HostedAppFrame({ app }: { app: HostedApp }) {
  const location = useLocation()
  const navigate = useNavigate()
  const frame = useRef<HTMLIFrameElement>(null)
  const path = appPathFor(app, location.pathname)
  const here = path + location.hash
  // The frame's `src`, fixed at mount: a new `src` would reload the app.
  const [entry] = useState(here === '/' ? '' : here)
  // Where the app last was, as far as the apex knows — so a move the app itself reported is not
  // echoed back to it.
  const known = useRef(here)
  // The apex's current place, for the ready handshake (read from inside the message listener).
  const current = useRef({ path, hash: location.hash })
  useEffect(() => {
    current.current = { path, hash: location.hash }
  })

  useEffect(() => {
    const origin = app.origin()
    const onMessage = (event: MessageEvent) => {
      if (event.source !== frame.current?.contentWindow || event.origin !== origin) return
      // The app is now listening. A move sent while it was still booting (Back, the launcher)
      // was dropped, so send where the apex is now; when nothing moved, applying it is a no-op.
      if ((event.data as { type?: unknown } | null)?.type === READY_MESSAGE) {
        known.current = current.current.path + current.current.hash
        frame.current.contentWindow?.postMessage({ type: ROUTE_MESSAGE, ...current.current }, origin)
        return
      }
      const report = readNavMessage(app, event.data)
      if (!report) return
      known.current = report.path + report.hash
      navigate(apexUrl(app, report.path, report.hash), { replace: !report.push })
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [app, navigate])

  useEffect(() => {
    if (here === known.current) return
    known.current = here
    frame.current?.contentWindow?.postMessage({ type: ROUTE_MESSAGE, path, hash: location.hash }, app.origin())
  }, [app, here, path, location.hash])

  return <AppEmbed origin={app.origin()} entry={entry} frameRef={frame} title={app.title} />
}
