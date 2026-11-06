// Orchestrator for the first-time chooser (§8.1) and the two onboarding
// paths beneath it (§4a existing-realm-device, §4b new-user). Single-mount
// React state keeps the whole flow in memory; refreshing the tab returns
// the user to /onboarding fresh per UX §7.1.
//
// web-roadmap 6.6 ports the low-friction redesign
// (`docs/proposals/onboarding-low-friction.md` §8). The new-user path no
// longer walks the 24-word ceremony up front: the recovery phrase is still
// generated (it has to be — the master key derives from it) but is no longer
// *shown* during onboarding. Show + confirm moved to the Settings "Set up
// account recovery" entry (§7.4 → `AccountRecoverySetupPage`); new users go
// generate → commit → straight to the shared passkey/PRF tail.
//
// The chooser's `[Sign up free]` and `[Just try it locally]` both create the
// same local realm; they diverge only at the end (the `intent`): signup
// proceeds into the §5.1 account picker, try-mode lands on the control plane
// where the non-dismissible TryModeBanner nags to convert.
//
// The recovery flow (§6) lives in `RecoveryFlow.tsx` rather than here
// because it has its own entry point (a "Lost your passkey?" link from
// §3) — but its tail (passkey → sync → explainer) is rendered by the
// same `Tail` component below.
import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import type { PairingSession, PairingStatus } from '@tenshu/sdk/onboarding'
import type { OfferTerminalStatus, PairingTab } from './screens/PairingScreens'
import { ChooserScreen } from './screens/ChooserScreen'
import {
  CommittingRealmScreen,
  DeviceNameScreen,
  GenerateRealmScreen,
  IntroScreen,
  RealmCreateFailedScreen,
} from './screens/NewUserScreens'
import {
  PairingAwaitScreen,
  PairingOfferTerminalScreen,
  PairingScreen,
  PairingTerminalScreen,
} from './screens/PairingScreens'
import {
  BrowserSyncWarningScreen,
  CompletingSessionScreen,
  LayeredExplainerScreen,
  PasskeySetupScreen,
  StorageTierScreen,
  SyncOptOutScreen,
} from './screens/TailScreens'
import { OnboardingShell } from './OnboardingShell'

// §8.1: `[Sign up free]` vs `[Just try it locally]` run the identical
// realm-creation flow; the intent only decides where the user lands once the
// realm exists. Joiner (pairing) onboarding carries no intent — it lands on
// the control plane directly.
type NewUserIntent = 'signup' | 'try'

type Step =
  | { readonly kind: 'chooser' }
  // §4b new-user
  | { readonly kind: 'newUser.intro'; readonly intent: NewUserIntent }
  | { readonly kind: 'newUser.deviceName'; readonly intent: NewUserIntent }
  | {
      readonly kind: 'newUser.generate'
      readonly deviceName: string
      readonly intent: NewUserIntent
    }
  | {
      readonly kind: 'newUser.commit'
      readonly deviceName: string
      readonly mnemonic: readonly string[]
      readonly intent: NewUserIntent
    }
  | {
      readonly kind: 'newUser.commitFailed'
      readonly deviceName: string
      readonly mnemonic: readonly string[]
      readonly intent: NewUserIntent
      readonly message: string
    }
  // §4a pairing
  | { readonly kind: 'pairing.deviceName' }
  | { readonly kind: 'pairing.submit'; readonly deviceName: string; readonly tab?: PairingTab }
  | {
      readonly kind: 'pairing.await'
      readonly deviceName: string
      readonly session: PairingSession
    }
  | {
      readonly kind: 'pairing.terminal'
      readonly deviceName: string
      readonly status: Exclude<PairingStatus, { kind: 'awaiting' }>
    }
  // Offer flow (`sync-pairing-inverted-direction.md`): this browser showed
  // the QR; the responder acted.
  | {
      readonly kind: 'pairing.offerTerminal'
      readonly deviceName: string
      readonly status: OfferTerminalStatus
    }
  // Shared tail. `intent` is set on the new-user path (it decides the final
  // landing route); it stays undefined for the joiner path.
  | { readonly kind: 'tail.passkey'; readonly deviceName: string; readonly isNewUser: boolean; readonly intent?: NewUserIntent }
  | { readonly kind: 'tail.syncWarning'; readonly deviceName: string; readonly isNewUser: boolean; readonly intent?: NewUserIntent }
  | { readonly kind: 'tail.syncOptOut'; readonly deviceName: string; readonly isNewUser: boolean; readonly intent?: NewUserIntent }
  | { readonly kind: 'tail.explainer'; readonly deviceName: string; readonly isNewUser: boolean; readonly intent?: NewUserIntent }
  | { readonly kind: 'tail.storage'; readonly deviceName: string; readonly intent?: NewUserIntent }
  | { readonly kind: 'tail.completing'; readonly intent?: NewUserIntent }

export function OnboardingFlow() {
  const { t } = useTranslation('onboarding')
  const defaultDeviceName = t('deviceName.default')
  const navigate = useNavigate()
  const [step, setStep] = useState<Step>({ kind: 'chooser' })

  const onCompleted = useCallback(
    (intent?: NewUserIntent) => {
      // §8.1/§8.2: signup proceeds into the account picker so the user binds
      // a free account; try-mode (and the joiner path) lands on the control
      // plane, where the TryModeBanner offers conversion when no account is
      // bound yet.
      navigate(intent === 'signup' ? '/control/services/picker' : '/control/home', {
        replace: true,
      })
    },
    [navigate],
  )

  const goToTailPasskey = useCallback(
    (deviceName: string, isNewUser: boolean, intent?: NewUserIntent) =>
      setStep({ kind: 'tail.passkey', deviceName, isNewUser, intent }),
    [],
  )

  const onTerminal = useCallback(
    (status: Exclude<PairingStatus, { kind: 'awaiting' }>) => {
      setStep(prev => {
        if (prev.kind !== 'pairing.await') return prev
        return { kind: 'pairing.terminal', deviceName: prev.deviceName, status }
      })
    },
    [],
  )

  const onOfferTerminal = useCallback(
    (status: OfferTerminalStatus) => {
      setStep(prev => {
        if (prev.kind !== 'pairing.submit') return prev
        return { kind: 'pairing.offerTerminal', deviceName: prev.deviceName, status }
      })
    },
    [],
  )

  return (
    <OnboardingShell>
      {step.kind === 'chooser' && (
        <ChooserScreen
          onSignUp={() => setStep({ kind: 'newUser.intro', intent: 'signup' })}
          onExisting={() => setStep({ kind: 'pairing.deviceName' })}
          onTryLocally={() => setStep({ kind: 'newUser.intro', intent: 'try' })}
        />
      )}

      {/* §4b new-user */}
      {step.kind === 'newUser.intro' && (
        <IntroScreen
          onContinue={() => setStep({ kind: 'newUser.deviceName', intent: step.intent })}
        />
      )}
      {step.kind === 'newUser.deviceName' && (
        <DeviceNameScreen
          defaultName={defaultDeviceName}
          onContinue={name =>
            setStep({ kind: 'newUser.generate', deviceName: name, intent: step.intent })
          }
          onBack={() => setStep({ kind: 'newUser.intro', intent: step.intent })}
        />
      )}
      {step.kind === 'newUser.generate' && (
        <GenerateRealmScreen
          onReady={mnemonic =>
            // §8/§7.4: the phrase is generated but never shown here — it goes
            // straight into Realm.create. Capture (show + confirm) is deferred
            // to Settings → Set up account recovery.
            setStep({
              kind: 'newUser.commit',
              deviceName: step.deviceName,
              mnemonic: mnemonic.split(' '),
              intent: step.intent,
            })
          }
        />
      )}
      {step.kind === 'newUser.commit' && (
        <CommittingRealmScreen
          mnemonic={step.mnemonic.join(' ')}
          deviceName={step.deviceName}
          onCommitted={() => goToTailPasskey(step.deviceName, true, step.intent)}
          onFailed={message =>
            setStep({
              kind: 'newUser.commitFailed',
              deviceName: step.deviceName,
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
            setStep({
              kind: 'newUser.commit',
              deviceName: step.deviceName,
              mnemonic: step.mnemonic,
              intent: step.intent,
            })
          }
          onCancel={() => setStep({ kind: 'chooser' })}
        />
      )}

      {/* §4a pairing */}
      {step.kind === 'pairing.deviceName' && (
        <DeviceNameScreen
          defaultName={defaultDeviceName}
          onContinue={name => setStep({ kind: 'pairing.submit', deviceName: name })}
          onBack={() => setStep({ kind: 'chooser' })}
        />
      )}
      {step.kind === 'pairing.submit' && (
        <PairingScreen
          deviceName={step.deviceName}
          initialTab={step.tab}
          onJoinSession={session =>
            setStep({ kind: 'pairing.await', deviceName: step.deviceName, session })
          }
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
          onContinue={() => goToTailPasskey(step.deviceName, false)}
          onRetry={() => setStep({ kind: 'pairing.submit', deviceName: step.deviceName })}
          onCancel={() => setStep({ kind: 'chooser' })}
        />
      )}
      {step.kind === 'pairing.offerTerminal' && (
        <PairingOfferTerminalScreen
          status={step.status}
          onContinue={() => goToTailPasskey(step.deviceName, false)}
          onRetry={() =>
            setStep({ kind: 'pairing.submit', deviceName: step.deviceName, tab: 'show' })
          }
          onCancel={() => setStep({ kind: 'chooser' })}
        />
      )}

      {/* Shared tail */}
      {step.kind === 'tail.passkey' && (
        <PasskeySetupScreen
          deviceName={step.deviceName}
          isFirstEncounter={step.isNewUser}
          onContinue={() =>
            setStep({
              kind: 'tail.syncWarning',
              deviceName: step.deviceName,
              isNewUser: step.isNewUser,
              intent: step.intent,
            })
          }
          onSkip={() =>
            setStep({
              kind: 'tail.syncWarning',
              deviceName: step.deviceName,
              isNewUser: step.isNewUser,
              intent: step.intent,
            })
          }
        />
      )}
      {step.kind === 'tail.syncWarning' && (
        <BrowserSyncWarningScreen
          onSeparate={() =>
            setStep({
              kind: 'tail.syncOptOut',
              deviceName: step.deviceName,
              isNewUser: step.isNewUser,
              intent: step.intent,
            })
          }
          onSkip={() =>
            setStep({
              kind: 'tail.explainer',
              deviceName: step.deviceName,
              isNewUser: step.isNewUser,
              intent: step.intent,
            })
          }
        />
      )}
      {step.kind === 'tail.syncOptOut' && (
        <SyncOptOutScreen
          onContinue={() =>
            setStep({
              kind: 'tail.explainer',
              deviceName: step.deviceName,
              isNewUser: step.isNewUser,
              intent: step.intent,
            })
          }
        />
      )}
      {step.kind === 'tail.explainer' && (
        <LayeredExplainerScreen
          onContinue={() =>
            step.isNewUser
              ? setStep({ kind: 'tail.storage', deviceName: step.deviceName, intent: step.intent })
              : setStep({ kind: 'tail.completing' })
          }
        />
      )}
      {step.kind === 'tail.storage' && (
        <StorageTierScreen
          onContinue={() => setStep({ kind: 'tail.completing', intent: step.intent })}
        />
      )}
      {step.kind === 'tail.completing' && (
        <CompletingSessionScreen onCompleted={() => onCompleted(step.intent)} />
      )}
    </OnboardingShell>
  )
}
