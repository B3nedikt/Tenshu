import { useEffect, useState } from 'react'

// The URL a page was opened with, captured once, for a route whose fragment carries a secret
// (`/i#v1.…` holds K_link, `/s/:id#k=…` holds K_share). The fragment is scrubbed from the address
// bar afterwards, so the secret never lingers in history or a referrer.
//
// ⚠⚠ The read is pure and the scrub happens in an effect, after the page has COMMITTED. Scrubbing
// during render is the bug this exists to prevent: React may throw a render away and run it again,
// and the retry then found the fragment already gone. In production that was `RequiresRuntime`
// flipping the runtime-requested store mid-render (now deferred, `runtimeRequested.ts`), which made
// every invite link read as "incomplete"; a suspending sibling does the same. The route tests pin
// it with a sibling that suspends once.
export interface OpenedUrl {
  // The full URL as opened, fragment included.
  readonly href: string
  // The fragment as opened, with its leading `#`; empty when there was none.
  readonly hash: string
}

export function useSecretFragment(): OpenedUrl {
  const [opened] = useState<OpenedUrl>(() =>
    typeof window === 'undefined'
      ? { href: '', hash: '' }
      : { href: window.location.href, hash: window.location.hash },
  )
  useEffect(() => {
    if (window.location.hash) {
      window.history.replaceState(null, '', window.location.pathname + window.location.search)
    }
  }, [])
  return opened
}
