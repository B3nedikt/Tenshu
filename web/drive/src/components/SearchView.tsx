import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { searchFiles } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { basename, formatBytes, formatDate, isFolder, parentDir } from '../lib/paths'
import { FileThumbnail } from './FileThumbnail'

// W4: search, as native has it.
//
// ⚠ Debounced rather than searched-per-keystroke: each query is a full `traverse()` of the drive
// proxy-side (the same trade native made — an index only pays above ~10k–50k files). 250ms is
// below the threshold where typing feels laggy and well above a keystroke.
const DEBOUNCE_MS = 250

export function SearchView({ onOpenFolder }: { onOpenFolder: (path: string) => void }) {
  const [input, setInput] = useState('')
  const [needle, setNeedle] = useState('')

  // Debounce without a library: a timer per keystroke, cleared by the next.
  useDebounced(input, DEBOUNCE_MS, setNeedle)

  const trimmed = needle.trim()
  const { data, isFetching } = useQuery({
    queryKey: queryKeys.search(trimmed),
    queryFn: () => searchFiles(trimmed),
    // ⚠ Skipped entirely for an empty needle rather than relying on the SDK's empty-array
    // short-circuit — no query, no request, no spinner.
    enabled: trimmed.length > 0,
  })

  const results = data ?? []

  return (
    <div>
      <input
        type="search"
        value={input}
        onChange={(e) => setInput(e.target.value)}
        placeholder="Search your files"
        aria-label="Search your files"
        className="w-full rounded-lg border border-line px-3 py-2 text-sm outline-none focus:border-line-strong"
      />

      {trimmed.length === 0 ? (
        <p className="mt-6 text-center text-sm text-text-3">Type to search across your drive.</p>
      ) : isFetching && results.length === 0 ? (
        <p className="mt-6 text-center text-sm text-text-3">Searching…</p>
      ) : results.length === 0 ? (
        // ⚠ Names the query. "No results" alone reads as "your drive is empty" — native's
        // zero states make the same distinction.
        <p className="mt-6 text-center text-sm text-text-3">
          Nothing matches “{trimmed}”.
        </p>
      ) : (
        <ul className="mt-3 divide-y divide-line rounded-xl border border-line">
          {results.map((entry) => (
            <li key={entry.path}>
              <button
                type="button"
                onClick={() => onOpenFolder(isFolder(entry) ? entry.path : parentDir(entry.path))}
                className="flex w-full min-w-0 items-center gap-3 px-3 py-3 text-left hover:bg-surface"
              >
                <FileThumbnail path={entry.path} name={basename(entry.path)} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm text-text">{basename(entry.path)}</span>
                  {/* The folder is the point of a search result — without it two files with the
                      same name are indistinguishable. */}
                  <span className="block truncate text-xs text-text-3">
                    {parentDir(entry.path)}
                    {!isFolder(entry) &&
                      ` · ${[formatBytes(entry.size), formatDate(entry.lastModified)]
                        .filter(Boolean)
                        .join(' · ')}`}
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// ⚠ The cleanup is the point: the timer must be torn down on every keystroke, or a burst of
// typing fires one full drive traverse per character.
function useDebounced(value: string, ms: number, onSettled: (v: string) => void) {
  useEffect(() => {
    const t = setTimeout(() => onSettled(value), ms)
    return () => clearTimeout(t)
  }, [value, ms, onSettled])
}
