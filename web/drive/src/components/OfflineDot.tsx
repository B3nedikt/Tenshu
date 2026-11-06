import { useQuery } from '@tanstack/react-query'
import { hasLocalData } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { useInView } from '../lib/useInView'

// W6. Marks whether a file's bytes are on this device. A file without them still opens — the
// bytes fetch from the content plane on demand (W1) — so this says "available offline", not
// "available".
//
// ⚠⚠ Gated on visibility, exactly like FileThumbnail, because the op's contract requires it:
// resolving presence eagerly across a listing trips Android's Binder watchdog. On web each probe
// is also a bridge round trip, so eager resolution would be one per row in a folder.
//
// ⚠ Silent when the bytes ARE local: the common case earns no ink. Only the cloud state — the
// one with a consequence, namely "this needs the network" — gets a mark.
export function OfflineDot({ path, isFolder }: { path: string; isFolder: boolean }) {
  const [ref, inView] = useInView<HTMLSpanElement>()

  const { data: local } = useQuery({
    queryKey: queryKeys.hasLocalData(path),
    queryFn: () => hasLocalData(path),
    // A folder has no bytes of its own — asking would always answer false and mark every folder
    // as "in the cloud", which is meaningless rather than merely wrong.
    enabled: !isFolder && inView,
    // Presence changes when the file is opened (fetch) or evicted; the listing refetches on
    // those paths, so within a session this is stable.
    staleTime: Infinity,
  })

  return (
    <span ref={ref} className="inline-flex w-4 shrink-0 justify-center" aria-hidden={local !== false}>
      {local === false && (
        <span title="Not on this device — opening will download it" className="text-xs text-text-3">
          ☁
        </span>
      )}
    </span>
  )
}
