// The state a map shows instead of a map. Never a blank box.
export function MapUnavailable({ title, reason, className }: { title: string; reason: string; className?: string }) {
  return (
    <div role="status" data-testid="map-unavailable" className={className}>
      <strong>{title}</strong>
      <p>{reason}</p>
    </div>
  )
}

