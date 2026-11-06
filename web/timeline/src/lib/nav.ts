// Where a tap goes. The app has two tabs (the day list and the places list) and three detail
// screens; a detail opened from a detail stacks, so Back returns to where the user was.
export type Detail =
  | { readonly kind: 'moment'; readonly id: string }
  | { readonly kind: 'trip'; readonly id: string }
  | { readonly kind: 'place'; readonly id: string }

export type Open = (detail: Detail) => void
