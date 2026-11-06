// The shell pieces every app renders. Each takes its copy as props, so an app without i18n (Drive)
// and a localised one (Contacts) share the markup.
export { AppErrorBoundary, type CrashCopy } from './ErrorBoundary'
export {
  RuntimeLeaseBanner,
  STILL_CONNECTING_AFTER_MS,
  type LeaseCopy,
  type RuntimeLeaseBannerProps,
} from './RuntimeLeaseBanner'
export { SyncHealthNotice, type SyncHealthNoticeProps } from './SyncHealthNotice'
export { StreamNotice, type StreamNoticeProps } from './StreamNotice'
