// Block 7 — Apps. Copy: web-about-tenshu-public.md §9.
//
// One carousel of every first-party app — the support hub's carousel (carousel/AppCarousel.tsx),
// here as a live React component with each app as a card: icon, name and one line beside the phone
// on wide screens (above it on narrow ones). No "Open X support" button: the card's name and the
// phone are the links, to wherever landingApps.ts landingAppHref says (support today, the app's own
// marketing page later).
//
// ⚠ No tiers and no status badges. The section used to sort apps into "shipping", "the rest" and
// "still being built", and had already undersold the project once by filing working apps under
// planned (apex-currency-sweep.md F5). Now the only status on the page is a quiet "Coming later"
// on the card of an app that is not usable yet (landingApps.ts COMING_LATER) — move an app out of
// that set the day it is usable, rather than softening its line.
//
// Which apps, their order, their captures and their links: carousel/landingApps.ts. Hardware (Hub,
// Mia speaker) lives in its own block (§10); Mia the assistant stays here because it is an app.
import { useTranslation } from 'react-i18next'
import { AppCarousel } from '../carousel/AppCarousel'
import type { CarouselEntry } from '../carousel/carouselModel'
import { LANDING_APPS, landingShots } from '../carousel/landingApps'
import { Section } from './Section'

export function Block7Apps() {
  const { t, i18n } = useTranslation('landing')
  const lang = (i18n.resolvedLanguage ?? i18n.language ?? 'en').split('-')[0]!
  const entries: CarouselEntry[] = LANDING_APPS.map((app) => {
    const name = t(`block7.apps.${app.id}.name`)
    return {
      id: app.id,
      name,
      href: app.href,
      icon: app.icon,
      tagline: t(`block7.apps.${app.id}.tagline`),
      note: app.comingLater ? t('block7.comingLater') : undefined,
      shots: landingShots(app.id, app.screens.android, lang, t('block7.screenshotAlt', { name }), undefined, 'android'),
      iosShots: landingShots(app.id, app.screens.ios, lang, t('block7.screenshotAlt', { name }), undefined, 'ios'),
    }
  })
  return (
    <Section id="apps" tone="tinted">
      {/* ⚠ appCarousel.css `.hub--cards` --stage-offset budgets this block's height (heading, one
          line of lede, the gap) so the section fits one screen on desktop: change both. The lede
          box is wider on desktop so the longest lede (fr) stays one line. */}
      <div className="mx-auto max-w-3xl text-center lg:max-w-4xl">
        <h2 className="text-3xl font-semibold tracking-tight">{t('block7.heading')}</h2>
        <p className="mt-6 text-lg text-text-2">{t('block7.lede')}</p>
      </div>
      <div className="mt-12 lg:mt-6">
        <AppCarousel
          mode="live"
          cards
          entries={entries}
          listLabel={t('block7.listLabel')}
          labels={{
            screenshot: (index, count, name) => t('block7.screenshotLabel', { index, count, name }),
            pause: t('block7.pauseShots'),
            play: t('block7.playShots'),
            show: (name) => t('block7.showApp', { name }),
            platform: {
              group: t('block7.device'),
              ios: t('block7.iphone'),
              android: t('block7.android'),
              onlyAndroid: t('block7.onlyAndroid'),
              onlyIos: t('block7.onlyIos'),
            },
          }}
          footnote={t('block7.frameNote')}
        />
      </div>
    </Section>
  )
}
