// The Automations list, as the iOS read-only tab shows it (`home-ios` `AutomationsView.swift`, over
// `SyncedAutomationsRepository` + `AutomationProgramMapper.toAutomation`).
//
// ⚠⚠ Run A lists EVERY routine (web-app-suite.md §4.H): device-event, geofence and voice triggers, and
// unparseable programs with `program: null`. Native maps each routine onto the M2 editor's model and
// DROPS what that model cannot represent: a program it cannot parse (`programStatus` other than
// 'parsed'), and any trigger but a time of day or the sun. This list mirrors native exactly, so the
// web never shows a routine the phone does not (`visibleAutomations`, pinned by `automations.test.ts`).
//
// The row: name, "trigger · first action", and an On/Off LABEL (never a switch). ⚠ Native shows no
// last-run time, no suspension state and no conditions in the list, and neither does this.
import type { TFunction } from 'i18next'
import type { HomeAction, HomeAutomation, HomeDevice, HomeProgram, HomeTrigger } from '@tenshu/sdk/home'

// The trigger types native's editor model represents (`AutomationProgramMapper.toAutomation`).
const LISTED_TRIGGERS: ReadonlySet<HomeTrigger['type']> = new Set(['timeOfDay', 'sun'])

export type ListedAutomation = HomeAutomation & { readonly program: HomeProgram }

export function visibleAutomations(all: readonly HomeAutomation[]): ListedAutomation[] {
  return all.filter(
    (a): a is ListedAutomation => a.programStatus === 'parsed' && a.program !== null && LISTED_TRIGGERS.has(a.program.trigger.type),
  )
}

// `formatMinutesOfDay`: "22:30", "8:05" (no zero-padded hour, as native).
export function minutesOfDayText(minutesOfDay: number): string {
  const clamped = (((minutesOfDay % 1440) + 1440) % 1440)
  const h = Math.floor(clamped / 60)
  const m = clamped % 60
  return `${h}:${String(m).padStart(2, '0')}`
}

// "At 22:30 · Turn on Sofa lamp" — iOS's `AutomationsModel.summary`.
export function automationSummary(t: TFunction, automation: ListedAutomation, devices: readonly HomeDevice[]): string {
  const deviceName = (id: string) => devices.find((d) => d.deviceId === id)?.name ?? t('automations.unknownDevice')
  const parts = [triggerText(t, automation.program.trigger)]
  const first = automation.program.actions[0]
  if (first) parts.push(actionText(t, first, deviceName))
  return parts.filter((p) => p !== '').join(' · ')
}

function triggerText(t: TFunction, trigger: HomeTrigger): string {
  switch (trigger.type) {
    // ⚠ Native's model drops the day-of-week filter (`Trigger.Schedule` has none), so "At 7:00" on a
    // weekday-only routine reads the same as on the phone.
    case 'timeOfDay':
      return t('automations.trigger.at', { time: minutesOfDayText(trigger.minuteOfDay) })
    case 'sun': {
      const event = t(`automations.sun.${trigger.event}`)
      if (trigger.offsetMinutes < 0) return t('automations.trigger.sunBefore', { minutes: -trigger.offsetMinutes, event })
      if (trigger.offsetMinutes > 0) return t('automations.trigger.sunAfter', { minutes: trigger.offsetMinutes, event })
      return t('automations.trigger.sunAt', { event })
    }
    default:
      // Never listed (`visibleAutomations`).
      return ''
  }
}

function actionText(t: TFunction, action: HomeAction, deviceName: (id: string) => string): string {
  switch (action.type) {
    case 'setPower':
      return t(action.on ? 'automations.action.turnOn' : 'automations.action.turnOff', { device: deviceName(action.deviceId) })
    // Native's model has no level: a level reads as "on" above 0, "off" at 0.
    case 'setLevel':
      return t(action.percent > 0 ? 'automations.action.turnOn' : 'automations.action.turnOff', {
        device: deviceName(action.deviceId),
      })
    case 'notify':
      return t('automations.action.notify', { message: action.body.trim() !== '' ? action.body : action.title })
    case 'runRoutine':
      return t('automations.action.run')
  }
}
