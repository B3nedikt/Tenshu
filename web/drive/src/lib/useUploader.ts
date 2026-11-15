// Uploading files (and whole folders) into a Drive folder — what the sidebar's "+ New" menu and a
// drop anywhere in the window both start (web-apps-layout.md §3.3). Moved out of the file
// browser's old Upload button unchanged, so the folder-pick rules (W7) stay in one place.
import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { createFolder, uploadFile } from './proxy'
import { joinPath } from './paths'

// A folder pick arrives as a FLAT list; the tree exists only in `webkitRelativePath`
// ("photos/2024/a.jpg"). A plain file pick leaves it empty, so the name is the whole path.
export function relativePathOf(file: File): string {
  const relative = (file as File & { webkitRelativePath?: string }).webkitRelativePath
  return relative && relative.length > 0 ? relative : file.name
}

export interface UploadProgress {
  readonly done: number
  readonly total: number
}

export function useUploader(onUploaded: (parentPath: string) => void) {
  const [progress, setProgress] = useState<UploadProgress | null>(null)

  const mutation = useMutation({
    mutationFn: async ({ parentPath, files }: { parentPath: string; files: readonly File[] }) => {
      // W7. Every directory the pick implies, each created once and parents before children —
      // `uploadFile` puts a file in an existing folder, it does not make the path on the way.
      const directories = new Set<string>()
      for (const file of files) {
        const relative = relativePathOf(file)
        const cut = relative.lastIndexOf('/')
        if (cut < 0) continue
        let accumulated = ''
        for (const segment of relative.slice(0, cut).split('/')) {
          accumulated = accumulated ? `${accumulated}/${segment}` : segment
          directories.add(accumulated)
        }
      }
      // Parents before children, stated as depth because that is the actual requirement.
      const ordered = [...directories].sort((a, b) => a.split('/').length - b.split('/').length)
      for (const directory of ordered) {
        // ⚠ Return value ignored deliberately — `createFolder` answers false both for a real
        // failure and for "it already exists" (documented idempotent). A directory that genuinely
        // could not be created surfaces as the upload into it failing, which is reported.
        await createFolder(joinPath(parentPath, directory))
      }

      setProgress({ done: 0, total: files.length })
      for (const [index, file] of files.entries()) {
        const relative = relativePathOf(file)
        const cut = relative.lastIndexOf('/')
        const target = cut < 0 ? parentPath : joinPath(parentPath, relative.slice(0, cut))
        // ⚠ Still one whole file in memory at a time: `uploadFile` takes a complete byte array and
        // there is no chunked upload op to bridge. W7 fixed the download side only.
        const data = new Int8Array(await file.arrayBuffer())
        const ok = await uploadFile(target, file.name, data)
        if (!ok) throw new Error(`Couldn't upload “${file.name}”`)
        setProgress({ done: index + 1, total: files.length })
      }
      return parentPath
    },
    onSuccess: (parentPath) => onUploaded(parentPath),
    onSettled: () => setProgress(null),
  })

  return {
    upload: (parentPath: string, files: readonly File[]) => {
      if (files.length > 0) mutation.mutate({ parentPath, files })
    },
    pending: mutation.isPending,
    progress,
    error: mutation.isError ? mutation.error.message : null,
    reset: mutation.reset,
  }
}
