// The overview's preview cards (docs/proposals/web-apex-home.md §3.3 item 3, §3.6): one per shown
// app that declares a `glance`, in the dock's order. Wide cards take half the row on large
// screens, narrow ones a third, rows packed without holes (`packSpans`); everything stacks on a phone.
import { useMemo } from 'react'
import type { HostedApp } from '../../apps/registry'
import { useVisibleApps } from '../useVisibleApps'
import { GlanceCard } from './GlanceCard'
import { packSpans, SPAN_CLASS } from './packSpans'
import { scopedCall, useGlanceGateway, type GatewayCall } from './glanceGateway'

export function GlanceGrid() {
  const gateway = useGlanceGateway()
  const { apps, state } = useVisibleApps()
  // Cards follow the user's list; with it unknown there is no order to follow, so none are drawn.
  if (gateway === null || state.kind !== 'ready') return null
  const withGlance = apps.filter(app => app.glance !== undefined)
  if (withGlance.length === 0) return null
  const spans = packSpans(withGlance.map(app => app.glance!.size))
  return (
    <section className="grid gap-5 lg:grid-cols-6">
      {withGlance.map((app, i) => (
        <ScopedCard key={app.id} app={app} gateway={gateway} spanClass={SPAN_CLASS[spans[i]!] ?? ''} />
      ))}
    </section>
  )
}

function ScopedCard({
  app,
  gateway,
  spanClass,
}: {
  readonly app: HostedApp
  readonly gateway: GatewayCall
  readonly spanClass: string
}) {
  // Stable per app, so the card's load effect runs once rather than on every render.
  const call = useMemo(() => scopedCall(app, gateway), [app, gateway])
  return <GlanceCard app={app} glance={app.glance!} call={call} spanClass={spanClass} />
}
