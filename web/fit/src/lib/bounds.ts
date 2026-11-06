// The SDK's write domains and read limits (`@tenshu/sdk/fit`), as data. ⚠ Spelled out, not imported:
// they are VALUES in `@tenshu/sdk/fit`, which re-exports the Kotlin bundle (web-app-suite.md §7).
// `bounds.test.ts` reads the SDK's source and fails if one drifts.
//
// The forms enforce them before sending, so a typical slip is caught as you type; the SDK enforces
// them again and answers a typed `error_fit_invalid_goal` / `error_fit_invalid_profile_value`, which
// the forms render too.
export const GOAL_ACTIVE_MINUTES = { min: 5, max: 240 } as const
export const GOAL_STEPS = { min: 1_000, max: 30_000 } as const
// Height is an integer in 1..MAX_HEIGHT_CM; weight is above 0 and at most MAX_WEIGHT_KG.
export const MAX_HEIGHT_CM = 300
export const MAX_WEIGHT_KG = 1_000
export const EARLIEST_BIRTH_DATE = '1900-01-01'
export const MAX_WORKOUT_PAGE_SIZE = 200
export const MAX_AGGREGATE_WINDOW_DAYS = 400
// ⚠ The gateway refuses a bigger file BEFORE it reaches the SDK, as an untyped bridge rejection, so
// the app checks it first and shows `error_fit_file_too_large` itself.
export const MAX_ACTIVITY_FILE_BYTES = 32 * 1024 * 1024
