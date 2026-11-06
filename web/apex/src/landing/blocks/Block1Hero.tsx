// Block 1 — Hero. Full-viewport centered opener (mirrors the centered
// structure of the Problem/Answer sections): a larger M1 mockup on top,
// then headline + subhead, then the large smart CTA. The "Learn more" +
// chevron is pinned to the bottom of the screen as the dominant
// invitation to keep scrolling — anchors to the next section (#problem).
import { useTranslation } from 'react-i18next'
import { LandingCta } from '../LandingCta'
import { M1Hero } from '../illustrations/M1Hero'
import { NextSectionLink, Section } from './Section'

export function Block1Hero() {
  const { t } = useTranslation('landing')
  return (
    <Section id="hero" screen>
      <div className="flex flex-1 flex-col items-center justify-center text-center">
        <div className="w-full max-w-2xl md:max-w-4xl">
          <M1Hero />
        </div>
        <h1 className="mt-10 text-4xl font-semibold tracking-tight sm:text-5xl">
          {t('block1.heading')}
        </h1>
        <p className="mx-auto mt-6 max-w-2xl text-lg text-text-2">
          {t('block1.lede')} <br />
          {t('block1.ledeLine2')}
        </p>
        <div className="mt-10">
          <LandingCta slot="hero" />
        </div>
      </div>
      <div className="flex justify-center pt-10">
        <NextSectionLink to="#problem" label={t('block1.learnMore')} />
      </div>
    </Section>
  )
}
