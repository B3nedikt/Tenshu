import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

// The sidebar's search field (web-apps-layout.md L7). Typing searches after a short pause; Enter at
// once; Escape clears. [initial] is the URL's `q=` (or '' off the search section): when it changes
// to something the field did not just commit (Back/forward), the field follows it.
const PAUSE_MS = 250

export function SearchBox({
  initial,
  section,
  onSearch,
}: {
  initial: string
  // The app's current section: a move to another one cancels a search still waiting to fire.
  section: string
  onSearch: (query: string) => void
}) {
  const { t } = useTranslation()
  const [text, setText] = useState(initial)
  // Follow the URL, adjusted during render (React's pattern for state that tracks a prop): only when
  // it moved to a value this field did not type, so a commit of its own never resets the caret.
  const [seen, setSeen] = useState(initial)
  if (initial !== seen) {
    setSeen(initial)
    if (initial !== text.trim() && !(initial === '' && text === '')) setText(initial)
  }
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current)
  }, [])
  // ⚠ A pending search belongs to the place it was typed in: the timer holds that render's
  // `onSearch` (and its section), so firing after a move would pull the user back into search, or
  // write the query into another section's URL. Any move — a section, or the URL's `q=` — cancels it.
  useEffect(() => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = null
  }, [section, initial])

  const commit = (value: string) => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = null
    onSearch(value)
  }

  return (
    <input
      type="search"
      // The gateway refuses a longer name; better to stop typing than to show a bridge error.
      maxLength={200}
      value={text}
      aria-label={t('search.label')}
      placeholder={t('search.placeholder')}
      onChange={(e) => {
        const value = e.target.value
        setText(value)
        if (timer.current) clearTimeout(timer.current)
        timer.current = setTimeout(() => commit(value), PAUSE_MS)
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter') commit(text)
        else if (e.key === 'Escape' && text) {
          e.preventDefault()
          setText('')
          commit('')
        }
      }}
      className="w-full rounded-lg border border-line bg-surface-2 px-3 py-2 text-sm text-text placeholder:text-text-3 focus:border-primary focus:outline-none"
    />
  )
}
