// `place.*` — the Maps bridge (web-app-suite.md Wave 3, Maps run A): saved places and place detail
// over `:place-service`, through Timeline's place reads (`PlacesBook.kt`). No map: rendering and
// `mapRegion` are W-MAP's. ⚠⚠ No saved / note / list methods: those have no store
// (`maps-app.md` §Web scope 5a).
//
// Every call resolves `{ ok, … }` with a catalogue-key `error.kind`; the list also has a push form
// under `subscriptions`. The detail is snapshot + refetch.
import { Maps } from '@tenshu/sdk/maps'
import type { GatewayModule } from './module'
import { readId, readString } from './params'

export const placeGateway: GatewayModule = {
  handlers: {
    'place.list': async () => Maps.places(),
    'place.get': async (params) => Maps.place(readId(params, 'placeId', 'place.get')),
    // A blank name is the SDK's typed failure, so `name` is only required to be a string here.
    'place.rename': async (params) =>
      Maps.renamePlace(readId(params, 'placeId', 'place.rename'), readString(params, 'name', 'place.rename')),
  },
  subscriptions: {
    // Genuine push over the relations' Flows. ⚠ `fail` is wired through: a collector that dies ends
    // the subscriber's stream with a terminal error instead of freezing its screen.
    'place.list.observe': (_params, emit, fail) => Maps.observePlaces((result) => emit(result), fail),
  },
}
