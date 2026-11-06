/**
 * Contact-invite redeem-on-open route (`drive-web-mode-b-sharing.md` §3, MB-1).
 *
 * The joiner opened `https://tenshu.app/i#v1.<base64url(inviteId ‖ K_link ‖
 * a13Pub)>`. The fragment is captured once on mount and scrubbed from the URL
 * (so `K_link` never lingers in history / the referrer), then handed to
 * `Contacts.redeemInvite(...)` — the apex runs the same sealed-box redemption
 * the natives do; `K_link` never reaches the relay.
 *
 * Redeeming binds a *new contact* to the joiner's identity, so this route is
 * session-gated (unlike the anonymous Mode-A `/s/:shareId` viewer): `firstTime`
 * → create an account first, `locked` → unlock, `active` → name + redeem.
 *
 * Route-level note: this is a top-level route (not under ControlShell), so the
 * shell's session guard doesn't bounce it — the gating here is bespoke.
 */
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { Contacts } from '@tenshu/sdk/contact'
import { Session, type SessionState } from '@tenshu/sdk/onboarding'
import { signupOpen } from '../../lib/signupGate'

type Phase =
  | { kind: 'detecting' }
  | { kind: 'firstTime' }
  | { kind: 'locked' }
  | { kind: 'form' }
  | { kind: 'redeeming' }
  | { kind: 'awaiting'; peerName: string }
  | { kind: 'done'; status: string; peerName: string }
  | { kind: 'error'; reason: 'alreadyUsed' | 'gone' | 'failed' | 'noLink' }

export default function ContactRedeemRoute() {
  // Capture the full invite URL once, synchronously, then scrub the fragment
  // (replaceState keeps the page but drops `#v1...` from history + referrer).
  const link = useMemo(() => {
    if (typeof window === 'undefined') return null
    const href = window.location.href
    const hadFragment = window.location.hash.length > 0
    if (hadFragment) {
      window.history.replaceState(null, '', window.location.pathname + window.location.search)
    }
    return hadFragment ? href : null
  }, [])

  const [phase, setPhase] = useState<Phase>({ kind: 'detecting' })
  const [name, setName] = useState('')

  // "Opened without a fragment" is a property of the URL, not something that
  // happens over time — derive it at render rather than setting state from the
  // effect (which would cost an extra render and trip the cascading-render rule).
  const view: Phase = link ? phase : { kind: 'error', reason: 'noLink' }

  useEffect(() => {
    let cancelled = false
    if (!link) return
    void Session.detect().then((s: SessionState) => {
      if (cancelled) return
      if (s.kind === 'firstTime') setPhase({ kind: 'firstTime' })
      else if (s.kind === 'locked') setPhase({ kind: 'locked' })
      else setPhase({ kind: 'form' })
    })
    return () => {
      cancelled = true
    }
  }, [link])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!link || !name.trim()) return
    setPhase({ kind: 'redeeming' })
    const result = await Contacts.redeemInvite(link, name.trim())
    if (result.outcome !== 'redeemed' || !result.inviteId) {
      const reason =
        result.outcome === 'alreadyUsed'
          ? 'alreadyUsed'
          : result.outcome === 'gone'
            ? 'gone'
            : 'failed'
      setPhase({ kind: 'error', reason })
      return
    }
    // Poll one ack budget; PENDING means the inviter hasn't confirmed yet — the
    // contact still forms on their confirmation, so we report a calm "sent".
    setPhase({ kind: 'awaiting', peerName: '' })
    const ack = await Contacts.awaitAck(result.inviteId, false)
    setPhase({ kind: 'done', status: ack.status, peerName: ack.peerName })
  }

  return (
    <main className="mx-auto max-w-md px-6 py-16">
      <h1 className="text-xl font-semibold tracking-tight text-text">Connect on Tenshu</h1>

      {view.kind === 'detecting' && <p className="mt-4 text-sm text-text-2">Checking…</p>}

      {/* An invite can arrive while the pre-launch signup gate is closed. Sending the
          recipient to a CTA that promises an account it cannot create is worse than
          telling them plainly, so this branch says so instead of linking onward. */}
      {view.kind === 'firstTime' && !signupOpen() && (
        <Notice>
          <p>
            Connecting needs a Tenshu account, and Tenshu isn&rsquo;t open for new accounts just
            yet. Hold on to this link &mdash; it will still work once we open.
          </p>
        </Notice>
      )}

      {view.kind === 'firstTime' && signupOpen() && (
        <Notice>
          <p>You&rsquo;ll need a Tenshu account to connect. Create one, then open the invite link again.</p>
          <Cta to="/start">Create an account</Cta>
        </Notice>
      )}

      {view.kind === 'locked' && (
        <Notice>
          <p>Unlock Tenshu on this device, then open the invite link again.</p>
          <Cta to="/lock">Unlock</Cta>
        </Notice>
      )}

      {view.kind === 'form' && (
        <form onSubmit={submit} className="mt-4 space-y-4">
          <p className="text-sm text-text-2">
            Someone invited you to connect. Enter the name they&rsquo;ll see for you.
          </p>
          <label className="block text-sm">
            <span className="text-text-2">Your name</span>
            <input
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="mt-1 w-full rounded-lg border border-line px-3 py-1.5 text-sm outline-none focus:border-line-strong"
            />
          </label>
          <button
            type="submit"
            disabled={!name.trim()}
            className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90 disabled:opacity-50"
          >
            Connect
          </button>
        </form>
      )}

      {view.kind === 'redeeming' && <p className="mt-4 text-sm text-text-2">Connecting…</p>}
      {view.kind === 'awaiting' && (
        <p className="mt-4 text-sm text-text-2">Waiting for them to confirm…</p>
      )}

      {view.kind === 'done' && <DoneNotice status={view.status} peerName={view.peerName} />}

      {view.kind === 'error' && <ErrorNotice reason={view.reason} />}
    </main>
  )
}

function DoneNotice({ status, peerName }: { status: string; peerName: string }) {
  if (status === 'ACCEPTED') {
    return (
      <Notice>
        <p>
          You&rsquo;re connected{peerName ? <> with <strong>{peerName}</strong></> : null}.
        </p>
        <Cta to="/drive/files">Open Drive</Cta>
      </Notice>
    )
  }
  if (status === 'REJECTED') {
    return (
      <Notice tone="error">
        <p>They declined the connection.</p>
      </Notice>
    )
  }
  // PENDING / GONE / INVALID — the response is recorded; the inviter confirms
  // on their side, at which point the contact appears for both.
  return (
    <Notice>
      <p>Your response was sent. You&rsquo;ll be connected once they confirm.</p>
      <Cta to="/drive/files">Open Drive</Cta>
    </Notice>
  )
}

function ErrorNotice({ reason }: { reason: 'alreadyUsed' | 'gone' | 'failed' | 'noLink' }) {
  const message =
    reason === 'alreadyUsed'
      ? 'This invite has already been used. Ask for a fresh link.'
      : reason === 'gone'
        ? 'This invite has expired or been cancelled. Ask for a fresh link.'
        : reason === 'noLink'
          ? 'This invite link is incomplete. Make sure you opened the full URL.'
          : "Couldn't connect right now. Try opening the link again."
  return (
    <Notice tone="error">
      <p>{message}</p>
    </Notice>
  )
}

function Notice({
  children,
  tone = 'muted',
}: {
  children: React.ReactNode
  tone?: 'muted' | 'error'
}) {
  return (
    <div
      className={`mt-4 space-y-3 rounded-lg border px-4 py-3 text-sm ${
        tone === 'error'
          ? 'border-danger/30 bg-danger/10 text-danger'
          : 'border-line bg-surface-2 text-text-2'
      }`}
    >
      {children}
    </div>
  )
}

function Cta({ to, children }: { to: string; children: React.ReactNode }) {
  return (
    <Link
      to={to}
      className="inline-block rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90"
    >
      {children}
    </Link>
  )
}
