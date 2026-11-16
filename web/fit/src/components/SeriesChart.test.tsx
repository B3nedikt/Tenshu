// A time series against elapsed time (fit-web-parity.md §2.1–2.2): axes, readout, a shareable cursor.
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { SeriesChart } from './SeriesChart'

const series = [
  { t: 0, v: 100 },
  { t: 600, v: 160 },
  { t: 1200, v: 130 },
]

describe('SeriesChart', () => {
  it('a time axis from 0 to the end, value ticks, and a readout at the pointer', () => {
    render(<SeriesChart series={series} ariaLabel="HR" valueText={(v) => `${v} bpm`} tickText={(v) => String(v)} />)
    expect(screen.getByText('0:00')).toBeInTheDocument()
    expect(screen.getByText('20:00')).toBeInTheDocument()
    const chart = screen.getByRole('img', { name: 'HR' })
    chart.getBoundingClientRect = () => ({ left: 0, top: 0, width: 640, height: 160, right: 640, bottom: 160, x: 0, y: 0, toJSON() {} })
    // The plot spans 44 … 632 px: its middle is 10:00.
    fireEvent.pointerMove(chart, { clientX: 44 + 294 })
    expect(screen.getByTestId('series-readout')).toHaveTextContent('160 bpm · 10:00')
    fireEvent.pointerLeave(chart)
    expect(screen.queryByTestId('series-readout')).toBeNull()
  })

  it('a controlled cursor follows its owner (the other charts, the map) and reports the pointer', () => {
    const onCursor = vi.fn()
    const { rerender } = render(<SeriesChart series={series} ariaLabel="HR" valueText={(v) => `${v}`} cursor={1200} onCursor={onCursor} />)
    expect(screen.getByTestId('series-readout')).toHaveTextContent('130 · 20:00')
    fireEvent.keyDown(screen.getByRole('img', { name: 'HR' }), { key: 'Home' })
    expect(onCursor).toHaveBeenLastCalledWith(0)
    rerender(<SeriesChart series={series} ariaLabel="HR" valueText={(v) => `${v}`} cursor={null} onCursor={onCursor} />)
    expect(screen.queryByTestId('series-readout')).toBeNull()
  })

  it('inverted (pace): a smaller value is drawn higher', () => {
    render(<SeriesChart series={[{ t: 0, v: 300 }, { t: 60, v: 360 }]} ariaLabel="Pace" valueText={(v) => `${v}`} invert />)
    const [a, b] = screen.getByTestId('series-line').getAttribute('points')!.split(' ').map((p) => Number(p.split(',')[1]))
    expect(a!).toBeLessThan(b!)
  })

  it('the average is always on the plot, even outside the samples (pace: distance / time with stops)', () => {
    render(<SeriesChart series={[{ t: 0, v: 290 }, { t: 60, v: 330 }]} ariaLabel="Pace" valueText={(v) => `${v}`} invert average={390} averageLabel="Avg" />)
    const y = Number(screen.getByTestId('series-average').querySelector('line')!.getAttribute('y1'))
    expect(y).toBeGreaterThan(0)
    expect(y).toBeLessThan(160 - 22)
  })

  it('a pause longer than gapSec breaks the line instead of bridging it', () => {
    render(<SeriesChart series={[{ t: 0, v: 300 }, { t: 30, v: 310 }, { t: 300, v: 305 }, { t: 330, v: 300 }]} ariaLabel="Pace" valueText={String} gapSec={45} />)
    expect(screen.getAllByTestId('series-line')).toHaveLength(2)
  })

  it('charts sharing a cursor share one time axis: the same moment at the same x', () => {
    render(
      <>
        <SeriesChart testId="a" series={[{ t: 0, v: 1 }, { t: 1_000, v: 2 }]} ariaLabel="A" valueText={String} duration={2_000} cursor={500} />
        <SeriesChart testId="b" series={[{ t: 0, v: 1 }, { t: 2_000, v: 2 }]} ariaLabel="B" valueText={String} duration={2_000} cursor={500} />
      </>,
    )
    const x = (id: string) => screen.getByTestId(id).querySelector('[data-testid="series-cursor"] line')!.getAttribute('x1')
    expect(x('a')).toBe(x('b'))
  })

  it('a flat series still gets distinct gridlines', () => {
    const { container } = render(<SeriesChart series={[{ t: 0, v: 0 }, { t: 60, v: 0 }]} ariaLabel="Flat" valueText={(v) => `${v} m`} />)
    const labels = [...container.querySelectorAll('text')].map((t) => t.textContent).filter((s) => s!.endsWith(' m'))
    expect(new Set(labels).size).toBe(labels.length)
  })
})
