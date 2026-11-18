// Where to install the native apps. You start with Control: it holds the realm,
// and every other app joins through it. iOS gets its link once Control has an
// App Store id (`TENSHU_CONTROL_APP_STORE_ID`, unset so far); until then its
// entry carries no URL and renders as "coming soon".
export const CONTROL_PLAY_URL =
  'https://play.google.com/store/apps/details?id=app.tenshu.control.android'

// The Mac download page (Homebrew or a direct download), language-neutral: Caddy redirects to the
// visitor's language. Built by support-pages/macosDownload.ts.
export const CONTROL_MAC_DOWNLOAD_URL = 'https://tenshu.app/apps/control/download/macos'
