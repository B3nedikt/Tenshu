// Block 1 — Hero. Full-viewport centered opener: headline + subhead, then the five apps as a row of
// icons over a laptop and a phone that switch apps together (HeroShowcase,
// docs/proposals/store-screenshot-stories.md §5), then the large smart CTA. The "Learn more" +
// chevron is pinned to the bottom of the screen as the dominant invitation to keep scrolling —
// anchors to the next section (#problem).
//
// The devices-around-the-relay drawing this block used to open with (illustrations/M1Hero.tsx) is
// no longer on the page: the hero now shows the apps themselves, and Block 3 has its own drawing of
// the same idea (AnswerArt).
import { useTranslation } from 'react-i18next'
import { LandingCta } from '../LandingCta'
import { HeroShowcase } from '../hero/HeroShowcase'
import { NextSectionLink, Section } from './Section'

export function Block1Hero() {
  const { t } = useTranslation('landing')
  return (
    <Section id="hero" screen>
      <div className="flex flex-1 flex-col items-center justify-center text-center">
        <h1 className="text-4xl font-semibold tracking-tight sm:text-5xl">{t('block1.heading')}</h1>
        <p className="mx-auto mt-5 max-w-2xl text-lg text-text-2">
          {t('block1.lede')} <br />
          {t('block1.ledeLine2')}
        </p>
        <div className="mt-6 w-full">
          <HeroShowcase />
        </div>
        <div className="mt-7">
          <LandingCta slot="hero" />
        </div>
      </div>
      <div className="flex justify-center pt-10">
        <NextSectionLink to="#problem" label={t('block1.learnMore')} />
      </div>
    </Section>
  )
}
