// §8.1 Apex Welcome — first-time chooser (`docs/proposals/onboarding-low-friction.md`
// §8.1). No body copy: the landing page has already made the pitch.
//
//   [Create an account]       primary    → create the realm, passkey, plan step
//   [I already use Tenshu]    primary    → existing-device pairing (§4a)
//   Use without an account    footer link → Try-mode (§8.2): realm only, the
//                                           non-dismissible TryModeBanner nags
//
// The two primary actions carry equal weight on purpose: most web users arrive
// with the app already on a phone or computer, so pairing is not a secondary
// path. Try-mode is an expert path and stays a quiet link.
import { useTranslation } from 'react-i18next'
import { MarkOutline } from '../../components/marks/Outline'
import { LinkButton, OnboardingCard, OnboardingTitle } from '../OnboardingShell'

export function ChooserScreen({
  onSignUp,
  onExisting,
  onTryLocally,
}: {
  readonly onSignUp: () => void
  readonly onExisting: () => void
  readonly onTryLocally: () => void
}) {
  const { t } = useTranslation('onboarding')
  return (
    <OnboardingCard>
      <div className="flex justify-center">
        <MarkOutline size={96} aria-label={t('chooser.markAriaLabel')} />
      </div>
      <div className="mt-6 text-center">
        <OnboardingTitle>{t('chooser.title')}</OnboardingTitle>
      </div>
      <div className="mt-8 grid gap-3 sm:grid-cols-2">
        <ChoiceButton
          prompt={t('chooser.signUpPrompt')}
          label={t('chooser.actionSignUp')}
          onClick={onSignUp}
        />
        <ChoiceButton
          prompt={t('chooser.haveTenshuPrompt')}
          label={t('chooser.actionHaveTenshu')}
          onClick={onExisting}
        />
      </div>
      <div className="mt-6 text-center">
        <LinkButton onClick={onTryLocally}>{t('chooser.actionTryLocally')}</LinkButton>
      </div>
    </OnboardingCard>
  )
}

function ChoiceButton({
  prompt,
  label,
  onClick,
}: {
  readonly prompt: string
  readonly label: string
  readonly onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex flex-col items-center gap-1 rounded-lg bg-primary px-4 py-4 text-on-primary shadow-sm transition-colors hover:bg-primary/90"
    >
      <span className="text-xs opacity-80">{prompt}</span>
      <span className="text-sm font-semibold">{label}</span>
    </button>
  )
}
