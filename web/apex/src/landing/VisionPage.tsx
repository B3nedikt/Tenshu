// The Vision page (/vision): why Tenshu exists, in one sentence, then the four places today's app
// ecosystems fall short, the three technologies Tenshu answers them with (each a name, a tagline
// and a short paragraph), and a one-line closing. Centred throughout, so it reads as a statement
// rather than an essay. Linked from the marketing header. Copy: `vision.*` in landing.json.
//
// ⚖️ The problem paragraphs were softened on purpose (2026-10-22): they argue from structure (how
// much power sits with how few stores) and never state a company's intent as fact or name one.
// Keep it that way when editing. Tenshu Rei is "designed to", because it is not built yet.
//
// A public page: it wears MarketingLayout and must never start the runtime (App.tsx).
import { useLayoutEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { MarketingLayout } from './MarketingLayout'

const PROBLEMS = ['privacy', 'security', 'interoperability', 'sovereignty'] as const
const TECHNOLOGIES = ['control', 'idl', 'rei'] as const

/** A short centred rule between the page's three parts. */
function Divider() {
  return <hr aria-hidden="true" className="mx-auto my-16 w-24 border-0 border-t border-line" />
}

export function VisionPage() {
  const { t } = useTranslation('landing')
  // A header link from halfway down the landing page would otherwise open this page mid-scroll.
  useLayoutEffect(() => {
    window.scrollTo(0, 0)
  }, [])
  return (
    <MarketingLayout current="vision">
      <main className="mx-auto w-full max-w-2xl px-6 py-16 text-center sm:py-24">
        <h1 className="text-4xl font-semibold tracking-tight sm:text-5xl">{t('vision.heading')}</h1>
        <p className="mt-8 text-lg text-text-2">{t('vision.intro')}</p>

        <div className="mt-12 space-y-10">
          {PROBLEMS.map((id) => (
            <section key={id} aria-labelledby={`vision-${id}`}>
              <h2 id={`vision-${id}`} className="text-2xl font-semibold tracking-tight">
                {t(`vision.problems.${id}.name`)}
              </h2>
              <p className="mt-3 text-lg text-text-2">{t(`vision.problems.${id}.body`)}</p>
            </section>
          ))}
        </div>

        <Divider />

        <section aria-labelledby="vision-tech">
          <h2 id="vision-tech" className="text-3xl font-semibold tracking-tight">
            {t('vision.tech.heading')}
          </h2>
          <p className="mt-6 text-lg text-text-2">{t('vision.tech.lede')}</p>
          <ul className="mt-12 list-none space-y-12 p-0">
            {TECHNOLOGIES.map((id) => (
              <li key={id}>
                <h3 className="text-2xl font-semibold tracking-tight">{t(`vision.tech.${id}.name`)}</h3>
                <p className="mt-1 text-lg font-medium text-text">{t(`vision.tech.${id}.tagline`)}</p>
                <p className="mt-3 text-lg text-text-2">{t(`vision.tech.${id}.body`)}</p>
              </li>
            ))}
          </ul>
        </section>

        <Divider />

        <section aria-labelledby="vision-closing">
          <h2 id="vision-closing" className="text-3xl font-semibold tracking-tight">
            {t('vision.closing.heading')}
          </h2>
          <p className="mt-6 text-lg text-text-2">{t('vision.closing.body')}</p>
        </section>
      </main>
    </MarketingLayout>
  )
}
