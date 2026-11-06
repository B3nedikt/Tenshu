// Production wiring for the commerce client: the real HTTP API plus the realm's
// ownerId, read through the SDK.
//
// ⚠ Isolated because importing `@tenshu/sdk/onboarding` drags the Kotlin bundle
// into whatever imports it. Only the composition root does.
import { Realm } from '@tenshu/sdk/onboarding'
import { Storage } from '@tenshu/sdk/storage'
import { CommercePublisherClient, type PublisherClient } from './publisherClient'

export const realPublisherClient: PublisherClient = new CommercePublisherClient(
  () => Realm.ownerId(),
  undefined,
  undefined,
  // The SDK verifies the signature and the owner before it saves.
  entitlement => Storage.applyEntitlement(entitlement),
)
