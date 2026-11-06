// `fs.*` — Drive's personal file system and its content streams, each mirroring a native
// `FilesRepository` op.
import { FileSystem } from '@tenshu/sdk/files'
import { ContentStream } from '@tenshu/sdk/content'
import type { GatewayModule } from './module'
import { asRecord } from './params'

export const fsGateway: GatewayModule = {
  handlers: {
    // List a folder via the in-process FileSystem. `params` is the
    // structured-cloned `{ path }` the subdomain sent. Returns [] when
    // there's no session yet.
    'fs.list': async (params) => {
      const path = readPath(params)
      return FileSystem.list(path)
    },

    // Name search across the whole drive. The predicate runs proxy-side, so only
    // matching rows cross the bridge.
    'fs.search': async (params) => FileSystem.search(readQuery(params)),

    // Is this file's content on this device? ⚠ Per-row and LAZY by contract: the op's
    // KDoc forbids eager resolution across a listing (Android's Binder watchdog).
    'fs.hasLocalData': async (params) => FileSystem.hasLocalData(readPath(params)),

    // The `limit` most-recently-modified entries (Home). Mirrors `FilesRepository.recentFiles`.
    'fs.recentFiles': async (params) => {
      return FileSystem.recentFiles(readLimit(params))
    },

    // Upload a file's bytes to `parentPath/fileName`. `data` is
    // the structured-cloned Int8Array the subdomain sent. Mirrors
    // `FilesRepository.uploadFile`.
    'fs.uploadFile': async (params) => {
      const { parentPath, fileName, data } = readUpload(params)
      return FileSystem.uploadFile(parentPath, fileName, data)
    },

    // Create a folder at `path`. Mirrors
    // `FilesRepository.createFolder` (idempotent: an existing folder
    // resolves false, callers can treat that as already-present).
    'fs.createFolder': async (params) => {
      return FileSystem.createFolder(readPath(params))
    },

    // Mutation boundaries — each mirrors a native
    // `FilesRepository` op and resolves boolean ok. The path-derivation
    // for rename/move lives in the Kotlin SDK (mirroring the native
    // repository), so these handlers stay thin pass-throughs.
    'fs.rename': async (params) => {
      const { path, newName } = readRename(params)
      return FileSystem.rename(path, newName)
    },
    'fs.move': async (params) => {
      const { sourcePath, destinationFolderPath } = readMove(params)
      return FileSystem.move(sourcePath, destinationFolderPath)
    },
    'fs.delete': async (params) => {
      return FileSystem.delete(readPath(params))
    },

    // File/folder metadata snapshot (the
    // Details surface). Returns the walker object or null. Mirrors
    // `FilesRepository.fileDetails`.
    'fs.details': async (params) => {
      return FileSystem.details(readPath(params))
    },

    // Modification history (the Activity
    // surface), newest first. Mirrors `FilesRepository.fileActivity`.
    'fs.activity': async (params) => {
      return FileSystem.activity(readPath(params))
    },

    // Personal-file byte read — the Details drawer's Open/preview action.
    // Returns the file's bytes (Int8Array) or null. Mirrors the lazy
    // shared-file read; the SDK reads from the IDB-backed FileDatabase.
    'fs.getData': async (params) => {
      return FileSystem.getData(readPath(params))
    },

    // Streaming (drive-streaming-preview.md §3.5): open a seekable
    // session, read a byte range (only the covering chunks decrypt), close it.
    // The Service Worker drives these to answer HTTP Range requests for a
    // <video src> — bounded memory, no whole-file blob URL.
    'fs.openContentStream': async (params) => {
      return ContentStream.openStream(readPath(params))
    },
    'fs.readContentRange': async (params) => {
      const r = readContentRange(params)
      return ContentStream.readRange(r.handle, r.offset, r.length)
    },
    'fs.closeContentStream': async (params) => {
      await ContentStream.close(readHandle(params))
      return null
    },

    // A WEBP thumbnail (Int8Array) fit within width × height, or null when
    // the file isn't a decodable image. Drives the lazy row thumbnails.
    'fs.getPreview': async (params) => {
      const { path, width, height } = readPreview(params)
      return FileSystem.getPreview(path, width, height)
    },
    // The lightbox's large read. Carries the answering class and the covering rung's
    // tri-state back across the bridge — see `NativeLargePreview`.
    'fs.getLargePreview': async (params) => {
      const { path, px } = readLargePreview(params)
      return FileSystem.getLargePreview(path, px)
    },
  },
}

function readPath(params: unknown): string {
  const v = asRecord(params).path
  return typeof v === 'string' ? v : '/'
}

function readQuery(params: unknown): string {
  const v = asRecord(params).query
  return typeof v === 'string' ? v : ''
}

function readLimit(params: unknown): number {
  const v = asRecord(params).limit
  return typeof v === 'number' && v > 0 ? Math.floor(v) : 20
}

function readPreview(params: unknown): { path: string; width: number; height: number } {
  const r = asRecord(params)
  if (typeof r.path !== 'string' || typeof r.width !== 'number' || typeof r.height !== 'number') {
    throw new Error('fs.getPreview requires { path, width, height }')
  }
  return { path: r.path, width: r.width, height: r.height }
}

function readLargePreview(params: unknown): { path: string; px: number } {
  const r = asRecord(params)
  if (typeof r.path !== 'string' || typeof r.px !== 'number') {
    throw new Error('fs.getLargePreview requires { path, px }')
  }
  return { path: r.path, px: r.px }
}

function readHandle(params: unknown): string {
  const v = asRecord(params).handle
  if (typeof v !== 'string') throw new Error('a content-stream call requires { handle }')
  return v
}

function readContentRange(params: unknown): { handle: string; offset: number; length: number } {
  const r = asRecord(params)
  if (typeof r.handle !== 'string' || typeof r.offset !== 'number' || typeof r.length !== 'number') {
    throw new Error('fs.readContentRange requires { handle, offset, length }')
  }
  return { handle: r.handle, offset: r.offset, length: r.length }
}

function readRename(params: unknown): { path: string; newName: string } {
  const r = asRecord(params)
  if (typeof r.path !== 'string' || typeof r.newName !== 'string') {
    throw new Error('fs.rename requires { path, newName }')
  }
  return { path: r.path, newName: r.newName }
}

function readMove(params: unknown): { sourcePath: string; destinationFolderPath: string } {
  const r = asRecord(params)
  if (typeof r.sourcePath !== 'string' || typeof r.destinationFolderPath !== 'string') {
    throw new Error('fs.move requires { sourcePath, destinationFolderPath }')
  }
  return { sourcePath: r.sourcePath, destinationFolderPath: r.destinationFolderPath }
}

function readUpload(
  params: unknown,
): { parentPath: string; fileName: string; data: Int8Array } {
  const r = asRecord(params)
  if (
    typeof r.parentPath !== 'string' ||
    typeof r.fileName !== 'string' ||
    !(r.data instanceof Int8Array)
  ) {
    throw new Error('fs.uploadFile requires { parentPath, fileName, data: Int8Array }')
  }
  return { parentPath: r.parentPath, fileName: r.fileName, data: r.data }
}
