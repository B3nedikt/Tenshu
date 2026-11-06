import { breadcrumbs } from '../lib/paths'

// Folder trail, root first. Every ancestor is a button that navigates there;
// the current folder is inert text.
export function Breadcrumbs({
  path,
  onNavigate,
}: {
  path: string
  onNavigate: (path: string) => void
}) {
  const crumbs = breadcrumbs(path)
  return (
    <nav aria-label="Breadcrumb" className="flex flex-wrap items-center gap-1 text-sm">
      {crumbs.map((crumb, i) => {
        const isLast = i === crumbs.length - 1
        return (
          <span key={crumb.path} className="flex items-center gap-1">
            {i > 0 && <span className="text-text-3">/</span>}
            {isLast ? (
              <span className="font-medium text-text">{crumb.label}</span>
            ) : (
              <button
                type="button"
                onClick={() => onNavigate(crumb.path)}
                className="rounded px-1 text-text-2 hover:bg-surface-3 hover:text-text"
              >
                {crumb.label}
              </button>
            )}
          </span>
        )
      })}
    </nav>
  )
}
