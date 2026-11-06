// The anonymous Mode-A viewer page (relay-metadata-minimisation.md F3): the file's name and MIME
// come only from the sealed metadata `ShareViewer` opened, and every failure — a wrong key
// (`decrypt`), a share without readable metadata (`meta`), or an "ok" result that somehow lacks
// them — renders a visible error, never a download under a placeholder name.
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { ShareFetchResult } from '@tenshu/sdk/share'

const fetchMock = vi.fn<(id: string, k: string, relay: string) => Promise<ShareFetchResult>>()

vi.mock('@tenshu/sdk/share', () => ({
  ShareViewer: {
    fetch: (id: string, k: string, relay: string) => fetchMock(id, k, relay),
  },
}))

import ShareViewerRoute from './[shareId]'
import { OPAQUE_BLOB_TYPE } from './inlineSafety'

function result(overrides: Partial<ShareFetchResult>): ShareFetchResult {
  return {
    ok: false,
    errorKind: null,
    bytes: null,
    fileName: null,
    mimeType: null,
    ...overrides,
  }
}

function renderAt(hash: string) {
  window.history.replaceState(null, '', `/s/share-1${hash}`)
  return render(
    <MemoryRouter initialEntries={['/s/share-1']}>
      <Routes>
        <Route path="s/:shareId" element={<ShareViewerRoute />} />
      </Routes>
    </MemoryRouter>,
  )
}

// Every blob the page minted an object URL for.
const blobs: Blob[] = []

function shared(fileName: string, mimeType: string) {
  fetchMock.mockResolvedValue(
    result({ ok: true, bytes: new Int8Array([60, 104, 49, 62]), fileName, mimeType }),
  )
  renderAt('#k=key')
}

describe('ShareViewerRoute', () => {
  beforeEach(() => {
    fetchMock.mockReset()
    blobs.length = 0
    URL.createObjectURL = vi.fn((blob: Blob | MediaSource) => {
      blobs.push(blob as Blob)
      return 'blob:mock'
    })
    URL.revokeObjectURL = vi.fn()
  })
  afterEach(() => {
    window.history.replaceState(null, '', '/')
  })

  it('names the download from the sealed metadata and scrubs the key from the URL', async () => {
    fetchMock.mockResolvedValue(
      result({
        ok: true,
        bytes: new Int8Array([1, 2, 3]),
        fileName: 'report.zip',
        mimeType: 'application/zip',
      }),
    )
    renderAt('#k=secret-key')

    const link = await screen.findByRole('link', { name: 'Download report.zip' })
    expect(link).toHaveAttribute('download', 'report.zip')
    expect(fetchMock).toHaveBeenCalledWith('share-1', 'secret-key', expect.any(String))
    expect(window.location.hash).toBe('')
  })

  it('shows the decrypt error for a wrong key, with no file name', async () => {
    fetchMock.mockResolvedValue(result({ errorKind: 'decrypt' }))
    renderAt('#k=wrong')

    expect(await screen.findByRole('alert')).toHaveTextContent("This share couldn't be decrypted")
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('shows the meta error when the share carries no readable details', async () => {
    fetchMock.mockResolvedValue(result({ errorKind: 'meta' }))
    renderAt('#k=key')

    expect(await screen.findByRole('alert')).toHaveTextContent('missing its file details')
  })

  it('refuses an ok result without metadata instead of inventing a name', async () => {
    fetchMock.mockResolvedValue(result({ ok: true, bytes: new Int8Array([1]) }))
    renderAt('#k=key')

    expect(await screen.findByRole('alert')).toHaveTextContent('missing its file details')
    expect(screen.queryByText(/shared-file/)).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('shows the no-key error without calling the relay', async () => {
    renderAt('')

    expect(await screen.findByRole('alert')).toHaveTextContent('missing its decryption key')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  // ⚠⚠ The MIME type is the sharer's claim. A same-origin blob that renders as a document runs
  // script as the apex, so these types must never be framed, imaged, or carried by a navigable
  // blob — opening the download link in a new tab must download, not render.
  it.each([
    ['page.html', 'text/html'],
    ['page.html', 'Text/HTML; charset=utf-8'],
    ['logo.svg', 'image/svg+xml'],
    ['page.xhtml', 'application/xhtml+xml'],
    ['feed.xml', 'text/xml'],
    ['data.xml', 'application/xml'],
    ['page.mht', 'multipart/related'],
  ])('never renders %s (%s) as a document, and its blob is opaque', async (fileName, mimeType) => {
    shared(fileName, mimeType)

    const link = await screen.findByRole('link', { name: `Download ${fileName}` })
    expect(link).toHaveAttribute('download', fileName)
    expect(document.querySelector('iframe, img, object, embed')).toBeNull()
    expect(blobs.map((b) => b.type)).toEqual([OPAQUE_BLOB_TYPE])
  })

  it('previews a raster image inline, with its own type', async () => {
    shared('photo.png', 'image/png')

    expect(await screen.findByRole('img', { name: 'photo.png' })).toHaveAttribute('src', 'blob:mock')
    expect(blobs.map((b) => b.type)).toEqual(['image/png'])
  })

  it('previews a PDF in a frame, with its own type', async () => {
    shared('report.pdf', 'application/pdf')

    expect(await screen.findByTitle('report.pdf')).toHaveProperty('tagName', 'IFRAME')
    expect(blobs.map((b) => b.type)).toEqual(['application/pdf'])
  })

  it('previews plain text as text', async () => {
    shared('notes.txt', 'text/plain')

    await screen.findByRole('link', { name: 'Download notes.txt' })
    expect(document.querySelector('pre')).not.toBeNull()
    expect(document.querySelector('iframe, img')).toBeNull()
    expect(blobs.map((b) => b.type)).toEqual(['text/plain'])
  })
})
