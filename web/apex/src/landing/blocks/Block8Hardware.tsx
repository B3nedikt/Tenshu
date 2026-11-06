// Block 8 — Hardware. Copy: web-about-tenshu-public.md §10. Tenshu
// isn't only software: two devices are in planning. The Hub runs a
// Tenshu proxy for ambient compute at home; the Mia speaker is the
// Bluetooth speaker that gives the Mia assistant (an app — see §9) a
// voice in the room. Both are text + placeholder photos for now, each
// carrying an "In planning" badge so the roadmap stays honest.
import { useTranslation } from 'react-i18next'
import { Section } from './Section'
import { ScreenshotPlaceholder, StatusBadge } from './Placeholders'

// Data-driven copy pattern (ADR 0016 / agent.md §19): static array
// carries IDs only; strings come from the catalogue at render time via
// `t('block8.items.<id>.{name,blurb}')`.
type HardwareId = 'hub' | 'miaSpeaker'

const HARDWARE: readonly HardwareId[] = ['hub', 'miaSpeaker']

export function Block8Hardware() {
  const { t } = useTranslation('landing')
  return (
    <Section id="hardware">
      <div className="mx-auto max-w-3xl text-center">
        <h2 className="text-3xl font-semibold tracking-tight">{t('block8.heading')}</h2>
        <p className="mt-6 text-lg text-text-2">{t('block8.lede')}</p>
      </div>
      <div className="mx-auto mt-12 grid max-w-4xl gap-6 md:grid-cols-2">
        {HARDWARE.map(id => (
          <HardwareCard key={id} id={id} />
        ))}
      </div>
    </Section>
  )
}

function HardwareCard({ id }: { readonly id: HardwareId }) {
  const { t } = useTranslation('landing')
  const name = t(`block8.items.${id}.name`)
  return (
    <div className="flex flex-col rounded-2xl border border-line bg-surface p-6">
      <ScreenshotPlaceholder label={name} aspectClassName="aspect-video" />
      <h3 className="mt-5 text-lg font-semibold text-text">{name}</h3>
      <p className="mt-2 flex-1 text-sm text-text-2">
        {t(`block8.items.${id}.blurb`)}
      </p>
      <div className="mt-4">
        <StatusBadge status="planning" />
      </div>
    </div>
  )
}
