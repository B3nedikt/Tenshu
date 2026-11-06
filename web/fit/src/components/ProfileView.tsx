// Profile (native Profile tab): goals, personal data, units and the data sources. Every edit is one
// SDK write within the SDK's bounds (`lib/bounds.ts`); the pushed profile / goals streams update the
// screen, so nothing here refetches after a write.
//
// ⚠ Every field but units can be unset, and reads "Not set" — never a placeholder value.
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { FitGoals, FitProfile, FitSex, FitUnits } from '@tenshu/sdk/fit'
import { EARLIEST_BIRTH_DATE, GOAL_ACTIVE_MINUTES, GOAL_STEPS, MAX_HEIGHT_CM, MAX_WEIGHT_KG } from '../lib/bounds'
import { localToday } from '../lib/dates'
import { cmToFeetInches, dayText, feetInchesToCm, heightText, kgToPounds, minutesText, numberText, poundsToKg, weightText } from '../lib/format'
import { useGoals, useProfile } from '../lib/live'
import { attempt, type Outcome } from '../lib/outcome'
import { recordHeight, recordWeight, setBirthDate, setGoals, setName, setSex, setUnits } from '../lib/proxy'
import { useUnits } from '../lib/units'
import { DataSourcesView } from './DataSourcesView'
import { Button, Field, FormDialog, Loading, OutcomeText, ReadFailure, Section, Stopped } from './ui'

type Editing = 'goals' | 'name' | 'birthDate' | 'sex' | 'height' | 'weight' | null

export function ProfileView() {
  const { t, i18n } = useTranslation()
  const profile = useProfile()
  const goals = useGoals()
  const [editing, setEditing] = useState<Editing>(null)
  const close = () => setEditing(null)

  return (
    <div>
      <Stopped live={profile} />
      <Stopped live={goals} />

      <Section title={t('goals.title')}>
        {goals.isPending ? (
          <Loading />
        ) : goals.error || !goals.data ? (
          <ReadFailure error={goals.error} onRetry={goals.retry} />
        ) : !goals.data.ok ? (
          <ReadFailure failure={goals.data.error} onRetry={goals.retry} />
        ) : (
          <>
            <Rows>
              <Row label={t('goals.activeMinutes')} value={minutesText(t, goals.data.goals.activeMinutes)} />
              <Row label={t('goals.steps')} value={numberText(i18n.language, goals.data.goals.steps)} />
            </Rows>
            <Button className="mt-2" size="sm" variant="outline" onClick={() => setEditing('goals')}>
              {t('goals.edit')}
            </Button>
            {editing === 'goals' && <GoalsDialog goals={goals.data.goals} onClose={close} />}
          </>
        )}
      </Section>

      <Section title={t('profile.title')}>
        {profile.isPending ? (
          <Loading />
        ) : profile.error || !profile.data ? (
          <ReadFailure error={profile.error} onRetry={profile.retry} />
        ) : !profile.data.ok ? (
          <ReadFailure failure={profile.data.error} onRetry={profile.retry} />
        ) : (
          <ProfileRows profile={profile.data.profile} editing={editing} setEditing={setEditing} close={close} />
        )}
      </Section>

      <Section title={t('sources.title')}>
        <DataSourcesView />
      </Section>
    </div>
  )
}

function ProfileRows({
  profile,
  editing,
  setEditing,
  close,
}: {
  profile: FitProfile
  editing: Editing
  setEditing: (e: Editing) => void
  close: () => void
}) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const notSet = t('profile.notSet')
  return (
    <>
      <Rows>
        <Row label={t('profile.name')} value={profile.name ?? notSet} onEdit={() => setEditing('name')} />
        <Row
          label={t('profile.birthDate')}
          value={profile.birthDate ? dayText(i18n.language, profile.birthDate) : notSet}
          onEdit={() => setEditing('birthDate')}
        />
        <Row label={t('profile.sex')} value={profile.sex ? t(`profile.sexes.${profile.sex}`) : notSet} onEdit={() => setEditing('sex')} />
        <Row
          label={t('profile.height')}
          value={profile.heightCm !== null ? heightText(t, profile.heightCm, units) : notSet}
          onEdit={() => setEditing('height')}
        />
        <Row
          label={t('profile.weight')}
          value={profile.weightKg !== null ? weightText(t, profile.weightKg, units) : notSet}
          onEdit={() => setEditing('weight')}
        />
      </Rows>
      <UnitsSwitch units={profile.units} />
      {editing === 'name' && <NameDialog name={profile.name ?? ''} onClose={close} />}
      {editing === 'birthDate' && <BirthDateDialog date={profile.birthDate ?? ''} onClose={close} />}
      {editing === 'sex' && <SexDialog sex={profile.sex} onClose={close} />}
      {editing === 'height' && <HeightDialog cm={profile.heightCm} onClose={close} />}
      {editing === 'weight' && <WeightDialog kg={profile.weightKg} onClose={close} />}
    </>
  )
}

function Rows({ children }: { children: ReactNode }) {
  return <dl className="divide-y divide-line rounded-lg border border-line">{children}</dl>
}

function Row({ label, value, onEdit }: { label: string; value: string; onEdit?: () => void }) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center justify-between gap-3 px-4 py-2.5">
      <dt className="text-sm text-text-2">{label}</dt>
      <dd className="flex items-center gap-2 text-sm text-text">
        <span>{value}</span>
        {onEdit && (
          <Button size="sm" variant="ghost" aria-label={t('common.editField', { field: label })} onClick={onEdit}>
            {t('common.edit')}
          </Button>
        )}
      </dd>
    </div>
  )
}

// ── units ────────────────────────────────────────────────────────────────────

function UnitsSwitch({ units }: { units: FitUnits }) {
  const { t } = useTranslation()
  const [outcome, setOutcome] = useState<Outcome>(null)
  return (
    <div className="mt-3">
      <div role="group" aria-label={t('profile.units')} className="flex items-center gap-2">
        <span className="text-sm text-text-2">{t('profile.units')}</span>
        {(['metric', 'imperial'] as const).map((u) => (
          <Button
            key={u}
            size="sm"
            variant={u === units ? 'primary' : 'outline'}
            aria-pressed={u === units}
            onClick={async () => setOutcome(u === units ? null : await attempt(() => setUnits(u)))}
          >
            {t(`profile.unitNames.${u}`)}
          </Button>
        ))}
      </div>
      <OutcomeText outcome={outcome} />
    </div>
  )
}

// ── the edit dialogs ─────────────────────────────────────────────────────────

function integerIn(raw: string, min: number, max: number): number | null {
  if (!/^\d+$/.test(raw.trim())) return null
  const v = Number(raw)
  return v >= min && v <= max ? v : null
}

function GoalsDialog({ goals, onClose }: { goals: FitGoals; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const [minutes, setMinutes] = useState(String(goals.activeMinutes))
  const [steps, setSteps] = useState(String(goals.steps))
  const m = integerIn(minutes, GOAL_ACTIVE_MINUTES.min, GOAL_ACTIVE_MINUTES.max)
  const s = integerIn(steps, GOAL_STEPS.min, GOAL_STEPS.max)
  const fmt = (v: number) => numberText(i18n.language, v)
  const invalid =
    m === null
      ? t('bounds.activeMinutes', { min: GOAL_ACTIVE_MINUTES.min, max: GOAL_ACTIVE_MINUTES.max })
      : s === null
        ? t('bounds.steps', { min: fmt(GOAL_STEPS.min), max: fmt(GOAL_STEPS.max) })
        : null
  return (
    <FormDialog title={t('goals.edit')} submitLabel={t('common.save')} invalid={invalid} onClose={onClose} onSubmit={() => attempt(() => setGoals(m!, s!))}>
      <Field
        label={t('goals.activeMinutes')}
        type="number"
        inputMode="numeric"
        min={GOAL_ACTIVE_MINUTES.min}
        max={GOAL_ACTIVE_MINUTES.max}
        value={minutes}
        onChange={(e) => setMinutes(e.target.value)}
      />
      <Field
        label={t('goals.steps')}
        type="number"
        inputMode="numeric"
        min={GOAL_STEPS.min}
        max={GOAL_STEPS.max}
        step={100}
        value={steps}
        onChange={(e) => setSteps(e.target.value)}
      />
    </FormDialog>
  )
}

function NameDialog({ name, onClose }: { name: string; onClose: () => void }) {
  const { t } = useTranslation()
  const [value, setValue] = useState(name)
  return (
    <FormDialog title={t('profile.name')} submitLabel={t('common.save')} invalid={null} onClose={onClose} onSubmit={() => attempt(() => setName(value.trim()))}>
      <Field autoFocus label={t('profile.name')} value={value} onChange={(e) => setValue(e.target.value)} />
    </FormDialog>
  )
}

function BirthDateDialog({ date, onClose }: { date: string; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const [value, setValue] = useState(date)
  const today = localToday()
  const valid = /^\d{4}-\d{2}-\d{2}$/.test(value) && value >= EARLIEST_BIRTH_DATE && value <= today
  const invalid = valid ? null : t('bounds.birthDate', { min: dayText(i18n.language, EARLIEST_BIRTH_DATE), max: dayText(i18n.language, today) })
  return (
    <FormDialog title={t('profile.birthDate')} submitLabel={t('common.save')} invalid={invalid} onClose={onClose} onSubmit={() => attempt(() => setBirthDate(value))}>
      <Field label={t('profile.birthDate')} type="date" min={EARLIEST_BIRTH_DATE} max={today} value={value} onChange={(e) => setValue(e.target.value)} />
    </FormDialog>
  )
}

function SexDialog({ sex, onClose }: { sex: FitSex | null; onClose: () => void }) {
  const { t } = useTranslation()
  const [value, setValue] = useState<FitSex | ''>(sex ?? '')
  return (
    <FormDialog
      title={t('profile.sex')}
      submitLabel={t('common.save')}
      invalid={value === '' ? t('bounds.sex') : null}
      onClose={onClose}
      onSubmit={() => attempt(() => setSex(value as FitSex))}
    >
      <label className="block text-sm">
        <span className="text-text-2">{t('profile.sex')}</span>
        <select
          className="mt-1 block w-full rounded-md border border-line bg-surface px-3 py-2 text-text"
          value={value}
          onChange={(e) => setValue(e.target.value as FitSex | '')}
        >
          <option value="">{t('profile.notSet')}</option>
          {(['female', 'male', 'other'] as const).map((s) => (
            <option key={s} value={s}>
              {t(`profile.sexes.${s}`)}
            </option>
          ))}
        </select>
      </label>
    </FormDialog>
  )
}

function HeightDialog({ cm, onClose }: { cm: number | null; onClose: () => void }) {
  const { t } = useTranslation()
  const units = useUnits()
  const initial = cm !== null ? cmToFeetInches(cm) : null
  const shown = {
    metric: cm !== null ? String(Math.round(cm)) : '',
    feet: initial ? String(initial.feet) : '',
    inches: initial ? String(initial.inches) : '',
  }
  const [metric, setMetric] = useState(shown.metric)
  const [feet, setFeet] = useState(shown.feet)
  const [inches, setInches] = useState(shown.inches)
  // ⚠ cm → whole inches → cm is lossy (179 → 5'10" → 178): an untouched height is never sent.
  const unchanged =
    cm !== null && (units === 'imperial' ? feet === shown.feet && inches === shown.inches : metric === shown.metric)
  // ⚠ The wire takes whole centimetres in 1..MAX_HEIGHT_CM, whatever the units on screen.
  const entered =
    units === 'imperial'
      ? /^\d+$/.test(feet) && /^\d*$/.test(inches) && Number(inches || 0) < 12
        ? feetInchesToCm(Number(feet), Number(inches || 0))
        : null
      : /^\d+$/.test(metric.trim())
        ? Number(metric)
        : null
  const valid = entered !== null && entered >= 1 && entered <= MAX_HEIGHT_CM
  const invalid = valid
    ? null
    : units === 'imperial'
      ? t('bounds.heightImperial', { max: t('height.ftIn', cmToFeetInches(MAX_HEIGHT_CM)) })
      : t('bounds.heightMetric', { max: MAX_HEIGHT_CM })
  return (
    <FormDialog title={t('profile.height')} submitLabel={t('common.save')} invalid={invalid} unchanged={unchanged} onClose={onClose} onSubmit={() => attempt(() => recordHeight(entered!))}>
      {units === 'imperial' ? (
        <div className="flex gap-2">
          <Field label={t('profile.feet')} type="number" inputMode="numeric" min={0} value={feet} onChange={(e) => setFeet(e.target.value)} />
          <Field label={t('profile.inches')} type="number" inputMode="numeric" min={0} max={11} value={inches} onChange={(e) => setInches(e.target.value)} />
        </div>
      ) : (
        <Field label={t('profile.centimetres')} type="number" inputMode="numeric" min={1} max={MAX_HEIGHT_CM} value={metric} onChange={(e) => setMetric(e.target.value)} />
      )}
    </FormDialog>
  )
}

function WeightDialog({ kg, onClose }: { kg: number | null; onClose: () => void }) {
  const { t } = useTranslation()
  const units = useUnits()
  const shown = kg === null ? '' : String(Math.round((units === 'imperial' ? kgToPounds(kg) : kg) * 10) / 10)
  const [value, setValue] = useState(shown)
  // ⚠ kg → lb at 0.1 → kg drifts (70 → 154.3 → 69.99): an untouched weight is never sent.
  const unchanged = kg !== null && value === shown
  const parsed = /^\d+([.,]\d+)?$/.test(value.trim()) ? Number(value.trim().replace(',', '.')) : null
  const kilograms = parsed === null ? null : units === 'imperial' ? poundsToKg(parsed) : parsed
  // ⚠ Above 0 and at most MAX_WEIGHT_KG, in kilograms on the wire.
  const valid = kilograms !== null && kilograms > 0 && kilograms <= MAX_WEIGHT_KG
  const invalid = valid
    ? null
    : units === 'imperial'
      ? t('bounds.weightImperial', { max: Math.floor(kgToPounds(MAX_WEIGHT_KG)) })
      : t('bounds.weightMetric', { max: MAX_WEIGHT_KG })
  return (
    <FormDialog title={t('profile.weight')} submitLabel={t('common.save')} invalid={invalid} unchanged={unchanged} onClose={onClose} onSubmit={() => attempt(() => recordWeight(kilograms!))}>
      <Field
        label={units === 'imperial' ? t('profile.pounds') : t('profile.kilograms')}
        inputMode="decimal"
        value={value}
        onChange={(e) => setValue(e.target.value)}
      />
    </FormDialog>
  )
}
