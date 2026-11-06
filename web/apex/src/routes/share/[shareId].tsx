/**
 * Mode-A share viewer route (`docs/proposals/drive-file-sharing.md` §3.4 / §6.5).
 *
 * Anonymous recipient surface: reads `shareId` from the path + `K_share` from
 * the URL fragment, then `ShareViewer.fetch(...)` (descriptor GET → chunk GETs →
 * AEAD decrypt) returns the decrypted bytes. `K_share` is stripped from the URL
 * synchronously on mount so it never lingers in history / the referrer.
 *
 * Renders raster images / pdf inline and text as text; everything else gets a
 * download button. ⚠⚠ The MIME type is the sharer's claim, so what renders is an
 * allowlist and the blob of anything else is typed `application/octet-stream`
 * (`inlineSafety.ts`) — a same-origin HTML/SVG blob would run script as the apex.
 * 410 / network / decrypt / meta failures map to friendly states.
 *
 * The file name and MIME type come only from the share's sealed metadata,
 * opened under `K_share` by `ShareViewer` (relay-metadata-minimisation.md F3):
 * the relay never had them. A result without them is an error (`meta`), never a
 * download under a placeholder name.
 *
 * Route-level allowlist: the apex's session-gating must NOT redirect `/s/*`
 * (this route) to `/lock` — a recipient has no session.
 */
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { ShareViewer } from '@tenshu/sdk/share'
import { RELAY_URL } from '../../lib/relayUrl'
import { blobType, previewKind } from './inlineSafety'

type ErrorKind = 'gone' | 'network' | 'decrypt' | 'meta' | 'no-key'

const FETCH_ERROR_KINDS: readonly ErrorKind[] = ['gone', 'network', 'decrypt', 'meta']

type ViewState =
  | { phase: 'loading' }
  | { phase: 'error'; kind: ErrorKind }
  | { phase: 'ready'; fileName: string; mimeType: string; objectUrl: string }

export default function ShareViewerRoute() {
  const { t } = useTranslation()
  const { shareId } = useParams<{ shareId: string }>()

  // Capture K_share once, synchronously, then scrub the fragment from the URL
  // (replaceState keeps the page but drops `#k=...` from history + referrer).
  const kShare = useMemo(() => {
    if (typeof window === 'undefined') return null
    const k = new URLSearchParams(window.location.hash.replace(/^#/, '')).get('k')
    if (window.location.hash) {
      window.history.replaceState(
        null,
        '',
        window.location.pathname + window.location.search,
      )
    }
    return k
  }, [])

  const [state, setState] = useState<ViewState>({ phase: 'loading' })
  // Hold the live object URL so the cleanup effect can revoke exactly it.
  const objectUrlRef = useRef<string | null>(null)

  // A link that arrived without its `#k=` fragment (or without a share id) can
  // never resolve — that is a property of the URL, so derive it at render rather
  // than setting state from the effect (an extra render, and the cascading-render
  // rule flags it).
  const view: ViewState = shareId && kShare ? state : { phase: 'error', kind: 'no-key' }

  useEffect(() => {
    let cancelled = false
    if (!shareId || !kShare) return

    ShareViewer.fetch(shareId, kShare, RELAY_URL)
      .then((result) => {
        if (cancelled) return
        if (!result.ok || !result.bytes) {
          const kind = FETCH_ERROR_KINDS.find((k) => k === result.errorKind) ?? 'network'
          setState({ phase: 'error', kind })
          return
        }
        // The name and MIME come from the sealed metadata; without them there is nothing
        // honest to show — fail visibly instead of offering "shared-file".
        const { fileName, mimeType } = result
        if (!fileName || !mimeType) {
          setState({ phase: 'error', kind: 'meta' })
          return
        }
        // Int8Array → Blob. Copy into a freshly-allocated Uint8Array (a plain
        // ArrayBuffer, not the Kotlin/JS heap's possibly-Shared buffer) so the
        // Blob constructor's `BlobPart` type is satisfied under strict lib.
        const copy = new Uint8Array(result.bytes.length)
        copy.set(result.bytes)
        const blob = new Blob([copy], { type: blobType(mimeType) })
        const objectUrl = URL.createObjectURL(blob)
        objectUrlRef.current = objectUrl
        setState({ phase: 'ready', fileName, mimeType, objectUrl })
      })
      .catch(() => {
        if (!cancelled) setState({ phase: 'error', kind: 'network' })
      })

    return () => {
      cancelled = true
      if (objectUrlRef.current) {
        URL.revokeObjectURL(objectUrlRef.current)
        objectUrlRef.current = null
      }
    }
  }, [shareId, kShare])

  return (
    <main style={{ maxWidth: 880, margin: '0 auto', padding: 24, fontFamily: 'system-ui' }}>
      <h1 style={{ fontSize: 20, marginBottom: 16 }}>{t('share.viewer.title')}</h1>
      {view.phase === 'loading' && <p>{t('share.viewer.loading')}</p>}
      {view.phase === 'error' && <ShareError kind={view.kind} />}
      {view.phase === 'ready' && (
        <SharePreview
          fileName={view.fileName}
          mimeType={view.mimeType}
          objectUrl={view.objectUrl}
        />
      )}
    </main>
  )
}

const ERROR_KEYS: Record<ErrorKind, string> = {
  gone: 'share.viewer.error.gone',
  decrypt: 'share.viewer.error.decrypt',
  meta: 'share.viewer.error.meta',
  'no-key': 'share.viewer.error.noKey',
  network: 'share.viewer.error.network',
}

function ShareError({ kind }: { kind: ErrorKind }) {
  const { t } = useTranslation()
  const message = t(ERROR_KEYS[kind])
  return (
    <div
      role="alert"
      style={{
        padding: 16,
        borderRadius: 8,
        background: '#fff4f4',
        border: '1px solid #f3c4c4',
        color: '#7a2222',
      }}
    >
      {message}
    </div>
  )
}

function SharePreview({
  fileName,
  mimeType,
  objectUrl,
}: {
  fileName: string
  mimeType: string
  objectUrl: string
}) {
  const { t } = useTranslation()
  const download = (
    <p style={{ marginTop: 16 }}>
      <a href={objectUrl} download={fileName} rel="noopener" style={{ color: '#2563eb' }}>
        {t('share.viewer.download', { fileName })}
      </a>
    </p>
  )

  const kind = previewKind(mimeType)

  if (kind === 'image') {
    return (
      <figure style={{ margin: 0 }}>
        <img
          src={objectUrl}
          alt={fileName}
          style={{ maxWidth: '100%', borderRadius: 8, display: 'block' }}
        />
        <figcaption style={{ marginTop: 8, color: '#555' }}>{fileName}</figcaption>
        {download}
      </figure>
    )
  }

  if (kind === 'pdf') {
    return (
      <div>
        <iframe
          src={objectUrl}
          title={fileName}
          style={{ width: '100%', height: '75vh', border: '1px solid #ddd', borderRadius: 8 }}
        />
        {download}
      </div>
    )
  }

  if (kind === 'text') {
    return <TextPreview fileName={fileName} objectUrl={objectUrl} download={download} />
  }

  return (
    <div>
      <p style={{ color: '#555' }}>
        <strong>{fileName}</strong> ({mimeType})
      </p>
      {download}
    </div>
  )
}

function TextPreview({
  fileName,
  objectUrl,
  download,
}: {
  fileName: string
  objectUrl: string
  download: ReactNode
}) {
  const { t } = useTranslation()
  const [text, setText] = useState<string | null>(null)
  useEffect(() => {
    let cancelled = false
    fetch(objectUrl)
      .then((r) => r.text())
      .then((t) => {
        if (!cancelled) setText(t)
      })
      .catch(() => {
        if (!cancelled) setText(null)
      })
    return () => {
      cancelled = true
    }
  }, [objectUrl])

  return (
    <div>
      <pre
        style={{
          maxHeight: '70vh',
          overflow: 'auto',
          padding: 16,
          background: '#f6f8fa',
          borderRadius: 8,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
        }}
      >
        {text ?? t('share.viewer.textLoading', { fileName })}
      </pre>
      {download}
    </div>
  )
}
