// Block 4 — Your Realm. Copy: web-about-tenshu-public.md §6.
// Merges the former "End-to-End Encrypted, Plainly" + "Realm Devices"
// blocks: the realm is the core concept (your data, photos, devices,
// and what you own), it's E2EE across your devices, and devices come
// and go. The honest-about-limits material that used to live here as
// an inline note is now its own block (Block 5 — see §7); keep this
// one free of relay-leakage detail by design.
//
// Visual: the "realm ring" (RealmArt) per §6 — devices of different
// kinds in a ring around the Tenshu cat (the cat *is* the realm), each
// showing the same readable item. Deliberately no relay (RealmArt.tsx).
//
// Layout mirrors Block 1 (the hero): a two-column grid — copy beside
// the illustration on desktop, illustration below the copy on mobile
// (text column first in source order). Keeps the realm ring compact
// rather than a full-width band.
import { Trans, useTranslation } from 'react-i18next'
import { RealmArt } from '../illustrations/RealmArt'
import { Section } from './Section'

export function Block4Realm() {
  const { t } = useTranslation('landing')
  return (
    <Section id="realm" tone="tinted">
      <div className="grid items-center gap-10 md:grid-cols-2">
        <div>
          {/* Two-level headline: "Your realm." reads as the named
              concept (the Tenshu term) and the smaller line below
              describes what the concept is. Body unpacks. */}
          <h2 className="text-4xl font-semibold tracking-tight sm:text-5xl">
            {t('block4.heading')}
          </h2>
          <p className="mt-3 text-xl text-text-2 sm:text-2xl">
            {t('block4.subhead')}
          </p>
          <div className="mt-8 space-y-4 text-lg text-text-2">
            <p>
              <Trans
                ns="landing"
                i18nKey="block4.body1"
                components={{ em: <em /> }}
              />
            </p>
            <p>{t('block4.body2')}</p>
            <p>{t('block4.body3')}</p>
          </div>
        </div>
        <RealmArt />
      </div>
    </Section>
  )
}
