// What `/onboarding` renders while the pre-launch signup gate is closed
// (`signupOpen()` — see `lib/signupGate.ts`).
//
// This is the ONLY rendering of the closed state, because every route into realm
// creation funnels through the `/onboarding` route: `/start` resolves `firstTime`
// to it, the invite-redeem "Create an account" CTA points at `/start`, and a
// LockedFlow local purge navigates to it directly. Gating the route therefore
// covers all of them, and returning `active` / `locked` visitors never reach it.
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { MarkOutline } from '../components/marks/Outline'
import { OnboardingCard, OnboardingProse, OnboardingTitle } from './OnboardingShell'

export function SignupClosedNotice() {
  const { t } = useTranslation('onboarding')
  return (
    <OnboardingCard>
      <div className="flex justify-center">
        <MarkOutline size={96} aria-label={t('chooser.markAriaLabel')} />
      </div>
      <div className="mt-6">
        <OnboardingTitle>{t('signupClosed.title')}</OnboardingTitle>
      </div>
      <OnboardingProse>
        <p>{t('signupClosed.body1')}</p>
        <p>{t('signupClosed.body2')}</p>
      </OnboardingProse>
      {/* Never a dead end: the notice is reachable from a deep link, so it owns
          its own way back rather than relying on the shell's brand lockup. */}
      <div className="mt-8">
        <Link
          to="/"
          className="text-sm font-medium text-text-2 underline-offset-4 hover:text-text hover:underline"
        >
          {t('signupClosed.backHome')}
        </Link>
      </div>
    </OnboardingCard>
  )
}
