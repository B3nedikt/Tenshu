// `MOTION` clips: a video's ~3 s, 256 px, silent MP4 (photos-video-support.md §4), played as a
// short muted loop — on the video tile under the pointer, and in the viewer before Play.
//
// ⚠⚠ `MOTION` never joins `GetPreview`'s ladder: it has its own read, `photo.motionClip`.
// ⚠⚠ One clip in flight, never per tile (§5.3): the class is OnDemand, so each read can pull a
// clip over the network. Reads are serialised here, and a read superseded while it waited is never
// sent.
// ⚠⚠ `bytes: null` is a WAIT, not an answer — never remembered; the next hover asks again.
import { useEffect, useState } from 'react'
import { motionClip } from './proxy'

let chain: Promise<unknown> = Promise.resolve()
let latest = 0

/** Read [path]'s clip after any read already in flight; `null` if superseded or absent. */
export function readMotionClip(path: string): { promise: Promise<Blob | null>; ticket: number } {
  const ticket = ++latest
  const promise = chain.then(async () => {
    if (ticket !== latest) return null
    const result = await motionClip(path)
    if (!result.ok || !result.bytes || result.bytes.length === 0) return null
    const copy = new Uint8Array(result.bytes.length)
    copy.set(result.bytes)
    return new Blob([copy], { type: 'video/mp4' })
  })
  chain = promise.catch(() => null)
  return { promise, ticket }
}

/** [path]'s clip as an object URL while [path] is set; revoked when it changes or unmounts. */
export function useMotionClip(path: string | null): string | null {
  const [state, setState] = useState<{ path: string; url: string } | null>(null)
  useEffect(() => {
    if (!path) return
    let live = true
    let url: string | null = null
    readMotionClip(path)
      .promise.then((blob) => {
        if (!live || !blob) return
        url = URL.createObjectURL(blob)
        setState({ path, url })
      })
      .catch(() => undefined)
    return () => {
      live = false
      if (url) URL.revokeObjectURL(url)
    }
  }, [path])
  return path && state?.path === path ? state.url : null
}
