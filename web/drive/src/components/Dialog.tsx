// Drive's dialogs now come from the shared web component layer
// (@tenshu/web-tokens/ui), built on Radix for focus-trap, Escape, scroll-lock
// and proper aria (design-system Phase 3). Re-exported here so the existing
// `./Dialog` imports across Drive keep working unchanged.
export { Dialog, PromptDialog, ConfirmDialog } from '@tenshu/web-tokens/ui'
