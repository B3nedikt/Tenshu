// Founder block — "Who's building this." Copy: web-about-tenshu-public.md
// §10a. Sits between Hardware and Sign-up: by then the reader has the whole
// pitch, and a named person standing behind it is what makes the sign-up
// feel safe. First person on purpose — Tenshu is one person so far, and the
// block says so rather than hiding behind "we".
//
// Text-only, like Block 5: a candid statement reads as more honest than a
// stock portrait. A photo can slot in above the heading once one exists.
import { Trans, useTranslation } from 'react-i18next'
import { Section } from './Section'

const LINKS = [
  { id: 'github', href: 'https://github.com/B3nedikt' },
  { id: 'linkedin', href: 'https://www.linkedin.com/in/benedikt-l%C3%B6bbecke' },
  { id: 'contact', href: 'mailto:hello@tenshu.app' },
] as const

export function BlockFounder() {
  const { t } = useTranslation('landing')
  return (
    <Section id="founder">
      <div className="mx-auto max-w-3xl">
        <h2 className="text-3xl font-semibold tracking-tight">
          {t('founder.heading')}
        </h2>
        <div className="mt-6 space-y-4 text-lg text-text-2">
          <p>{t('founder.body1')}</p>
          <p>
            <Trans ns="landing" i18nKey="founder.body2" components={{ em: <em /> }} />
          </p>
          <p>{t('founder.body3')}</p>
          <p>{t('founder.body4')}</p>
          <p className="text-base">{t('founder.company')}</p>
        </div>
        <ul className="mt-8 flex list-none flex-wrap gap-6 p-0">
          {LINKS.map(link => (
            <li key={link.id}>
              <a
                href={link.href}
                rel="me noopener"
                className="font-medium text-text underline-offset-4 hover:underline"
              >
                {t(`founder.link.${link.id}`)}
              </a>
            </li>
          ))}
        </ul>
      </div>
    </Section>
  )
}
