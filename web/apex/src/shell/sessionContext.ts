// What `SessionLayout` hands the shell under it. Apart from the component so fast refresh keeps
// working (react-refresh/only-export-components).
import { useOutletContext } from 'react-router'

export interface SessionOutletContext {
  readonly deviceName: string
}

/** The signed-in device's name. Only valid in a shell rendered under `SessionLayout`. */
export function useSessionDevice(): string {
  return useOutletContext<SessionOutletContext>().deviceName
}
