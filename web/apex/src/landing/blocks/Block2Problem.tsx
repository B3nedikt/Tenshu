// Block 2 — The Problem. Illustration-led emotional opener: phone
// sending data up to a cloud server, PC reading it on the other end.
// Short, blunt copy; the visual carries the gut-punch. Bridges to
// Block 3 ("How it should be") via a labeled chevron.
//
// Full-screen page (same shape as the hero): illustration on top,
// headline + copy centred vertically, the bridge chevron pinned to the
// bottom of the viewport.
import { useTranslation } from 'react-i18next'
import { ProblemArt } from '../illustrations/ProblemArt'
import { NextSectionLink, Section } from './Section'

export function Block2Problem() {
  const { t } = useTranslation('landing')
  return (
    <Section id="problem" tone="tinted" screen>
      <div className="flex flex-1 flex-col items-center justify-center text-center">
        <div className="w-full max-w-2xl md:max-w-3xl">
          <ProblemArt />
        </div>
        <h2 className="mt-10 text-3xl font-semibold tracking-tight sm:text-4xl">
          {t('block2.heading')}
        </h2>
        <p className="mx-auto mt-6 max-w-4xl text-lg text-text-2">
          {t('block2.body1')}
        </p>
        <p className="mx-auto mt-6 max-w-4xl text-lg text-text-2">
          {t('block2.body2')}
        </p>
      </div>
      <div className="flex justify-center pt-10">
        <NextSectionLink to="#answer" label={t('block2.bridge')} />
      </div>
    </Section>
  )
}
