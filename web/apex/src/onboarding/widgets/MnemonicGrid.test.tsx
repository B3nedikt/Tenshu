// Recovery-phrase grid + clipboard-countdown helper (roadmap 6.4, UX
// §4b.3). The countdown auto-clears the clipboard after the window, so it
// gets coverage; the grid just renders the words.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ClipboardCountdownButton, MnemonicGrid } from './MnemonicGrid'

describe('MnemonicGrid', () => {
  it('renders every word in order with its 1-based index', () => {
    render(<MnemonicGrid words={['alpha', 'bravo', 'charlie']} />)
    const items = screen.getAllByRole('listitem').map(li => li.textContent)
    expect(items).toEqual([' 1alpha', ' 2bravo', ' 3charlie'])
  })
})

describe('ClipboardCountdownButton', () => {
  const writeText = vi.fn<(text: string) => Promise<void>>()

  afterEach(() => {
    // Defined per-test below; remove without clobbering the rest of navigator.
    delete (navigator as unknown as { clipboard?: unknown }).clipboard
  })

  function mockClipboard() {
    writeText.mockReset()
    writeText.mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })
  }

  it('copies the text and shows the clearing-countdown banner', async () => {
    mockClipboard()
    render(<ClipboardCountdownButton text="my secret phrase" seconds={2} />)
    fireEvent.click(screen.getByRole('button', { name: /copy/i }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('my secret phrase'))
    expect(screen.getByRole('status')).toHaveTextContent('2')
  })

  it('wipes the clipboard to empty once the window elapses', async () => {
    mockClipboard()
    render(<ClipboardCountdownButton text="my secret phrase" seconds={1} />)
    fireEvent.click(screen.getByRole('button', { name: /copy/i }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('my secret phrase'))
    // Real 1s window + the trailing reset; allow generous slack.
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith(''), { timeout: 3000 })
    await waitFor(() => expect(screen.queryByRole('status')).toBeNull())
  })
})
