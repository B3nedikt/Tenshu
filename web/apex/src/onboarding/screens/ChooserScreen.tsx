// §8.1 Apex Welcome — first-time chooser, ported to the low-friction
// redesign (`docs/proposals/onboarding-low-friction.md` §8.1, web-roadmap
// 6.6). Browsers are not durable storage the way phones are, so — unlike
// native, where the equal-weight chooser defers signup — the web surface
// prompts signup eagerly: `[Sign up free]` is the primary CTA. The genuine
// deferral, `[Just try it locally]`, is the *third* CTA (quiet link) because
// the cost of deferring is much higher on web (a browser profile can be
// cleared at any time). All three are framed as sync-enablement, not
// access-gating.
//
//   [Sign up free]            primary    → create the realm, then §5.1 picker
//   [I have Tenshu already]   secondary  → existing-device pairing (§4a)
//   [Just try it locally]     tertiary   → Try-mode (§8.2): realm only, the
//                                          non-dismissible TryModeBanner nags
import { useTranslation } from 'react-i18next'
import { MarkOutline } from '../../components/marks/Outline'
import {
  LinkButton,
  OnboardingActions,
  OnboardingCard,
  OnboardingProse,
  OnboardingTitle,
  PrimaryButton,
  SecondaryButton,
} from '../OnboardingShell'

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
      <div className="mt-6">
        <OnboardingTitle>{t('chooser.title')}</OnboardingTitle>
      </div>
      <OnboardingProse>
        <p>{t('chooser.body1')}</p>
        <p>{t('chooser.body2')}</p>
      </OnboardingProse>
      <OnboardingActions>
        <PrimaryButton onClick={onSignUp}>{t('chooser.actionSignUp')}</PrimaryButton>
        <SecondaryButton onClick={onExisting}>{t('chooser.actionHaveTenshu')}</SecondaryButton>
      </OnboardingActions>
      <div className="mt-4">
        <LinkButton onClick={onTryLocally}>{t('chooser.actionTryLocally')}</LinkButton>
      </div>
    </OnboardingCard>
  )
}
