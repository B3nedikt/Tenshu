import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FileViewer } from './FileViewer'

const getData = vi.fn()
const getLargePreview = vi.fn()
vi.mock('../lib/proxy', () => ({
  getData: (path: string) => getData(path),
  getLargePreview: (path: string, px: number) => getLargePreview(path, px),
  // FileThumbnail (filmstrip) — no thumbnails here.
  getPreview: () => Promise.resolve({ ok: true, bytes: null }),
}))
const openVideoStream = vi.fn()
vi.mock('../lib/videoStream', () => ({
  openVideoStream: (...args: unknown[]) => openVideoStream(...args),
}))

function file(path: string) {
  return { path, size: 10, lastModified: Date.UTC(2024, 0, 1), createdAt: 0, isFolder: false, itemCount: 0 }
}
const FILES = [file('/Docs/a.txt'), file('/Docs/b.txt'), file('/Docs/c.txt')]

let urls = 0
const createObjectURL = vi.fn(() => `blob:${++urls}`)
const revokeObjectURL = vi.fn()

beforeEach(() => {
  urls = 0
  vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
  getLargePreview.mockResolvedValue({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'absent' })
  getData.mockImplementation(() => Promise.resolve({ ok: true, bytes: new Int8Array([104, 105]) }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  getData.mockReset()
  getLargePreview.mockReset()
  openVideoStream.mockReset()
  createObjectURL.mockClear()
  revokeObjectURL.mockClear()
})

type Props = ComponentProps<typeof FileViewer>

function renderViewer(overrides: Partial<Props> = {}) {
  const props: Props = {
    files: FILES,
    path: '/Docs/b.txt',
    onNavigate: vi.fn(),
    onClose: vi.fn(),
    onShare: vi.fn(),
    onDelete: vi.fn(),
    detailsOpen: false,
    onToggleDetails: vi.fn(),
    ...overrides,
  }
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const ui = (p: Props) => (
    <QueryClientProvider client={queryClient}>
      <FileViewer {...p} />
    </QueryClientProvider>
  )
  const result = render(ui(props))
  return { ...result, props, rerender: (p: Partial<Props>) => result.rerender(ui({ ...props, ...p })) }
}

describe('FileViewer', () => {
  it('renders the current file and its position', async () => {
    renderViewer()
    const dialog = screen.getByRole('dialog', { name: 'b.txt' })
    expect(dialog).toHaveFocus()
    expect(screen.getByText(/2 of 3/)).toBeInTheDocument()
    expect(await screen.findByText('hi')).toBeInTheDocument()
    expect(getData).toHaveBeenCalledWith('/Docs/b.txt')
    expect(screen.getByRole('button', { name: 'Back to Docs' })).toBeInTheDocument()
  })

  it('arrows and ←/→ navigate to the neighbours', async () => {
    const { props } = renderViewer()
    await userEvent.click(screen.getByRole('button', { name: 'Previous file' }))
    expect(props.onNavigate).toHaveBeenLastCalledWith('/Docs/a.txt')
    await userEvent.click(screen.getByRole('button', { name: 'Next file' }))
    expect(props.onNavigate).toHaveBeenLastCalledWith('/Docs/c.txt')
    fireEvent.keyDown(window, { key: 'ArrowLeft' })
    expect(props.onNavigate).toHaveBeenLastCalledWith('/Docs/a.txt')
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(props.onNavigate).toHaveBeenLastCalledWith('/Docs/c.txt')
  })

  it('stops at the ends', () => {
    const first = renderViewer({ path: '/Docs/a.txt' })
    expect(screen.getByRole('button', { name: 'Previous file' })).toBeDisabled()
    fireEvent.keyDown(window, { key: 'ArrowLeft' })
    expect(first.props.onNavigate).not.toHaveBeenCalled()
    first.unmount()

    const last = renderViewer({ path: '/Docs/c.txt' })
    expect(screen.getByRole('button', { name: 'Next file' })).toBeDisabled()
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(last.props.onNavigate).not.toHaveBeenCalled()
  })

  it('Escape and Space close', () => {
    const { props } = renderViewer()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(props.onClose).toHaveBeenCalledTimes(1)
    fireEvent.keyDown(window, { key: ' ' })
    expect(props.onClose).toHaveBeenCalledTimes(2)
  })

  it('ignores keys typed into an input', () => {
    const { props } = renderViewer({ details: <input aria-label="note" />, detailsOpen: true })
    const input = screen.getByRole('textbox', { name: 'note' })
    fireEvent.keyDown(input, { key: ' ' })
    fireEvent.keyDown(input, { key: 'ArrowLeft' })
    expect(props.onClose).not.toHaveBeenCalled()
    expect(props.onNavigate).not.toHaveBeenCalled()
  })

  it('the filmstrip navigates', async () => {
    const { props } = renderViewer()
    const strip = screen.getByRole('navigation', { name: 'Files in this folder' })
    expect(strip.querySelector('[aria-current="true"]')).toHaveAccessibleName('b.txt')
    await userEvent.click(screen.getByRole('button', { name: 'c.txt' }))
    expect(props.onNavigate).toHaveBeenCalledWith('/Docs/c.txt')
  })

  it('ⓘ toggles details and shows them when open', async () => {
    const { props, rerender } = renderViewer({ details: <p>the details</p> })
    const info = screen.getByRole('button', { name: 'Details' })
    expect(info).toHaveAttribute('aria-pressed', 'false')
    expect(screen.queryByText('the details')).not.toBeInTheDocument()
    await userEvent.click(info)
    expect(props.onToggleDetails).toHaveBeenCalledTimes(1)

    rerender({ detailsOpen: true })
    expect(screen.getByRole('button', { name: 'Details' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('complementary', { name: 'File details' })).toHaveTextContent('the details')
  })

  it('Share and Delete report the open path', async () => {
    const { props } = renderViewer()
    await userEvent.click(screen.getByRole('button', { name: 'Share' }))
    await userEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(props.onShare).toHaveBeenCalledWith('/Docs/b.txt')
    expect(props.onDelete).toHaveBeenCalledWith('/Docs/b.txt')
  })

  // ⚠⚠ The X-5 data-loss guard, carried over from the lightbox: a stand-in image is a 2048 px
  // thumbnail class, so Download must fetch the ORIGINAL — never link the URL on screen.
  it('a stand-in image downloads the original, not the shown URL', async () => {
    getLargePreview.mockResolvedValue({
      ok: true,
      bytes: new Int8Array([0xff, 0xd8, 0xff, 0xe0, 9]),
      thumbnailClass: 'xxlarge',
      coveringRung: 'present',
    })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    renderViewer({ files: [file('/p.jpg')], path: '/p.jpg' })

    const img = await screen.findByRole('img', { name: 'p.jpg' })
    expect(img).toHaveAttribute('src', 'blob:1')
    expect(getData).not.toHaveBeenCalled()
    // A button, not a link over the rung.
    expect(screen.queryByRole('link', { name: 'Download' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Download' }))
    await waitFor(() => expect(getData).toHaveBeenCalledWith('/p.jpg'))
    await waitFor(() => expect(click).toHaveBeenCalled())
    const saved = click.mock.contexts[0] as HTMLAnchorElement
    expect(saved.download).toBe('p.jpg')
    expect(saved.href).not.toContain('blob:1')
    click.mockRestore()
  })

  it('a video has no Download', async () => {
    openVideoStream.mockResolvedValue({ url: '/__stream/1', close: vi.fn() })
    renderViewer({ files: [file('/v.mp4')], path: '/v.mp4' })
    await waitFor(() => expect(openVideoStream).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: 'Download' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Download' })).not.toBeInTheDocument()
  })

  it('a kind with no preview offers Download instead of downloading itself', async () => {
    renderViewer({ files: [file('/x.bin')], path: '/x.bin' })
    expect(await screen.findByText('No preview')).toBeInTheDocument()
    expect(getData).not.toHaveBeenCalled()
  })

  it('a stale load for a previous path does not replace the current preview', async () => {
    let resolveA: (v: unknown) => void = () => {}
    getData.mockImplementation((path: string) =>
      path === '/Docs/a.txt'
        ? new Promise((r) => (resolveA = r))
        : Promise.resolve({ ok: true, bytes: new Int8Array([98, 98]) }),
    )
    const { rerender } = renderViewer({ path: '/Docs/a.txt' })
    rerender({ path: '/Docs/b.txt' })
    expect(await screen.findByText('bb')).toBeInTheDocument()

    await act(async () => resolveA({ ok: true, bytes: new Int8Array([97, 97]) }))
    expect(screen.getByText('bb')).toBeInTheDocument()
    expect(screen.queryByText('aa')).not.toBeInTheDocument()
    // The stale preview was never shown, and is freed.
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:2')
  })

  it('revokes the object URL when the path changes and on unmount', async () => {
    const { rerender, unmount } = renderViewer({ path: '/Docs/a.txt' })
    await screen.findByText('hi')
    expect(revokeObjectURL).not.toHaveBeenCalled()
    rerender({ path: '/Docs/b.txt' })
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:1')
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledTimes(2))
    unmount()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:2')
  })

  // Review findings (L3): keys that belong to someone else must not close or move the viewer.
  it('ignores a key something already handled (the list that just opened it, a Radix dialog)', () => {
    const { onClose } = renderViewer().props
    const event = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true })
    event.preventDefault()
    window.dispatchEvent(event)
    expect(onClose).not.toHaveBeenCalled()
  })

  it('ignores keys typed in a dialog it opened (portalled outside the viewer)', () => {
    const { onClose, onNavigate } = renderViewer().props
    const outside = document.createElement('button')
    document.body.appendChild(outside)
    fireEvent.keyDown(outside, { key: 'Escape' })
    fireEvent.keyDown(outside, { key: 'ArrowRight' })
    expect(onClose).not.toHaveBeenCalled()
    expect(onNavigate).not.toHaveBeenCalled()
    outside.remove()
  })
})
