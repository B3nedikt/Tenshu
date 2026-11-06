import type { ShareLink } from '@tenshu/sdk/share'

// The public host that serves Mode-A share links (matches the native
// `ShareLinkUrl.DEFAULT_HOST`). The recipient opens `…/s/{shareId}#k={fragment}`
// with no Tenshu session; the apex's anonymous `ShareViewer` fetches + decrypts.
const SHARE_HOST = 'https://tenshu.app'

// Assemble the copy-able share URL. The key fragment lives in the URL hash —
// it is the read capability (A11) and never goes on the wire (the relay only
// ever sees the `shareId`).
export function shareUrl(link: ShareLink): string {
  return `${SHARE_HOST}/s/${link.shareId}#k=${link.urlFragment}`
}
