// Block 3 — Tenshu's Answer. Contrast illustration to Block 2:
// phone with two apps, the proxy mediating between them and the
// relay (which only ever sees encrypted data), and a laptop on the
// other end with the same setup. Headline echoes the bridge label
// from Block 2 ("How it should be"). Bridges down to Block 4 via a
// bare chevron.
//
// Full-screen page (same shape as Block 2 and the hero): illustration
// on top, headline + copy centred, chevron pinned to the bottom.
import { useTranslation } from 'react-i18next'
import { AnswerArt } from '../illustrations/AnswerArt'
import { NextSectionLink, Section } from './Section'

export function Block3Answer() {
  const { t } = useTranslation('landing')
  return (
    <Section id="answer" screen>
      <div className="flex flex-1 flex-col items-center justify-center text-center">
        <div className="w-full max-w-2xl md:max-w-3xl">
          <AnswerArt />
        </div>
        <h2 className="mt-10 text-3xl font-semibold tracking-tight sm:text-4xl">
          {t('block3.heading')}
        </h2>
        <p className="mx-auto mt-6 max-w-4xl text-lg text-text-2">
          {t('block3.body')}
        </p>
      </div>
      <div className="flex justify-center pt-10">
        <NextSectionLink to="#realm" ariaLabel={t('block3.bridgeAriaLabel')} />
      </div>
    </Section>
  )
}
