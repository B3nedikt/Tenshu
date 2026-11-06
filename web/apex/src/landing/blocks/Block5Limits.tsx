// Block 5 — Honest About the Limits. Copy: web-about-tenshu-public.md
// §7. This block carries the trust beat the tone guide (§12) calls
// load-bearing — formerly an inline note inside the encryption block,
// now its own section so it isn't a footnote. Framed in the
// whitepaper's voice: the four Data Privacy & Security Design
// Principles, especially Zero-Trust Backend ("a breached Tenshu
// service yields only ciphertext and timing metadata, never plaintext
// or long-term keys … a non-trust-bearing convenience layer").
//
// Intentionally text-only: a candid, typographic statement reads as
// more honest than a diagram, and any "what the relay sees" graphic
// risks contradicting the message (showing perfect protection) or
// re-introducing the technical detail the narrative trimmed. The
// "relay's-eye view" concept is parked as the future visual — see
// web-about-tenshu-public.md §7.
import { Trans, useTranslation } from 'react-i18next'
import { Section } from './Section'

export function Block5Limits() {
  const { t } = useTranslation('landing')
  return (
    <Section id="limits">
      <div className="mx-auto max-w-3xl">
        <h2 className="text-3xl font-semibold tracking-tight">
          {t('block5.heading')}
        </h2>
        <div className="mt-6 space-y-4 text-lg text-text-2">
          <p>
            <Trans
              ns="landing"
              i18nKey="block5.body1"
              components={{ em: <em /> }}
            />
          </p>
          <p>{t('block5.body2')}</p>
          <p>
            <Trans
              ns="landing"
              i18nKey="block5.body3"
              components={{
                threatModel: (
                  <a
                    href="https://github.com/B3nedikt/Tenshu/blob/master/docs/sync/threat-model.md"
                    className="font-medium text-text underline-offset-4 hover:underline"
                  />
                ),
              }}
            />
          </p>
        </div>
      </div>
    </Section>
  )
}
