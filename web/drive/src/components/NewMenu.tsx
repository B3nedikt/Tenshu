import { useRef, type ChangeEvent } from 'react'
import { Menu, MenuItem } from '@tenshu/web-tokens/ui'
import type { UploadProgress } from '../lib/useUploader'

// The sidebar's "+ New" — the native add-file sheet (web-apps-layout.md §3.3): create a folder, or
// upload files or a whole folder. ("Upload images" folds into Upload files: the browser's picker
// already filters.) Dropping files anywhere in the window does the same as Upload files.
export function NewMenu({
  onNewFolder,
  onUpload,
  progress,
  busy,
}: {
  onNewFolder: () => void
  onUpload: (files: File[]) => void
  progress: UploadProgress | null
  busy: boolean
}) {
  const filesInput = useRef<HTMLInputElement>(null)
  const folderInput = useRef<HTMLInputElement>(null)

  function onPick(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? [])
    event.target.value = '' // allow re-picking the same file
    if (files.length > 0) onUpload(files)
  }

  const label = busy
    ? progress && progress.total > 1
      ? `Uploading ${progress.done}/${progress.total}…`
      : 'Uploading…'
    : '+ New'

  return (
    <>
      <Menu
        align="start"
        trigger={
          <button
            type="button"
            disabled={busy}
            className="w-full rounded-xl bg-primary px-4 py-2.5 text-sm font-semibold text-on-primary hover:bg-primary/90 disabled:opacity-60"
          >
            {label}
          </button>
        }
      >
        <MenuItem onSelect={onNewFolder}>New folder</MenuItem>
        <MenuItem onSelect={() => filesInput.current?.click()}>Upload files</MenuItem>
        <MenuItem onSelect={() => folderInput.current?.click()}>Upload folder</MenuItem>
      </Menu>
      <input ref={filesInput} type="file" multiple hidden data-testid="upload-files" onChange={onPick} />
      {/* `webkitdirectory` is the only way to pick a directory from an <input>; React does not
          know the attribute, hence the cast. Supported in every browser Drive targets. */}
      <input
        ref={folderInput}
        type="file"
        multiple
        hidden
        data-testid="upload-folder"
        onChange={onPick}
        {...({ webkitdirectory: '' } as Record<string, string>)}
      />
    </>
  )
}
