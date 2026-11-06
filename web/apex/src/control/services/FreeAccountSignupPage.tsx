// Email + OTP. One screen serves sign-up and sign-in alike, exactly as native does
// (`FreeAccountSignupScreen(signIn = …)`): the service decides which it is from
// whether the address is known, and deliberately does not tell us — answering
// differently would make this an account-enumeration oracle.
//
// ⚠ This is a 6-digit CODE, typed here. The previous version described a
// verification LINK to tap, which the commerce plane has never sent.
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { Trans, useTranslation } from 'react-i18next'
import { privacyPolicyUrl, termsOfUseUrl } from '@tenshu/web-tokens/legal'
import { usePublisherClient } from './publisherContext'
import { purchasePath, tierForProductId } from './tiers'

type Step = 'email' | 'code'

export function FreeAccountSignupPage() {
  const { t, i18n } = useTranslation('control')
  const client = usePublisherClient()
  const navigate = useNavigate()
  // `return=checkout` is set by the picker (with `product`: sign in, then buy)
  // and by the checkout result page (without: confirm the email, then see the
  // plan land).
  const [params] = useSearchParams()
  const product = tierForProductId(params.get('product') ?? undefined)?.productId ?? null
  const afterVerify =
    params.get('return') !== 'checkout'
      ? '/control/services'
      : product !== null
        ? // Signing in on the way to a purchase: carry on to it.
          purchasePath(product, true)
        : '/control/services/checkout-result?status=success'
  const [step, setStep] = useState<Step>('email')
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function sendCode(e?: React.FormEvent) {
    e?.preventDefault()
    if (busy) return
    setBusy(true)
    setError(null)
    const result = await client.register(email, displayName.trim() || null)
    setBusy(false)
    if (result.kind === 'otpSent') {
      setStep('code')
      setCode('')
    } else {
      setError(result.message)
    }
  }

  async function submitCode(e: React.FormEvent) {
    e.preventDefault()
    if (busy) return
    setBusy(true)
    setError(null)
    const result = await client.verify(code)
    setBusy(false)
    if (result.kind === 'verified') {
      navigate(afterVerify, { replace: true })
    } else if (result.kind === 'invalidCode') {
      setError(t('services.signup.invalidCode'))
    } else {
      setError(result.message)
    }
  }

  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-2xl font-semibold tracking-tight">
        {step === 'email' ? t('services.signup.titleForm') : t('services.signup.titleCode')}
      </h1>
      {step === 'email' ? (
        <form onSubmit={sendCode} className="mt-4 space-y-4">
          <p className="text-sm text-text-2">{t('services.signup.intro')}</p>
          <label className="block text-sm">
            <span className="text-text-2">{t('services.signup.emailLabel')}</span>
            <input
              type="email"
              required
              value={email}
              onChange={e => setEmail(e.target.value)}
              className="mt-1 w-full rounded-md border border-line bg-surface px-3 py-2 text-text"
            />
          </label>
          <label className="block text-sm">
            <span className="text-text-2">{t('services.signup.displayNameLabel')}</span>
            <input
              type="text"
              value={displayName}
              onChange={e => setDisplayName(e.target.value)}
              className="mt-1 w-full rounded-md border border-line bg-surface px-3 py-2 text-text"
            />
          </label>
          {error !== null && <p className="text-sm text-error">{error}</p>}
          {/* Above the button: the acceptance must be read before the tap that gives it.
              `register` sends TOS_VERSION and PRIVACY_VERSION (commerceApi.ts), each the date of the
              page linked here. Continuing accepts the terms and confirms the minimum age (16,
              docs/policy/acceptable-use-and-limits.md §7). ⚠⚠ The privacy half informs, never "you
              accept": a privacy policy is information under the GDPR, and the legal bases are
              Art. 6(1)(b)/(f), not consent. */}
          <p className="text-xs text-text-2">
            <Trans
              ns="control"
              i18nKey="services.signup.legalNotice"
              // ⚠ Not `link`: react-i18next parses the string as HTML, where <link> is a void
              // element, so the words would land beside an empty anchor instead of inside it.
              components={{
                terms: <LegalLink href={termsOfUseUrl(i18n.language)} />,
                policy: <LegalLink href={privacyPolicyUrl(i18n.language)} />,
              }}
            />
          </p>
          <button
            type="submit"
            disabled={busy}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary disabled:opacity-60"
          >
            {busy ? t('services.signup.sending') : t('services.signup.send')}
          </button>
        </form>
      ) : (
        <form onSubmit={submitCode} className="mt-4 space-y-4">
          <p className="text-sm text-text-2">{t('services.signup.codeSent', { email })}</p>
          <label className="block text-sm">
            <span className="text-text-2">{t('services.signup.codeLabel')}</span>
            <input
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              required
              value={code}
              onChange={e => setCode(e.target.value)}
              className="mt-1 w-full rounded-md border border-line bg-surface px-3 py-2 font-mono text-text"
            />
          </label>
          {error !== null && <p className="text-sm text-error">{error}</p>}
          <div className="flex flex-wrap gap-3">
            <button
              type="submit"
              disabled={busy}
              className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary disabled:opacity-60"
            >
              {busy ? t('services.signup.verifying') : t('services.signup.verify')}
            </button>
            {/* ⚠ Resend goes through `register` again, which the service rate-limits
                per email AND per IP. A refused resend spends no budget. */}
            <button
              type="button"
              disabled={busy}
              onClick={() => void sendCode()}
              className="rounded-md border border-line px-4 py-2 text-sm text-text-2 disabled:opacity-60"
            >
              {t('services.signup.resend')}
            </button>
          </div>
        </form>
      )}
      <div className="mt-4">
        <button
          type="button"
          onClick={() => navigate('/control/services')}
          className="text-sm text-text-2 underline-offset-4 hover:underline"
        >
          {t('services.signup.cancel')}
        </button>
      </div>
    </div>
  )
}

/** A link to a static legal page, opened beside the app. Trans fills in the words. */
function LegalLink({ href, children }: { readonly href: string; readonly children?: React.ReactNode }) {
  return (
    <a href={href} target="_blank" rel="noreferrer" className="font-medium text-text underline underline-offset-4">
      {children}
    </a>
  )
}
