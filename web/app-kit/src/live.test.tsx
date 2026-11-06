import { act, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from './testing/render'
import { createLiveContext, useLive, type Live } from './live'

function stream<T>() {
  const subs: Array<{ onValue: (v: T) => void; onError: (e: Error) => void }> = []
  return {
    subs,
    subscribe: vi.fn((onValue: (v: T) => void, onError: (e: Error) => void) => {
      const sub = { onValue, onError }
      subs.push(sub)
      return () => {
        subs.splice(subs.indexOf(sub), 1)
      }
    }),
  }
}

describe('useLive', () => {
  it('seeds from the read, then takes every push; a dead stream says so and retry resubscribes', async () => {
    const s = stream<string[]>()
    const seed = vi.fn(async () => ['seeded'])
    let live: Live<string[]> | null = null
    function Probe() {
      live = useLive(['things'], seed, s.subscribe)
      return <p>{live.data?.join(',') ?? 'pending'}</p>
    }
    renderWithQueryClient(<Probe />)
    expect(await screen.findByText('seeded')).toBeInTheDocument()

    act(() => s.subs[0]!.onValue(['pushed']))
    expect(await screen.findByText('pushed')).toBeInTheDocument()

    act(() => s.subs[0]!.onError(new Error('collector died')))
    await waitFor(() => expect(live!.streamError).toEqual(new Error('collector died')))

    await act(async () => live!.retry())
    await waitFor(() => expect(s.subscribe).toHaveBeenCalledTimes(2))
    expect(live!.streamError).toBeNull()
  })
})

describe('createLiveContext', () => {
  it('fails loudly outside its provider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const [, useState] = createLiveContext<{ n: number }>('DemoLiveProvider')
    function Reader() {
      return <p>{useState().n}</p>
    }
    expect(() => renderWithQueryClient(<Reader />)).toThrow('DemoLiveProvider is missing')
  })

  it('hands the provider’s value to readers', () => {
    const [Context, useState] = createLiveContext<{ n: number }>('DemoLiveProvider')
    function Reader() {
      return <p>n={useState().n}</p>
    }
    renderWithQueryClient(
      <Context.Provider value={{ n: 3 }}>
        <Reader />
      </Context.Provider>,
    )
    expect(screen.getByText('n=3')).toBeInTheDocument()
  })
})

