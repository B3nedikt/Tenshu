// A Blob download (calendar-app.md §Web scope W4): the browser's own save, no server round-trip.
export function downloadText(text: string, fileName: string, type: string): void {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  // After the click has been handled; revoking synchronously can cancel the download in some engines.
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

// A file name from a calendar name: no path separators or characters filesystems refuse.
export function safeFileName(name: string, fallback: string): string {
  const cleaned = [...name]
    .map((ch) => (ch.charCodeAt(0) < 0x20 || '\\/:*?"<>|'.includes(ch) ? ' ' : ch))
    .join('')
    .replace(/ +/g, ' ')
    .trim()
  return cleaned || fallback
}
