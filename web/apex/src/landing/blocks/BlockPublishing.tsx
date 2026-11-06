// Publishing block — "Publish anywhere, trusted everywhere." Sits right after the Developers
// block: that one is about building an app, this one about getting it to people. Together they
// follow the Vision page's order (Control and the IDL, then Tenshu Rei).
//
// ⚠ The App Console, the Tenshu Store and Tenshu Rei are whitepaper designs, not shipped, so the
// block carries an "In planning" badge and the copy stays in the future tense. Like the Vision
// page, it argues from structure (two stores) and never states a store owner's intent as fact.
import { useTranslation } from 'react-i18next'
import { Section } from './Section'
import { StatusBadge } from './Placeholders'

export function BlockPublishing() {
  const { t } = useTranslation('landing')
  return (
    <Section id="publishing">
      <div className="mx-auto max-w-3xl">
        <StatusBadge status="planning" />
        <h2 className="mt-4 text-3xl font-semibold tracking-tight">
          {t('publishing.heading')}
        </h2>
        <div className="mt-6 space-y-4 text-lg text-text-2">
          <p>{t('publishing.body1')}</p>
          <p>{t('publishing.body2')}</p>
        </div>
      </div>
    </Section>
  )
}
