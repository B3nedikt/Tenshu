// Pre-launch signup gate. Closes the door behind "Get started" while leaving the
// marketing site — and every realm that ALREADY exists in this browser — working.
// Driven by `VITE_SIGNUP_DISABLED` at build time, same polarity as
// `VITE_CONSTRUCTION_CODE`: unset/empty = open. So local dev, vitest and a test
// deployment get the working site with no configuration, and only the public
// build carries a knob.
//
// ⚠ A CURTAIN, NOT SECURITY. Vite inlines the flag, so a closed build simply
// contains no route to realm creation — the relay's own endpoints are unchanged
// and still answer anyone who calls them directly. This is the marketing site
// declining to point at a door, not an access control. Real gating belongs at
// the relay.
//
// ⚠ Closes ALL THREE chooser doors (sign up / pair an existing device / try
// locally — see `screens/ChooserScreen.tsx`), because it gates the route above
// them. Reopening just try-locally is a change to that one screen plus this
// predicate's call site in `App.tsx`; nothing else needs to know.
//
// It deliberately does NOT gate returning visitors: `active` still opens the
// control plane and `locked` still unlocks. Gating those would strand a realm
// that already exists in this browser, which reads as data loss rather than as
// a pre-launch notice.
//
// A function, not a module constant, so `vi.stubEnv` can drive it in tests — a
// const would freeze the value at import time.
export function signupOpen(): boolean {
  return (import.meta.env.VITE_SIGNUP_DISABLED ?? '').trim() === ''
}
