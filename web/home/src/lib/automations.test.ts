import { describe, expect, it } from 'vitest'
import type { HomeAutomation, HomeTrigger } from '@tenshu/sdk/home'
import { automation } from '../test/proxyMock'
import { minutesOfDayText, visibleAutomations } from './automations'

const TRIGGERS: Record<HomeTrigger['type'], HomeTrigger> = {
  timeOfDay: { type: 'timeOfDay', minuteOfDay: 0, days: [] },
  sun: { type: 'sun', event: 'sunrise', offsetMinutes: 0 },
  deviceEvent: { type: 'deviceEvent', deviceId: 'd', match: { field: 'f', op: 'eq', value: 'v' } },
  geofence: { type: 'geofence', placeId: 'p', direction: 'leave' },
  voicePhrase: { type: 'voicePhrase', phrase: 'x' },
}

function withTrigger(trigger: HomeTrigger): HomeAutomation {
  return automation(trigger.type, { program: { trigger, conditions: [], actions: [] } })
}

describe('visibleAutomations — native AutomationProgramMapper.toAutomation', () => {
  it.each(Object.entries(TRIGGERS))('%s', (type, trigger) => {
    const listed = visibleAutomations([withTrigger(trigger)]).length === 1
    expect(listed).toBe(type === 'timeOfDay' || type === 'sun')
  })

  it.each(['unsupportedVersion', 'malformed'] as const)('drops programStatus %s', (status) => {
    expect(visibleAutomations([automation('x', { programStatus: status, program: null })])).toEqual([])
  })

  it('drops a non-parsed status on its own, even with a program attached', () => {
    expect(visibleAutomations([automation('x', { programStatus: 'unsupportedVersion' })])).toEqual([])
  })

  it('keeps the order run A returns', () => {
    const all = [automation('b'), automation('a'), automation('c')]
    expect(visibleAutomations(all).map((a) => a.routineId)).toEqual(['b', 'a', 'c'])
  })
})

describe('minutesOfDayText — native formatMinutesOfDay', () => {
  it.each([
    [0, '0:00'],
    [7 * 60 + 5, '7:05'],
    [22 * 60 + 30, '22:30'],
    [1440 + 61, '1:01'],
    [-1, '23:59'],
  ])('%i → %s', (m, text) => expect(minutesOfDayText(m)).toBe(text))
})
