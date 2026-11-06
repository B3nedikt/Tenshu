// The account-deletion page (/account/delete): a Tenshu account deleted from a browser, with no
// app, no realm and no sign-in here. Google Play requires such a page, reachable without the app,
// before Control can be reviewed — so its URL is stable and it is never behind the pre-launch
// curtain (lib/alwaysPublicPaths.ts). Copy lives under `accountDeletion.*` in
// locales/<lang>/landing.json; the in-app deletion flow says the same things and should stay
// consistent with it.
//
// Flow: email → the service mails a deletion code → the code, an explicit acknowledgement, and a
// destructive button → done. Wire contract: `accountDeletionApi` (control/services/commerceApi.ts).
//
// ⚠⚠ Not an account-enumeration oracle: `/request` answers 202 for every well-formed address, and
// the code step's copy is the same whether or not an account uses it ("If a Tenshu account uses…").
// Never branch the UI on anything that would tell the two apart.
//
// ⚠ What deletion does is shown BEFORE the user confirms, on both steps — Play's reviewers read it
// there, and it must match the policy (docs/policy/acceptable-use-and-limits.md §4.3, §5.2).
//
// A public page: it wears MarketingLayout and must never start the runtime (App.tsx).
import { useLayoutEffect, useState, type FormEvent } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import { MarketingLayout } from './MarketingLayout'
import {
  accountDeletionApi,
  type DeletionConfirmResult,
  type DeletionRequestResult,
} from '../control/services/commerceApi'

type Step = 'email' | 'code' | 'done'

const WHAT = ['deleted', 'subscriptions', 'apple', 'data', 'kept', 'devices', 'permanent'] as const

const CODE_PATTERN = /^\d{6}$/

function requestErrorKey(result: Exclude<DeletionRequestResult, { kind: 'codeSent' }>): string {
  switch (result.kind) {
    case 'invalidEmail':
      return 'accountDeletion.error.invalidEmail'
    case 'rateLimited':
      return 'accountDeletion.error.rateLimited'
    case 'sendFailed':
      return 'accountDeletion.error.sendFailed'
    case 'unreachable':
      return 'accountDeletion.error.unreachable'
    case 'error':
      return 'accountDeletion.error.generic'
  }
}

function confirmErrorKey(result: Exclude<DeletionConfirmResult, { kind: 'deleted' }>): string {
  switch (result.kind) {
    case 'invalidCode':
      return 'accountDeletion.error.invalidCode'
    case 'rateLimited':
      return 'accountDeletion.error.rateLimited'
    case 'unreachable':
      return 'accountDeletion.error.unreachable'
    case 'error':
      return 'accountDeletion.error.generic'
  }
}

export function AccountDeletionPage() {
  const { t } = useTranslation('landing')
  useLayoutEffect(() => {
    window.scrollTo(0, 0)
  }, [])

  const [step, setStep] = useState<Step>('email')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [resent, setResent] = useState(false)

  async function sendCode(e?: FormEvent) {
    e?.preventDefault()
    if (busy) return
    setBusy(true)
    setError(null)
    setResent(false)
    const result = await accountDeletionApi.request(email)
    setBusy(false)
    if (result.kind === 'codeSent') {
      setResent(step === 'code')
      setStep('code')
      setCode('')
    } else {
      setError(t(requestErrorKey(result)))
    }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault()
    if (busy || !acknowledged || !CODE_PATTERN.test(code.trim())) return
    setBusy(true)
    setError(null)
    setResent(false)
    const result = await accountDeletionApi.confirm(email, code)
    setBusy(false)
    if (result.kind === 'deleted') {
      setStep('done')
    } else {
      setError(t(confirmErrorKey(result)))
    }
  }

  function changeEmail() {
    setStep('email')
    setCode('')
    setAcknowledged(false)
    setError(null)
    setResent(false)
  }

  const errorLine =
    error === null ? null : (
      <p role="alert" className="text-sm text-danger">
        {error}
      </p>
    )

  return (
    <MarketingLayout>
      <main className="mx-auto w-full max-w-2xl px-6 py-16 sm:py-24">
        {step === 'done' ? (
          <section aria-labelledby="account-deletion-done">
            <h1 id="account-deletion-done" className="text-3xl font-semibold tracking-tight sm:text-4xl">
              {t('accountDeletion.done.heading')}
            </h1>
            <p className="mt-6 text-lg text-text-2">{t('accountDeletion.done.body')}</p>
            <p className="mt-4 text-base text-text-2">{t('accountDeletion.done.apple')}</p>
          </section>
        ) : (
          <>
            <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">
              {t('accountDeletion.heading')}
            </h1>
            <p className="mt-6 text-lg text-text-2">{t('accountDeletion.intro')}</p>

            <section
              aria-labelledby="account-deletion-what"
              className="mt-10 rounded-lg border border-line bg-surface-2 p-6"
            >
              <h2 id="account-deletion-what" className="text-xl font-semibold tracking-tight">
                {t('accountDeletion.what.heading')}
              </h2>
              <ul className="mt-4 list-disc space-y-3 pl-5 text-base text-text-2">
                {WHAT.map((id) => (
                  <li key={id} className={id === 'permanent' ? 'font-medium text-text' : undefined}>
                    {t(`accountDeletion.what.${id}`)}
                  </li>
                ))}
              </ul>
            </section>

            {step === 'email' ? (
              <form onSubmit={(e) => void sendCode(e)} className="mt-10 space-y-4">
                <label className="block text-sm">
                  <span className="text-text-2">{t('accountDeletion.email.label')}</span>
                  <input
                    type="email"
                    required
                    autoComplete="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    className="mt-1 w-full rounded-md border border-line bg-surface px-3 py-2 text-text"
                  />
                </label>
                {errorLine}
                <button
                  type="submit"
                  disabled={busy || email.trim() === ''}
                  className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {busy ? t('accountDeletion.email.sending') : t('accountDeletion.email.send')}
                </button>
              </form>
            ) : (
              <form onSubmit={(e) => void confirm(e)} className="mt-10 space-y-4">
                {/* ⚠ The same sentence whether or not an account uses the address. */}
                <p className="text-base text-text" role="status">
                  {resent
                    ? t('accountDeletion.code.resent')
                    : t('accountDeletion.code.sent', { email: email.trim() })}
                </p>
                <label className="block text-sm">
                  <span className="text-text-2">{t('accountDeletion.code.label')}</span>
                  <input
                    type="text"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength={6}
                    required
                    value={code}
                    onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                    className="mt-1 w-40 rounded-md border border-line bg-surface px-3 py-2 font-mono tracking-widest text-text"
                  />
                </label>
                <label className="flex items-start gap-3 text-sm">
                  <input
                    type="checkbox"
                    checked={acknowledged}
                    onChange={(e) => setAcknowledged(e.target.checked)}
                    className="mt-0.5"
                  />
                  <span className="text-text">{t('accountDeletion.code.acknowledge')}</span>
                </label>
                {errorLine}
                <div className="flex flex-wrap gap-3">
                  {/* ⚠ Disabled until the acknowledgement is ticked: the one irreversible
                      button on the site is never one click away from a typed code. */}
                  <button
                    type="submit"
                    disabled={busy || !acknowledged || !CODE_PATTERN.test(code.trim())}
                    className="rounded-md bg-danger px-4 py-2 text-sm font-medium text-white hover:bg-danger/90 disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    {busy ? t('accountDeletion.code.deleting') : t('accountDeletion.code.delete')}
                  </button>
                  {/* Resend goes through `/request` again, which the service rate-limits. */}
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void sendCode()}
                    className="rounded-md border border-line px-4 py-2 text-sm text-text-2 disabled:opacity-60"
                  >
                    {t('accountDeletion.code.resend')}
                  </button>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={changeEmail}
                    className="px-2 py-2 text-sm text-text-2 underline-offset-4 hover:underline disabled:opacity-60"
                  >
                    {t('accountDeletion.code.changeEmail')}
                  </button>
                </div>
              </form>
            )}

            <p className="mt-10 text-sm text-text-2">
              <Trans
                ns="landing"
                i18nKey="accountDeletion.help"
                components={{
                  mail: (
                    <a
                      href="mailto:support@tenshu.app"
                      className="font-medium text-text underline underline-offset-4"
                    />
                  ),
                }}
              />
            </p>
          </>
        )}
      </main>
    </MarketingLayout>
  )
}
