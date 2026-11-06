// §4a joiner pairing screens (roadmap 6.3, T.2 gap). Covers the
// submit-screen validation gate (reconciled on the canonical
// `tenshu://join?…` form Control emits), the SDK submit hand-off, the
// await screen's SAS rendering + terminal hand-off, and each terminal
// state's CTA wiring.
import type { ReactElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type {
  OfferPairingSession,
  OfferPairingStatus,
  PairingSession,
  PairingStatus,
  PairingSubmitError,
} from '@tenshu/sdk/onboarding'
import type { TResult } from '@tenshu/sdk/onboarding'
import { OnboardingSdkContext } from '../../lib/onboardingSdkContext'
import type { OnboardingSdk } from '../../lib/mockOnboarding'
import {
  PairingAwaitScreen,
  PairingOfferScreen,
  PairingOfferTerminalScreen,
  PairingSubmitScreen,
  PairingTerminalScreen,
} from './PairingScreens'

const CANONICAL_URI = 'tenshu://join?o=owner-1&d=MQYQ&k=AAAAAAAAAAAA&s=OM'

function sdkWithSubmit(
  submit: (args: {
    pairingUri: string
    deviceName: string
  }) => Promise<TResult<PairingSession, PairingSubmitError>>,
): OnboardingSdk {
  return { Pairing: { submit } } as unknown as OnboardingSdk
}

function renderWithSdk(sdk: OnboardingSdk, ui: ReactElement) {
  return render(
    <OnboardingSdkContext.Provider value={sdk}>{ui}</OnboardingSdkContext.Provider>,
  )
}

describe('PairingSubmitScreen', () => {
  it('keeps Continue disabled and shows no validation message while empty', () => {
    const sdk = sdkWithSubmit(vi.fn())
    renderWithSdk(
      sdk,
      <PairingSubmitScreen deviceName="Web" onSubmitted={() => {}} onCancel={() => {}} />,
    )
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
    expect(screen.queryByText(/doesn't look like a Tenshu pairing code/i)).toBeNull()
  })

  it('warns on a wrong-scheme URI and leaves Continue disabled', async () => {
    const user = userEvent.setup()
    const sdk = sdkWithSubmit(vi.fn())
    renderWithSdk(
      sdk,
      <PairingSubmitScreen deviceName="Web" onSubmitted={() => {}} onCancel={() => {}} />,
    )
    // The abandoned opaque-URI draft scheme is not a tenshu:// URI at all.
    await user.type(
      screen.getByPlaceholderText(/tenshu:\/\/join/i),
      'tenshu-pair:v1?o=o&d=d&k=k&s=s',
    )
    expect(await screen.findByText(/doesn't look like a Tenshu pairing code/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
  })

  it('refuses a retired tenshu://pair code as an unknown role', async () => {
    const user = userEvent.setup()
    const sdk = sdkWithSubmit(vi.fn())
    renderWithSdk(
      sdk,
      <PairingSubmitScreen deviceName="Web" onSubmitted={() => {}} onCancel={() => {}} />,
    )
    // A retired host must not be submittable.
    await user.type(
      screen.getByPlaceholderText(/tenshu:\/\/join/i),
      'tenshu://pair?v=1&d=tenshu1abc&ownerId=owner-1&deviceId=device-2',
    )
    expect(await screen.findByText(/update/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
  })

  it('warns on a canonical URI missing required fields', async () => {
    const user = userEvent.setup()
    const sdk = sdkWithSubmit(vi.fn())
    renderWithSdk(
      sdk,
      <PairingSubmitScreen deviceName="Web" onSubmitted={() => {}} onCancel={() => {}} />,
    )
    await user.type(screen.getByPlaceholderText(/tenshu:\/\/join/i), 'tenshu://join?o=o&d=abc')
    expect(await screen.findByText(/missing required fields/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
  })

  it('accepts a canonical Control URI and submits it (trimmed) with the device name', async () => {
    const user = userEvent.setup()
    const session = {} as PairingSession
    const submit = vi.fn().mockResolvedValue({ ok: true, value: session })
    const onSubmitted = vi.fn()
    renderWithSdk(
      sdkWithSubmit(submit),
      <PairingSubmitScreen deviceName="My Web" onSubmitted={onSubmitted} onCancel={() => {}} />,
    )
    // Trailing whitespace must be stripped before submit.
    await user.type(screen.getByPlaceholderText(/tenshu:\/\/join/i), CANONICAL_URI + '   ')
    const cont = screen.getByRole('button', { name: 'Continue' })
    expect(cont).toBeEnabled()
    await user.click(cont)
    expect(submit).toHaveBeenCalledWith({ pairingUri: CANONICAL_URI, deviceName: 'My Web' })
    await waitFor(() => expect(onSubmitted).toHaveBeenCalledWith(session))
  })

  it('surfaces an invalidUri submit error and does not advance', async () => {
    const user = userEvent.setup()
    const submit = vi.fn().mockResolvedValue({ ok: false, error: { kind: 'invalidUri' } })
    const onSubmitted = vi.fn()
    renderWithSdk(
      sdkWithSubmit(submit),
      <PairingSubmitScreen deviceName="Web" onSubmitted={onSubmitted} onCancel={() => {}} />,
    )
    await user.type(screen.getByPlaceholderText(/tenshu:\/\/join/i), CANONICAL_URI)
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn't read that pairing code/i)
    expect(onSubmitted).not.toHaveBeenCalled()
  })
})

describe('PairingAwaitScreen', () => {
  it('renders the SAS from the observed awaiting status', async () => {
    let emit: ((s: PairingStatus) => void) | null = null
    const session: PairingSession = {
      observe: cb => {
        emit = cb
        return () => {}
      },
      cancel: () => Promise.resolve(),
    }
    render(<PairingAwaitScreen session={session} onTerminal={() => {}} />)
    emit!({ kind: 'awaiting', sas: '482 913' })
    // SasDisplay renders each glyph in its own span; assert the digits.
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent('482913')
    })
  })

  it('forwards a terminal status to onTerminal', async () => {
    let emit: ((s: PairingStatus) => void) | null = null
    const session: PairingSession = {
      observe: cb => {
        emit = cb
        return () => {}
      },
      cancel: () => Promise.resolve(),
    }
    const onTerminal = vi.fn()
    render(<PairingAwaitScreen session={session} onTerminal={onTerminal} />)
    emit!({ kind: 'paired' })
    await waitFor(() => expect(onTerminal).toHaveBeenCalledWith({ kind: 'paired' }))
  })

  it('cancel button burns the session and reports a denied terminal', async () => {
    const user = userEvent.setup()
    const cancel = vi.fn().mockResolvedValue(undefined)
    const session: PairingSession = {
      observe: cb => {
        cb({ kind: 'awaiting', sas: '111 222' })
        return () => {}
      },
      cancel,
    }
    const onTerminal = vi.fn()
    render(<PairingAwaitScreen session={session} onTerminal={onTerminal} />)
    await user.click(await screen.findByRole('button', { name: 'Cancel pairing' }))
    expect(cancel).toHaveBeenCalled()
    expect(onTerminal).toHaveBeenCalledWith({ kind: 'denied' })
  })
})

describe('PairingTerminalScreen', () => {
  it('paired → Set up passkey drives onContinue', async () => {
    const user = userEvent.setup()
    const onContinue = vi.fn()
    render(
      <PairingTerminalScreen
        status={{ kind: 'paired' }}
        onContinue={onContinue}
        onRetry={() => {}}
        onCancel={() => {}}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Set up passkey' }))
    expect(onContinue).toHaveBeenCalled()
  })

  it.each([
    ['denied' as const, 'Pairing was denied'],
    ['expired' as const, 'Pairing expired'],
  ])('%s → Try again drives onRetry', async (kind, heading) => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(
      <PairingTerminalScreen
        status={{ kind }}
        onContinue={() => {}}
        onRetry={onRetry}
        onCancel={() => {}}
      />,
    )
    expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalled()
  })

  it('error → renders the SDK-supplied message and retries', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(
      <PairingTerminalScreen
        status={{ kind: 'error', message: 'relay unreachable' }}
        onContinue={() => {}}
        onRetry={onRetry}
        onCancel={() => {}}
      />,
    )
    expect(screen.getByText('relay unreachable')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalled()
  })
})

// -- Offer flow (inverted direction: this browser shows the QR) ------------

function sdkWithOffer(
  offer: (deviceName: string) => Promise<TResult<OfferPairingSession, PairingSubmitError>>,
): OnboardingSdk {
  return { Pairing: { offer } } as unknown as OnboardingSdk
}

describe('PairingOfferScreen', () => {
  it('starts an offer for the collected device name and renders the waiting instructions', async () => {
    const offer = vi.fn().mockResolvedValue({
      ok: true,
      value: {
        pairingUri: 'tenshu://offer?k=PUB&s=SEC&n=Web',
        observe: (cb: (s: OfferPairingStatus) => void) => {
          cb({ kind: 'showing', pairingUri: 'tenshu://offer?k=PUB&s=SEC&n=Web' })
          return () => {}
        },
        cancel: () => Promise.resolve(),
      } as OfferPairingSession,
    })
    renderWithSdk(
      sdkWithOffer(offer),
      <PairingOfferScreen deviceName="My Web" onTerminal={() => {}} onCancel={() => {}} />,
    )
    await waitFor(() => expect(offer).toHaveBeenCalledWith('My Web'))
    expect(
      await screen.findByText(/Waiting for your existing device to scan/i),
    ).toBeInTheDocument()
  })

  it('forwards a terminal (paired) status to onTerminal', async () => {
    let emit: ((s: OfferPairingStatus) => void) | null = null
    const offer = vi.fn().mockResolvedValue({
      ok: true,
      value: {
        pairingUri: 'tenshu://offer?k=PUB&s=SEC&n=Web',
        observe: (cb: (s: OfferPairingStatus) => void) => {
          emit = cb
          cb({ kind: 'showing', pairingUri: 'tenshu://offer?k=PUB&s=SEC&n=Web' })
          return () => {}
        },
        cancel: () => Promise.resolve(),
      } as OfferPairingSession,
    })
    const onTerminal = vi.fn()
    renderWithSdk(
      sdkWithOffer(offer),
      <PairingOfferScreen deviceName="Web" onTerminal={onTerminal} onCancel={() => {}} />,
    )
    await waitFor(() => expect(emit).not.toBeNull())
    emit!({ kind: 'paired', sas: '482 913' })
    await waitFor(() =>
      expect(onTerminal).toHaveBeenCalledWith({ kind: 'paired', sas: '482 913' }),
    )
  })

  it('surfaces an offer failure without a QR', async () => {
    const offer = vi.fn().mockResolvedValue({ ok: false, error: { kind: 'network' } })
    renderWithSdk(
      sdkWithOffer(offer),
      <PairingOfferScreen deviceName="Web" onTerminal={() => {}} onCancel={() => {}} />,
    )
    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn't reach the Tenshu relay/i)
  })
})

describe('PairingOfferTerminalScreen', () => {
  it('paired → shows the SAS and Set up passkey drives onContinue', async () => {
    const user = userEvent.setup()
    const onContinue = vi.fn()
    render(
      <PairingOfferTerminalScreen
        status={{ kind: 'paired', sas: '482 913' }}
        onContinue={onContinue}
        onRetry={() => {}}
        onCancel={() => {}}
      />,
    )
    expect(screen.getByRole('status')).toHaveTextContent('482913')
    await user.click(screen.getByRole('button', { name: 'Set up passkey' }))
    expect(onContinue).toHaveBeenCalled()
  })

  it.each([
    ['denied' as const, 'Pairing was denied'],
    ['expired' as const, 'Pairing expired'],
  ])('%s → Try again drives onRetry', async (kind, heading) => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(
      <PairingOfferTerminalScreen
        status={{ kind }}
        onContinue={() => {}}
        onRetry={onRetry}
        onCancel={() => {}}
      />,
    )
    expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalled()
  })
})
