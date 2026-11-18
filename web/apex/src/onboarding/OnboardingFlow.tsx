// Orchestrator for the first-time chooser (§8.1) and the two onboarding
// paths beneath it (§4a existing-realm-device, §4b new-user). Single-mount
// React state keeps the whole flow in memory; refreshing the tab returns
// the user to /onboarding fresh per UX §7.1.
//
// The flow is deliberately short (`docs/proposals/onboarding-low-friction.md`
// §8.1, web onboarding pass of 2026-11-18):
//
//   new account:  chooser → generate → commit → passkey → get the apps
//                 → plan → /control/services/<choice>
//   try-mode:     chooser → generate → commit → passkey → get the apps
//                 → overview (TryModeBanner nags to convert)
//   joiner:       chooser → pairing → passkey → overview
//
// No step asks for a device name: the browser is named for what it is
// ("Chrome on macOS", `lib/deviceName.ts`). The recovery phrase is generated
// but never shown here — show + confirm live in Settings → Set up account
// recovery (§7.4 → `AccountRecoverySetupPage`).
//
// The recovery flow (§6) lives in `RecoveryFlow.tsx` rather than here
// because it has its own entry point (a "Lost your passkey?" link from
// §3) — but it shares the tail screens below.
import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router'
import { OVERVIEW_PATH } from '../lib/overviewPath'
import type { PairingSession, PairingStatus } from '@tenshu/sdk/onboarding'
import type { OfferTerminalStatus, PairingTab } from './screens/PairingScreens'
import { ChooserScreen } from './screens/ChooserScreen'
import {
  CommittingRealmScreen,
  GenerateRealmScreen,
  RealmCreateFailedScreen,
} from './screens/NewUserScreens'
import {
  PairingAwaitScreen,
  PairingOfferTerminalScreen,
  PairingScreen,
  PairingTerminalScreen,
} from './screens/PairingScreens'
import {
  CompletingSessionScreen,
  GetAppsScreen,
  PasskeySetupScreen,
  PlanScreen,
} from './screens/TailScreens'
import { OnboardingShell } from './OnboardingShell'
import { useDefaultDeviceName } from './useDefaultDeviceName'

// §8.1: `[Create an account]` vs `Use without an account` run the identical
// realm-creation flow; the intent only decides whether the plan step follows.
// Joiner (pairing) onboarding carries no intent — it lands on the control
// plane directly.
type NewUserIntent = 'signup' | 'try'

type Step =
  | { readonly kind: 'chooser' }
  // §4b new-user
  | { readonly kind: 'newUser.generate'; readonly intent: NewUserIntent }
  | {
      readonly kind: 'newUser.commit'
      readonly mnemonic: readonly string[]
      readonly intent: NewUserIntent
    }
  | {
      readonly kind: 'newUser.commitFailed'
      readonly mnemonic: readonly string[]
      readonly intent: NewUserIntent
      readonly message: string
    }
  // §4a pairing
  | { readonly kind: 'pairing.submit'; readonly tab?: PairingTab }
  | { readonly kind: 'pairing.await'; readonly session: PairingSession }
  | {
      readonly kind: 'pairing.terminal'
      readonly status: Exclude<PairingStatus, { kind: 'awaiting' }>
    }
  // Offer flow (`sync-pairing-inverted-direction.md`): this browser showed
  // the QR; the responder acted.
  | { readonly kind: 'pairing.offerTerminal'; readonly status: OfferTerminalStatus }
  // Shared tail. `intent` is set on the new-user path; it stays undefined for
  // the joiner path, which already has the apps and an account.
  | { readonly kind: 'tail.passkey'; readonly isNewUser: boolean; readonly intent?: NewUserIntent }
  | { readonly kind: 'tail.getApps'; readonly intent: NewUserIntent }
  | { readonly kind: 'tail.plan' }
  | { readonly kind: 'tail.completing'; readonly destination: string }

// A joiner already has the apps and an account, so it goes straight in.
function afterPasskey(intent: NewUserIntent | undefined): Step {
  return intent === undefined
    ? { kind: 'tail.completing', destination: OVERVIEW_PATH }
    : { kind: 'tail.getApps', intent }
}

export function OnboardingFlow() {
  const deviceName = useDefaultDeviceName()
  const navigate = useNavigate()
  const [step, setStep] = useState<Step>({ kind: 'chooser' })

  const goToTailPasskey = useCallback(
    (isNewUser: boolean, intent?: NewUserIntent) =>
      setStep({ kind: 'tail.passkey', isNewUser, intent }),
    [],
  )

  const onTerminal = useCallback(
    (status: Exclude<PairingStatus, { kind: 'awaiting' }>) => {
      setStep(prev => (prev.kind === 'pairing.await' ? { kind: 'pairing.terminal', status } : prev))
    },
    [],
  )

  const onOfferTerminal = useCallback(
    (status: OfferTerminalStatus) => {
      setStep(prev =>
        prev.kind === 'pairing.submit' ? { kind: 'pairing.offerTerminal', status } : prev,
      )
    },
    [],
  )

  const destination = step.kind === 'tail.completing' ? step.destination : null
  const onCompleted = useCallback(() => {
    if (destination !== null) navigate(destination, { replace: true })
  }, [navigate, destination])

  return (
    <OnboardingShell>
      {step.kind === 'chooser' && (
        <ChooserScreen
          onSignUp={() => setStep({ kind: 'newUser.generate', intent: 'signup' })}
          onExisting={() => setStep({ kind: 'pairing.submit' })}
          onTryLocally={() => setStep({ kind: 'newUser.generate', intent: 'try' })}
        />
      )}

      {/* §4b new-user */}
      {step.kind === 'newUser.generate' && (
        <GenerateRealmScreen
          onReady={mnemonic =>
            // §8/§7.4: the phrase is generated but never shown here — it goes
            // straight into Realm.create. Capture (show + confirm) is deferred
            // to Settings → Set up account recovery.
            setStep({
              kind: 'newUser.commit',
              mnemonic: mnemonic.split(' '),
              intent: step.intent,
            })
          }
        />
      )}
      {step.kind === 'newUser.commit' && (
        <CommittingRealmScreen
          mnemonic={step.mnemonic.join(' ')}
          deviceName={deviceName}
          onCommitted={() => goToTailPasskey(true, step.intent)}
          onFailed={message =>
            setStep({
              kind: 'newUser.commitFailed',
              mnemonic: step.mnemonic,
              intent: step.intent,
              message,
            })
          }
        />
      )}
      {step.kind === 'newUser.commitFailed' && (
        <RealmCreateFailedScreen
          message={step.message}
          onRetry={() =>
            setStep({ kind: 'newUser.commit', mnemonic: step.mnemonic, intent: step.intent })
          }
          onCancel={() => setStep({ kind: 'chooser' })}
        />
      )}

      {/* §4a pairing */}
      {step.kind === 'pairing.submit' && (
        <PairingScreen
          deviceName={deviceName}
          initialTab={step.tab}
          onJoinSession={session => setStep({ kind: 'pairing.await', session })}
          onOfferTerminal={onOfferTerminal}
          onCancel={() => setStep({ kind: 'chooser' })}
        />
      )}
      {step.kind === 'pairing.await' && (
        <PairingAwaitScreen session={step.session} onTerminal={onTerminal} />
      )}
      {step.kind === 'pairing.terminal' && (
        <PairingTerminalScreen
          status={step.status}
          onContinue={() => goToTailPasskey(false)}
          onRetry={() => setStep({ kind: 'pairing.submit' })}
          onCancel={() => setStep({ kind: 'chooser' })}
        />
      )}
      {step.kind === 'pairing.offerTerminal' && (
        <PairingOfferTerminalScreen
          status={step.status}
          onContinue={() => goToTailPasskey(false)}
          onRetry={() => setStep({ kind: 'pairing.submit', tab: 'show' })}
          onCancel={() => setStep({ kind: 'chooser' })}
        />
      )}

      {/* Shared tail */}
      {step.kind === 'tail.passkey' && (
        <PasskeySetupScreen
          deviceName={deviceName}
          isFirstEncounter={step.isNewUser}
          onContinue={() => setStep(afterPasskey(step.intent))}
          onSkip={() => setStep(afterPasskey(step.intent))}
        />
      )}
      {step.kind === 'tail.getApps' && (
        <GetAppsScreen
          onContinue={() =>
            setStep(
              step.intent === 'signup'
                ? { kind: 'tail.plan' }
                : { kind: 'tail.completing', destination: OVERVIEW_PATH },
            )
          }
        />
      )}
      {step.kind === 'tail.plan' && (
        <PlanScreen
          onChoose={to => setStep({ kind: 'tail.completing', destination: to })}
          onNotNow={() => setStep({ kind: 'tail.completing', destination: OVERVIEW_PATH })}
        />
      )}
      {step.kind === 'tail.completing' && <CompletingSessionScreen onCompleted={onCompleted} />}
    </OnboardingShell>
  )
}
