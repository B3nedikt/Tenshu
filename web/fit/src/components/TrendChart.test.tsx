// The web bar chart (fit-web-parity.md §2.1): axis, emphasis, goal and average, hover and keyboard readout.
import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { TrendChart, type TrendPoint } from './TrendChart'

const points: TrendPoint[] = [3_000, 9_000, 4_500, 0, 7_000, 8_200, 5_100].map((value, i) => ({
  key: `2026-11-${String(9 + i).padStart(2, '0')}`,
  value,
  label: ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][i]!,
  readout: `${value} steps · day ${i}`,
}))

function renderChart() {
  return render(<TrendChart points={points} ariaLabel="Steps, the last 7 days" tickText={(v) => `${v / 1000}K`} goal={8_000} goalLabel="Goal 8,000" average={5_257} />)
}

describe('TrendChart', () => {
  it('draws one bar a day with labels, gridlines with unit ticks, the goal and the average', () => {
    const { container } = renderChart()
    expect(screen.getAllByTestId('bar')).toHaveLength(7)
    expect(screen.getByText('Sun')).toBeInTheDocument()
    expect(screen.getByText('Sat')).toBeInTheDocument()
    expect(screen.getByText('10K')).toBeInTheDocument()
    expect(screen.getByText('Goal 8,000')).toBeInTheDocument()
    expect(screen.getByTestId('average-line')).toBeInTheDocument()
    // A zero day draws no bar shape, but keeps its slot.
    expect(container.querySelector('[data-key="2026-11-12"]')!.getAttribute('d')).toBe('')
  })

  it('today (the last bar) is the emphasised one until another is read', () => {
    renderChart()
    const emphasised = () => screen.getAllByTestId('bar').filter((b) => b.hasAttribute('data-emphasis')).map((b) => b.getAttribute('data-key'))
    expect(emphasised()).toEqual(['2026-11-15'])
    const chart = screen.getByRole('img', { name: 'Steps, the last 7 days' })
    // The first ← reads today, the next steps back a day.
    fireEvent.keyDown(chart, { key: 'ArrowLeft' })
    expect(screen.getByTestId('readout')).toHaveTextContent('5100 steps · day 6')
    fireEvent.keyDown(chart, { key: 'ArrowLeft' })
    expect(emphasised()).toEqual(['2026-11-14'])
    expect(screen.getByTestId('readout')).toHaveTextContent('8200 steps · day 5')
  })

  it('the pointer reads the bar under it; leaving clears it', () => {
    renderChart()
    const chart = screen.getByRole('img', { name: 'Steps, the last 7 days' })
    // jsdom: the chart is 640 px wide from x = 0; the plot starts at 44 px, seven slots of 84.3 px.
    chart.getBoundingClientRect = () => ({ left: 0, top: 0, width: 640, height: 200, right: 640, bottom: 200, x: 0, y: 0, toJSON() {} })
    fireEvent.pointerMove(chart, { clientX: 44 + 84 * 1 + 40 })
    expect(screen.getByTestId('readout')).toHaveTextContent('9000 steps · day 1')
    // Announced, not only drawn.
    expect(chart.getAttribute('aria-describedby')).toBeTruthy()
    expect(document.getElementById(chart.getAttribute('aria-describedby')!)).toHaveTextContent('9000 steps · day 1')
    fireEvent.pointerLeave(chart)
    expect(screen.queryByTestId('readout')).toBeNull()
  })

  it('Home and End jump to the ends, Escape clears', () => {
    renderChart()
    const chart = screen.getByRole('img', { name: 'Steps, the last 7 days' })
    fireEvent.keyDown(chart, { key: 'Home' })
    expect(screen.getByTestId('readout')).toHaveTextContent('day 0')
    fireEvent.keyDown(chart, { key: 'End' })
    expect(screen.getByTestId('readout')).toHaveTextContent('day 6')
    fireEvent.keyDown(chart, { key: 'Escape' })
    expect(screen.queryByTestId('readout')).toBeNull()
  })
})
