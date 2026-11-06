// Block 9 — Sign-up + FAQ. Copy: web-about-tenshu-public.md §11.
// FAQ collapses by default per source doc + web-apex-landing.md §3.
import { Trans, useTranslation } from 'react-i18next'
import { LandingCta } from '../LandingCta'
import { PRICING_HREF, ROADMAP_HREF } from '../marketingNav'
import { Section } from './Section'

// Data-driven FAQ (ADR 0016 / agent.md §19): array carries IDs +
// per-entry inline-element components only. Question text and answer
// copy live in `landing.json` under `block9.faq.<id>.*`; entries with
// inline elements (`<em>`, `<a>`) declare a `components` map that
// `<Trans>` consumes at render time.
type FaqId = 'free' | 'shutdown' | 'openSource' | 'compare' | 'migration' | 'learnMore'

const FAQ: readonly FaqId[] = [
  'free',
  'shutdown',
  'openSource',
  'compare',
  'migration',
  'learnMore',
]

// Link styles reused by FAQ answers. `inlineLink` matches the older
// inline-link look (no font weight bump); `boldLink` matches the
// stronger threat-model link used in Block 5 and the openSource answer.
const inlineLinkClass = 'underline-offset-4 hover:underline'
const threatModelLinkClass = 'underline-offset-4 hover:underline'

export function Block9Signup() {
  const { t } = useTranslation('landing')
  return (
    <Section id="signup" tone="tinted">
      <div className="rounded-2xl border border-line bg-surface p-8 shadow-sm">
        <h2 className="text-3xl font-semibold tracking-tight">{t('block9.heading')}</h2>
        <p className="mt-3 text-base text-text-2">{t('block9.lede')}</p>
        <div className="mt-6">
          <LandingCta slot="signup" />
        </div>
      </div>
      <h3 className="mt-12 text-xl font-semibold tracking-tight">{t('block9.faqHeading')}</h3>
      <ul className="mt-4 divide-y divide-line rounded-lg border border-line bg-surface">
        {FAQ.map(id => (
          <li key={id}>
            <details className="group px-5 py-4">
              <summary className="flex cursor-pointer list-none items-center justify-between text-base font-medium text-text">
                <span>{t(`block9.faq.${id}.question`)}</span>
                <span
                  aria-hidden="true"
                  className="ml-4 text-text-3 group-open:rotate-180 transition-transform"
                >
                  ▾
                </span>
              </summary>
              <div className="mt-3 text-sm text-text-2">
                <FaqAnswer id={id} />
              </div>
            </details>
          </li>
        ))}
      </ul>
    </Section>
  )
}

// One <Trans> per entry; the components map carries the inline elements
// for that specific answer. Plain-text answers (`compare`,
// `migration`) still go through <Trans> for consistency — i18next
// renders them as plain text when the catalogue value has no tags.
function FaqAnswer({ id }: { readonly id: FaqId }) {
  if (id === 'free') {
    return (
      <Trans
        ns="landing"
        i18nKey="block9.faq.free.answer"
        components={{ pricing: <a href={PRICING_HREF} className={inlineLinkClass} /> }}
      />
    )
  }
  if (id === 'shutdown') {
    return (
      <Trans
        ns="landing"
        i18nKey="block9.faq.shutdown.answer"
        components={{ em: <em /> }}
      />
    )
  }
  if (id === 'openSource') {
    return (
      <Trans
        ns="landing"
        i18nKey="block9.faq.openSource.answer"
        components={{
          threatModel: (
            <a
              href="https://github.com/B3nedikt/Tenshu/blob/master/docs/sync/threat-model.md"
              className={threatModelLinkClass}
            />
          ),
        }}
      />
    )
  }
  if (id === 'learnMore') {
    return (
      <Trans
        ns="landing"
        i18nKey="block9.faq.learnMore.answer"
        components={{
          whitepaper: (
            <a
              href="https://github.com/B3nedikt/Tenshu/blob/master/docs/whitepaper.adoc"
              className={inlineLinkClass}
            />
          ),
          architecture: (
            <a
              href="https://github.com/B3nedikt/Tenshu/blob/master/docs/web/architecture.md"
              className={inlineLinkClass}
            />
          ),
          threatModel: (
            <a
              href="https://github.com/B3nedikt/Tenshu/blob/master/docs/sync/threat-model.md"
              className={inlineLinkClass}
            />
          ),
          github: (
            <a href="https://github.com/B3nedikt/Tenshu" className={inlineLinkClass} />
          ),
          roadmap: (
            <a href={ROADMAP_HREF} className={inlineLinkClass} />
          ),
        }}
      />
    )
  }
  return <Trans ns="landing" i18nKey={`block9.faq.${id}.answer`} />
}
