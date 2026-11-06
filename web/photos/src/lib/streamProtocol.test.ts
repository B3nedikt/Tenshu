import { describe, expect, it } from 'vitest'
import {
  STREAM_RESPONSE_WINDOW,
  clampToWindow,
  parseRangeHeader,
  streamPath,
  tokenFromPath,
} from './streamProtocol'

describe('streamProtocol', () => {
  describe('parseRangeHeader', () => {
    const total = 1000

    it('returns null for a missing header (caller serves from the start)', () => {
      expect(parseRangeHeader(null, total)).toBeNull()
      expect(parseRangeHeader(undefined, total)).toBeNull()
      expect(parseRangeHeader('', total)).toBeNull()
    })

    it('parses an open-ended range to the end', () => {
      expect(parseRangeHeader('bytes=0-', total)).toEqual({ start: 0, end: 999 })
      expect(parseRangeHeader('bytes=200-', total)).toEqual({ start: 200, end: 999 })
    })

    it('parses a closed range and clamps the end to the last byte', () => {
      expect(parseRangeHeader('bytes=100-199', total)).toEqual({ start: 100, end: 199 })
      expect(parseRangeHeader('bytes=100-99999', total)).toEqual({ start: 100, end: 999 })
    })

    it('parses a suffix range (last N bytes)', () => {
      expect(parseRangeHeader('bytes=-300', total)).toEqual({ start: 700, end: 999 })
      // A suffix larger than the file clamps the start to 0.
      expect(parseRangeHeader('bytes=-5000', total)).toEqual({ start: 0, end: 999 })
    })

    it('returns null for unsatisfiable / malformed / multi-range headers', () => {
      expect(parseRangeHeader('bytes=1000-', total)).toBeNull() // start past EOF
      expect(parseRangeHeader('bytes=500-100', total)).toBeNull() // start > end
      expect(parseRangeHeader('bytes=-0', total)).toBeNull() // empty suffix
      expect(parseRangeHeader('bytes=abc-def', total)).toBeNull()
      expect(parseRangeHeader('bytes=0-1,2-3', total)).toBeNull() // multi-range unsupported
      expect(parseRangeHeader('items=0-1', total)).toBeNull()
    })
  })

  describe('clampToWindow', () => {
    it('leaves a range within the window untouched', () => {
      expect(clampToWindow({ start: 0, end: 100 }, 1024)).toEqual({ start: 0, end: 100 })
    })

    it('pulls the end in to the window size', () => {
      expect(clampToWindow({ start: 0, end: 10_000 }, 1024)).toEqual({ start: 0, end: 1023 })
      expect(clampToWindow({ start: 500, end: 10_000 }, 1024)).toEqual({ start: 500, end: 1523 })
    })

    it('defaults to the 2 MiB response window', () => {
      expect(clampToWindow({ start: 0, end: 100_000_000 })).toEqual({
        start: 0,
        end: STREAM_RESPONSE_WINDOW - 1,
      })
    })
  })

  describe('streamPath / tokenFromPath', () => {
    it('round-trips a token through the virtual URL', () => {
      const token = 'abc-123'
      expect(tokenFromPath(streamPath(token))).toBe(token)
    })

    it('encodes tokens with URL-unsafe characters', () => {
      const token = 'a/b c'
      const path = streamPath(token)
      expect(path).not.toContain(' ')
      expect(tokenFromPath(path)).toBe(token)
    })

    it('returns null for a non-stream pathname', () => {
      expect(tokenFromPath('/files/photo.jpg')).toBeNull()
      expect(tokenFromPath('/__tenshu_stream__/')).toBeNull()
    })
  })
})
