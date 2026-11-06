import type { TFunction } from 'i18next'
import type { FitWorkout } from '@tenshu/sdk/fit'

// The stored title when the source gave one, else the activity type's name.
export function workoutTitle(t: TFunction, workout: FitWorkout): string {
  return workout.title.trim() || t(`activity.${workout.activityType}`)
}
