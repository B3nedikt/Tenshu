// Developers block — "Build private apps in months, not years." Copy:
// web-about-tenshu-public.md §9a. The SDK pitch plus the one piece of
// reasoning a developer needs: a single schema language describes data,
// services, permissions and the wire format, so a granted permission is
// exactly what the code can do.
//
// ⚠ The SDKs are not shipped, so the block carries an "In planning" badge
// and the copy stays in the future tense. Move it to "In progress" only
// once there is something a developer can install.
import { useTranslation } from 'react-i18next'
import { Section } from './Section'
import { StatusBadge } from './Placeholders'

export function BlockDevelopers() {
  const { t } = useTranslation('landing')
  return (
    <Section id="developers">
      <div className="mx-auto max-w-3xl">
        <StatusBadge status="planning" />
        <h2 className="mt-4 text-3xl font-semibold tracking-tight">
          {t('developers.heading')}
        </h2>
        <div className="mt-6 space-y-4 text-lg text-text-2">
          <p>{t('developers.body1')}</p>
          <p>{t('developers.body2')}</p>
        </div>
      </div>
    </Section>
  )
}
