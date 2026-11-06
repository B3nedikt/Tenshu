// Where a tap goes: the places list, or one place. A detail replaces the list; Back returns to it.
export type Detail = { readonly kind: 'place'; readonly id: string }

export type Open = (detail: Detail) => void
