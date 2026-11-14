import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { FileActivity, FileDetails as FileDetailsData } from '@tenshu/sdk/files'
import { activity, details } from '../lib/proxy'
import { basename, formatBytes, formatDate, formatDateTime } from '../lib/paths'
import { queryKeys } from '../lib/queryKeys'
import { downloadFile } from '../lib/downloadFile'

// One file's metadata (Details) + modification history (Activity) — the two FileDetailsScreen
// reads, B2a + B2b — as the content of the app's details pane (web-apps-layout.md §3.2: one click
// selects, the pane shows the selection, ⓘ / ⌘I opens and closes it and it stays as you left it).
// Snapshot reads keyed on the path; the pane's own Close is [onClose]. Download saves the bytes.
export function FileDetails({ path, onClose }: { path: string; onClose: () => void }) {
  const detailsQuery = useQuery({
    queryKey: queryKeys.details(path),
    queryFn: () => details(path),
  })
  const activityQuery = useQuery({
    queryKey: queryKeys.activity(path),
    queryFn: () => activity(path),
  })

  const [downloading, setDownloading] = useState(false)
  const [downloadError, setDownloadError] = useState<string | null>(null)

  // Fetch the bytes on demand (the metadata above loads without them) and save them to disk.
  async function onDownload() {
    setDownloading(true)
    setDownloadError(null)
    try {
      setDownloadError(await downloadFile(path))
    } finally {
      setDownloading(false)
    }
  }

  return (
    <section aria-label="File details" className="flex flex-col">
        <header className="flex items-center justify-between gap-3 border-b border-line pb-4">
          <h2 className="min-w-0 truncate text-base font-semibold text-text">
            {basename(path)}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close details"
            className="shrink-0 rounded p-1 text-text-3 hover:bg-surface-3 hover:text-text"
          >
            <svg className="size-5" viewBox="0 0 20 20" fill="currentColor" aria-hidden>
              <path d="m6 6 8 8M14 6l-8 8" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          </button>
        </header>

        <div className="border-b border-line py-3">
          <button
            type="button"
            onClick={onDownload}
            disabled={downloading}
            className="w-full rounded-lg bg-primary px-3 py-2 text-sm font-medium text-on-primary hover:bg-primary/90 disabled:opacity-50"
          >
            {downloading ? 'Downloading…' : 'Download'}
          </button>
          {downloadError && <p className="mt-2 text-sm text-danger">{downloadError}</p>}
        </div>

        <div className="space-y-7 py-5">
          <section>
            <SectionTitle>Details</SectionTitle>
            {detailsQuery.isPending ? (
              <Muted>Loading…</Muted>
            ) : !detailsQuery.data ? (
              <Muted>No details available.</Muted>
            ) : !detailsQuery.data.ok ? (
              // ⚠ "the read failed" is not "there is nothing here". Both rendered the same
              // "No details available." before, so a broken listing read looked like an empty
              // file — the drawer's own version of the blank that hid W2a.
              <Muted>Couldn't load details ({detailsQuery.data.error.kind}).</Muted>
            ) : detailsQuery.data.row ? (
              <DetailRows data={detailsQuery.data.row} />
            ) : (
              <Muted>No details available.</Muted>
            )}
          </section>

          <section>
            <SectionTitle>Activity</SectionTitle>
            {activityQuery.isPending ? (
              <Muted>Loading…</Muted>
            ) : activityQuery.data && activityQuery.data.length > 0 ? (
              <ActivityList rows={activityQuery.data} />
            ) : (
              <Muted>No activity yet.</Muted>
            )}
          </section>
        </div>
    </section>
  )
}

function DetailRows({ data }: { data: FileDetailsData }) {
  return (
    <dl className="space-y-2 text-sm">
      <Row label="Size" value={formatBytes(data.size)} />
      <Row label="Created" value={formatDate(data.createdAt) || '—'} />
      <Row label="Modified" value={formatDate(data.lastModified) || '—'} />
      <Row label="Path" value={data.path} mono />
    </dl>
  )
}

function Row({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="shrink-0 text-text-2">{label}</dt>
      <dd className={`min-w-0 truncate text-right text-text ${mono ? 'font-mono text-xs' : ''}`}>
        {value}
      </dd>
    </div>
  )
}

const KIND_LABEL: Record<FileActivity['kind'], string> = {
  Created: 'Created',
  Modified: 'Modified',
  Renamed: 'Renamed',
  Moved: 'Moved',
}

function ActivityList({ rows }: { rows: readonly FileActivity[] }) {
  return (
    <ul className="space-y-3">
      {rows.map((row, i) => (
        <li key={`${row.timestampMillis}-${i}`} className="text-sm">
          <div className="flex items-baseline justify-between gap-3">
            <span className="font-medium text-text">{KIND_LABEL[row.kind]}</span>
            <span className="shrink-0 text-xs text-text-3">
              {formatDateTime(row.timestampMillis)}
            </span>
          </div>
          {row.detail && <p className="text-text-2">{row.detail}</p>}
          <p className="text-xs text-text-3">{row.deviceName ?? row.deviceId}</p>
        </li>
      ))}
    </ul>
  )
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h3 className="mb-3 text-xs font-semibold uppercase tracking-wide text-text-3">
      {children}
    </h3>
  )
}

function Muted({ children }: { children: React.ReactNode }) {
  return <p className="text-sm text-text-3">{children}</p>
}
