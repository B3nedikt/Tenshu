import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, resetProxy, failure, workoutRow } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { MAX_ACTIVITY_FILE_BYTES } = await import('../lib/bounds')
const { ImportPanel } = await import('./ImportPanel')

function gpxFile(name = 'run.gpx', body = '<gpx/>') {
  return new File([body], name, { type: 'application/gpx+xml' })
}

async function choose(file: File) {
  // `applyAccept: false`: the picker's filter is a hint the user can override ("All files"), so the
  // SDK's typed refusal for a .FIT or .txt is the real gate, and is what these tests reach.
  await userEvent.setup({ applyAccept: false }).upload(screen.getByTestId('import-input'), file)
}

beforeEach(() => {
  resetProxy()
})

describe('ImportPanel', () => {
  it('sends the file’s bytes as an Int8Array and lists what was stored', async () => {
    proxy.importActivityFile.mockResolvedValue({
      ok: true,
      workouts: [workoutRow('gpx-1', 0, { title: 'Morning run' }), workoutRow('gpx-2', 0, { title: '', activityType: 'Cycling' })],
      streamsPending: [],
    })
    renderWithProviders(<ImportPanel />)
    await choose(gpxFile('two.gpx', '<gpx>ab</gpx>'))
    const result = await screen.findByTestId('import-result')
    expect(result).toHaveTextContent('Saved 2 workouts from two.gpx.')
    expect(result).toHaveTextContent('Morning run')
    expect(result).toHaveTextContent('Ride')
    expect(result).not.toHaveTextContent('upload when online')
    const [name, bytes] = proxy.importActivityFile.mock.calls[0]!
    expect(name).toBe('two.gpx')
    expect(bytes).toBeInstanceOf(Int8Array)
    expect(new TextDecoder().decode(bytes)).toBe('<gpx>ab</gpx>')
    // History re-reads its head so the new rows show.
    await waitFor(() => expect(proxy.importActivityFile).toHaveBeenCalledTimes(1))
  })

  it('streamsPending reads as saved, with the upload still to come — never as a failure', async () => {
    proxy.importActivityFile.mockResolvedValue({
      ok: true,
      workouts: [workoutRow('gpx-1', 0, { title: 'Morning run' }), workoutRow('gpx-2', 0, { title: 'Evening run' })],
      streamsPending: ['gpx-2'],
    })
    renderWithProviders(<ImportPanel />)
    await choose(gpxFile())
    const result = await screen.findByTestId('import-result')
    expect(result).toHaveTextContent('Saved 2 workouts')
    const pending = result.querySelector('[data-id="gpx-2"]')!
    expect(pending).toHaveTextContent('Saved; route and heart rate will upload when online')
    expect(result.querySelector('[data-id="gpx-1"]')).not.toHaveTextContent('upload when online')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('two tracks with the same start are reported as the SDK stored them: one workout', async () => {
    proxy.importActivityFile.mockResolvedValue({ ok: true, workouts: [workoutRow('gpx-1', 0, { title: 'Second' })], streamsPending: [] })
    renderWithProviders(<ImportPanel />)
    await choose(gpxFile('dup.gpx'))
    const result = await screen.findByTestId('import-result')
    expect(result).toHaveTextContent('Saved 1 workout from dup.gpx.')
    expect(result.querySelectorAll('li')).toHaveLength(1)
    expect(result).toHaveTextContent('Second')
  })

  it.each([
    ['error_fit_unsupported_format', 'notes.txt', 'Only GPX and TCX files can be imported.'],
    ['error_fit_binary_unsupported', 'ride.fit', 'Binary .FIT files can’t be imported on the web yet.'],
    ['error_fit_malformed_file', 'broken.gpx', 'damaged or isn’t a valid GPX or TCX file'],
    ['error_fit_no_workouts', 'empty.gpx', 'This file contains no workouts.'],
    ['error_fit_file_too_large', 'huge.gpx', 'too large to import'],
  ])('%s renders by kind, and says nothing was saved', async (kind, name, copy) => {
    proxy.importActivityFile.mockResolvedValue(failure(kind))
    renderWithProviders(<ImportPanel />)
    await choose(gpxFile(name))
    const result = await screen.findByTestId('import-result')
    expect(result).toHaveAttribute('data-kind', kind)
    expect(result).toHaveTextContent(copy)
    expect(result).not.toHaveTextContent('may already be saved')
    expect(result).not.toHaveTextContent(`diagnostic for ${kind}`)
  })

  it('a file over the cap is refused here, typed, before any bytes cross the bridge', async () => {
    renderWithProviders(<ImportPanel />)
    const big = gpxFile('huge.gpx')
    Object.defineProperty(big, 'size', { value: MAX_ACTIVITY_FILE_BYTES + 1 })
    await choose(big)
    const result = await screen.findByTestId('import-result')
    expect(result).toHaveAttribute('data-kind', 'error_fit_file_too_large')
    expect(proxy.importActivityFile).not.toHaveBeenCalled()
  })

  it('a service failure partway says some may be saved and a retry is safe', async () => {
    proxy.importActivityFile.mockResolvedValue(failure('error_internal'))
    renderWithProviders(<ImportPanel />)
    await choose(gpxFile())
    const result = await screen.findByTestId('import-result')
    expect(result).toHaveAttribute('data-kind', 'error_internal')
    expect(result).toHaveTextContent('may already be saved')
    expect(result).toHaveTextContent('Importing it again is safe.')
  })

  it('a rejected bridge call is its own sentence', async () => {
    proxy.importActivityFile.mockRejectedValue(new Error('fit.importActivityFile timed out'))
    renderWithProviders(<ImportPanel />)
    await choose(gpxFile())
    expect(await screen.findByTestId('import-result')).toHaveTextContent('Tenshu took too long to answer.')
  })
})
