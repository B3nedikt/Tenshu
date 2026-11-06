import { RuntimeLeaseBanner, type LeaseCopy } from '@tenshu/app-kit/ui'
import { runtimeLease, runtimeUseHere } from '../lib/proxy'

export { STILL_CONNECTING_AFTER_MS } from '@tenshu/app-kit/ui'

// One runtime per origin (docs/web/architecture.md "Cross-tab") — the shared banner with Drive's
// copy. When another tab runs Tenshu, every call this tab makes is refused; this says why, and
// offers the way out.
const COPY: LeaseCopy = {
  connectingTitle: 'Still connecting to Tenshu…',
  connectingBody: 'The Tenshu tab hosting Drive is busy. Drive will load as soon as it answers.',
  elsewhereTitle: 'Tenshu is open in another tab',
  elsewhereBody:
    "Tenshu runs in one tab at a time, so your files can't get out of step. Use it here to move it to this tab.",
  moving: 'Waiting for the other tab to finish saving. This can take up to two minutes.',
  movingShort: 'Moving…',
  useHere: 'Use here',
}

export function RuntimeBanner() {
  return <RuntimeLeaseBanner lease={runtimeLease} useHere={runtimeUseHere} copy={COPY} />
}
