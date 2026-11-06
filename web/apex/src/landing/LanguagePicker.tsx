// The site's language picker: the support page's header (under the title) and the footer of every
// marketing surface draw this one component. The summary shows the current language's own name;
// opening it lists every language in its own name, the current one marked. A list, not pills, so
// it scales past three languages.
//
// It is NAVIGATION, not a setting: each entry is a link to this same page in that language, so it
// works with JavaScript off and a shared link shows what the sender saw
// (store-presence-and-support-pages.md §4.1). Hence a <details> of plain links with aria-current —
// not a <select>, which needs a script to navigate. The static support pages render it with
// `renderToStaticMarkup` and never hydrate it (src/landing/supportChrome.tsx); with React running
// the effects below add what <details> lacks — Escape, a click outside, `aria-expanded` — like
// MarketingMobileMenu.
//
// Following a link also stores the pick (LANGUAGE_STORAGE_KEY), a per-viewer convenience exactly
// like the theme: it decides only what an address without a language shows. In the SPA the click
// handler stores it; on a static support page LANGUAGE_SWITCH_SCRIPT does, keyed on the same
// `data-lang-choice` attribute.
import { useEffect, useId, useRef, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { LANGUAGE_CHOICE_ATTRIBUTE, storeLanguageChoice } from './languages'

export interface LanguageLink {
  readonly lang: string
  /** The language's own name ("Deutsch"), rendered in that language (`lang`). */
  readonly name: string
  /** This page in that language. */
  readonly href: string
  readonly current: boolean
  /**
   * A page with no URL per language (the share viewer, the invite page — MarketingLayout): the
   * link points at this page and a click switches the SPA's language in place instead of
   * navigating, so the page — and whatever it holds, a share being decrypted — stays put.
   */
  readonly inPlace?: boolean
}

export interface LanguagePickerProps {
  readonly links: readonly LanguageLink[]
  /**
   * Where the list opens: `up` at the foot of a page (the footer), `down` under a title. And which
   * edge it lines up with — the picker's own, so the list never reaches past the column's gutter.
   */
  readonly opens?: 'up' | 'down'
  readonly align?: 'start' | 'end'
}

export function LanguagePicker({ links, opens = 'down', align = 'start' }: LanguagePickerProps) {
  const { t, i18n } = useTranslation('landing')
  const labelId = useId()
  const currentId = useId()
  const ref = useRef<HTMLDetailsElement>(null)
  const summaryRef = useRef<HTMLElement>(null)
  const [open, setOpen] = useState(false)
  // Only where React runs: a static page is rendered to markup and never hydrated, and a baked-in
  // `aria-expanded="false"` would be wrong the moment <details> opened on its own. <summary>
  // already reports its state; the server snapshot is that static render.
  const live = useSyncExternalStore(noSubscription, isLive, isStatic)

  useEffect(() => {
    if (!open) return
    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Escape') return
      setOpen(false)
      summaryRef.current?.focus()
    }
    function onPointerDown(e: MouseEvent) {
      if (ref.current !== null && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('keydown', onKeyDown)
    document.addEventListener('mousedown', onPointerDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('mousedown', onPointerDown)
    }
  }, [open])

  const current = links.find((link) => link.current)
  if (links.length < 2 || current === undefined) return null
  return (
    <nav aria-labelledby={labelId} className="flex items-center gap-2 text-sm">
      <span id={labelId} className="text-text-2">
        {t('footer.language.label')}
      </span>
      <details
        ref={ref}
        open={open}
        onToggle={(e) => setOpen(e.currentTarget.open)}
        className="group relative"
      >
        <summary
          ref={summaryRef}
          aria-labelledby={`${labelId} ${currentId}`}
          aria-expanded={live ? open : undefined}
          className="inline-flex cursor-pointer list-none items-center gap-1.5 rounded-pill border border-line bg-surface py-1 pr-2.5 pl-3 text-text transition-colors hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary [&::-webkit-details-marker]:hidden"
        >
          <span id={currentId} lang={current.lang}>
            {current.name}
          </span>
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
            className={`text-text-2 transition-transform ${opens === 'up' ? 'rotate-180 group-open:rotate-0' : 'group-open:rotate-180'}`}
          >
            <path d="m6 9 6 6 6-6" />
          </svg>
        </summary>
        <ul
          className={
            'absolute z-30 m-0 flex w-max min-w-full max-w-[calc(100vw-3rem)] list-none flex-col gap-0.5 rounded-lg border border-line bg-surface p-1 shadow-lg ' +
            (opens === 'up' ? 'bottom-full mb-2 ' : 'top-full mt-2 ') +
            (align === 'end' ? 'right-0' : 'left-0')
          }
        >
          {links.map((link) => (
            <li key={link.lang}>
              <Link
                to={link.href}
                lang={link.lang}
                hrefLang={link.lang}
                aria-current={link.current ? 'page' : undefined}
                {...{ [LANGUAGE_CHOICE_ATTRIBUTE]: link.lang }}
                onClick={(e) => {
                  storeLanguageChoice(link.lang)
                  setOpen(false)
                  if (!link.inPlace) return
                  e.preventDefault()
                  void i18n.changeLanguage(link.lang)
                }}
                className={
                  'flex items-center justify-between gap-4 rounded-md px-3 py-2 transition-colors hover:bg-surface-2 hover:text-text focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary ' +
                  (link.current ? 'font-medium text-text' : 'text-text-2')
                }
              >
                {link.name}
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.25"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                  className={link.current ? 'text-primary' : 'invisible'}
                >
                  <path d="M20 6 9 17l-5-5" />
                </svg>
              </Link>
            </li>
          ))}
        </ul>
      </details>
    </nav>
  )
}

const noSubscription = () => () => {}
const isLive = () => true
const isStatic = () => false
