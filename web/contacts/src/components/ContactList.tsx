// The contact list — native `ContactListScreen`: every contact sorted by petname (the SDK sorts),
// a search over names and details, add-by-name, and `.vcf` import. Live: it reads the
// `contact.list.observe` stream through `useContacts`, so a write anywhere re-renders it.
//
// ⚠⚠ A failed read renders as an ERROR, never as "No contacts yet" (run A: the web has no
// reachability banner to explain an empty book).
import { useRef, useState, type ChangeEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { Contact } from '@tenshu/sdk/contact'
import { createContact, importVcards } from '../lib/proxy'
import { useContacts } from '../lib/live'
import { matchesQuery } from '../lib/contacts'
import { Avatar, Button, ErrorText, Field, PromptDialog, TrustBadge } from './ui'
import { attempt, useContactName, type Outcome } from '../lib/outcome'
import { StreamNotice } from './StreamNotice'

export function ContactList({ onOpen }: { onOpen: (personId: string) => void }) {
  const { t } = useTranslation()
  const live = useContacts()
  const nameOf = useContactName()
  const [query, setQuery] = useState('')
  const [adding, setAdding] = useState(false)

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-end gap-2">
        <div className="min-w-48 flex-1">
          <Field
            label={t('list.searchLabel')}
            type="search"
            placeholder={t('list.searchPlaceholder')}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <Button onClick={() => setAdding(true)}>{t('list.add')}</Button>
        <ImportButton />
      </div>

      <StreamNotice live={live} />
      <ListBody query={query} onOpen={onOpen} nameOf={nameOf} />

      {adding && (
        <PromptDialog
          title={t('add.title')}
          supporting={t('add.supporting')}
          label={t('common.name')}
          submitLabel={t('add.confirm')}
          onClose={() => setAdding(false)}
          onSubmit={async (name) => {
            let created: string | null = null
            const outcome = await attempt(async () => {
              const r = await createContact(name)
              if (r.ok) created = r.personId
              return r
            })
            if (!outcome && created) onOpen(created)
            return outcome
          }}
        />
      )}
    </section>
  )
}

function ListBody({
  query,
  onOpen,
  nameOf,
}: {
  query: string
  onOpen: (personId: string) => void
  nameOf: (c: Contact) => string
}) {
  const { t } = useTranslation()
  const live = useContacts()
  if (live.isPending) return <p className="text-sm text-text-3">{t('common.loading')}</p>
  if (live.error && !live.data) return <ErrorText error={live.error} />
  const result = live.data
  if (!result) return null
  if (!result.ok) return <ErrorText failure={result.error} />

  if (result.contacts.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-line p-8 text-center">
        <p className="font-medium text-text">{t('list.emptyTitle')}</p>
        <p className="mt-1 text-sm text-text-2">{t('list.emptyBody')}</p>
      </div>
    )
  }
  const shown = result.contacts.filter((c) => matchesQuery(c, query))
  if (shown.length === 0) {
    return <p className="text-sm text-text-2">{t('list.noMatches')}</p>
  }
  return (
    <ul className="divide-y divide-line rounded-xl border border-line" aria-label={t('list.aria')}>
      {shown.map((contact) => (
        <li key={contact.personId}>
          <button
            type="button"
            onClick={() => onOpen(contact.personId)}
            className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-surface-2 focus:outline-none focus-visible:bg-surface-2"
          >
            <Avatar contact={contact} />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-medium text-text">{nameOf(contact)}</span>
              {contact.details.organisation && (
                <span className="block truncate text-xs text-text-3">{contact.details.organisation}</span>
              )}
            </span>
            {contact.trust !== 'notConnected' && <TrustBadge trust={contact.trust} />}
          </button>
        </li>
      ))}
    </ul>
  )
}

// `.vcf` import from a file the user picks. The device address book has no web API, so this is the
// whole import surface on the web (web-app-suite.md §8.1). Parsing happens in the SDK.
function ImportButton() {
  const { t } = useTranslation()
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [report, setReport] = useState<{ imported: number; cards: number } | null>(null)
  const [outcome, setOutcome] = useState<Outcome>(null)

  async function onFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setBusy(true)
    setReport(null)
    setOutcome(null)
    const text = await file.text()
    const result = await attempt(async () => {
      const r = await importVcards(text)
      if (r.ok) setReport({ imported: r.imported, cards: r.cards })
      return r
    })
    setOutcome(result)
    setBusy(false)
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <input
        ref={input}
        type="file"
        accept=".vcf,text/vcard,text/x-vcard"
        className="sr-only"
        aria-label={t('import.fileLabel')}
        onChange={onFile}
        data-testid="vcf-input"
      />
      <Button variant="outline" disabled={busy} onClick={() => input.current?.click()}>
        {busy ? t('import.working') : t('import.action')}
      </Button>
      {report && (
        <p role="status" className="text-xs text-text-2">
          {report.imported < report.cards
            ? t('import.partial', { imported: report.imported, cards: report.cards })
            : t('import.done', { count: report.imported })}
        </p>
      )}
      {outcome && (
        <div className="max-w-64 text-right">
          {'failure' in outcome ? <ErrorText failure={outcome.failure} /> : <ErrorText error={outcome.error} />}
        </div>
      )}
    </div>
  )
}
