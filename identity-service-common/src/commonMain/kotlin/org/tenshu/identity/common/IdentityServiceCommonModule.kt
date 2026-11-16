package org.tenshu.identity.common

import org.tenshu.core.Composite
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.schemaMigration
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.trait.Syncable
import org.tenshu.core.trait.TraitRequirement
import org.tenshu.identity.AdmissionCertGrant
import org.tenshu.identity.AdmissionKeyAnnouncement
import org.tenshu.identity.Device
import org.tenshu.identity.Entitlement
import org.tenshu.identity.EntitlementUnverifiedError
import org.tenshu.identity.GetDevices
import org.tenshu.identity.GetRealmMembers
import org.tenshu.identity.GetRealmInvitations
import org.tenshu.identity.GetPendingAcceptances
import org.tenshu.identity.GetSharedRealmMemberships
import org.tenshu.identity.GrantedMembership
import org.tenshu.identity.DeviceHardware
import org.tenshu.identity.DeviceRecord
import org.tenshu.identity.MembershipRecord
import org.tenshu.identity.AwaitedAcceptance
import org.tenshu.identity.RealmKeyDelivery
import org.tenshu.identity.RealmGrantor
import org.tenshu.identity.RealmKeyGeneration
import org.tenshu.identity.OutboundAcceptance
import org.tenshu.identity.DeviceLastSeen
import org.tenshu.identity.Member
import org.tenshu.identity.MemberAuthorityKey
import org.tenshu.identity.OwnedRealmMaster
import org.tenshu.identity.PendingGrant
import org.tenshu.identity.RetainedRealmKey
import org.tenshu.identity.IdentityService
import org.tenshu.identity.SharedRealmMembership

val identityServiceCommonModule = tModule {

    composite(
        interfaceClass = Device::class,
        dataSchema = Device.Schema,
        dtoFactory = ::DeviceDto,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, Device.Schema), Device {
                override val schema: CompositeSchema get() = Device.Schema
            }
        }
    )

    // Locally-derived "last heard from" per device — never synced (see DeviceLastSeen's KDoc).
    // Registered here anyway: the proxy writes it and the roster reads it through the chain, both
    // of which need the type resolvable.
    composite(
        interfaceClass = DeviceLastSeen::class,
        dataSchema = DeviceLastSeen.Schema,
        dtoFactory = ::DeviceLastSeenDto
    )

    // Self-published hardware model per device (DeviceHardware's KDoc: one writer per row).
    composite(
        interfaceClass = DeviceHardware::class,
        dataSchema = DeviceHardware.Schema,
        dtoFactory = ::DeviceHardwareDto
    )

    // Shared-realm membership (tenshu-rei.md §2.3 / §3.4): one Syncable row per
    // shared realm a member belongs to, carrying A12 wrapped to the member's A13.
    composite(
        interfaceClass = SharedRealmMembership::class,
        dataSchema = SharedRealmMembership.Schema,
        dtoFactory = ::SharedRealmMembershipDto
    )

    // Publisher-signed entitlement (storage-entitlement-sync.md §3.1 +
    // tenshu-publisher.md §10.2): one Syncable row per owner, propagated to every
    // paired device so a 2nd device learns of a subscription bought on the first.
    composite(
        interfaceClass = Entitlement::class,
        dataSchema = Entitlement.Schema,
        dtoFactory = ::EntitlementDto
    )

    // ⚠ Must be registered even though today's only consumer is proxy-internal (it is recorded,
    // never thrown across IPC): an unregistered error type fails to parse on a client, which would
    // turn the very case it names — "this client cannot verify your entitlement" — back into an
    // opaque error/internal. Same reasoning as `RelayRouteUnsupportedError`.
    composite(
        interfaceClass = EntitlementUnverifiedError::class,
        dataSchema = EntitlementUnverifiedError.Schema,
        dtoFactory = ::EntitlementUnverifiedErrorDto
    )

    // Shared-realm member record (shared-realm-membership-grant.md §5, D5 / gap G4):
    // the Q25 in-realm roster — one row per member, encrypted under Kr inside the
    // shared realm, written by a member as the acceptance signal.
    composite(
        interfaceClass = Member::class,
        dataSchema = Member.Schema,
        dtoFactory = ::MemberDto
    )

    // Member-authority keypair (shared-realm-membership-grant.md §3, D2 / asset A26):
    // one Syncable row per shared realm in the member's own personal realm, so
    // every device of theirs self-binds without the owner online.
    composite(
        interfaceClass = MemberAuthorityKey::class,
        dataSchema = MemberAuthorityKey.Schema,
        dtoFactory = ::MemberAuthorityKeyDto
    )

    // Owner-side realm master keypair custody (shared-realm-membership-grant.md §12,
    // GR-6 / asset A1): one Syncable row per realm this device owns, in the owner's
    // own personal realm, so grant/remove survive a restart and any owner device can
    // act — not just the creating one.
    composite(
        interfaceClass = OwnedRealmMaster::class,
        dataSchema = OwnedRealmMaster.Schema,
        dtoFactory = ::OwnedRealmMasterDto
    )

    // Owner-side granted-member roster (shared-realm-membership-grant.md §12, GR-6):
    // one Syncable row per member the owner granted, mapping the in-realm memberId
    // back to the minted A26 public key so the owner can revoke by member.
    composite(
        interfaceClass = GrantedMembership::class,
        dataSchema = GrantedMembership.Schema,
        dtoFactory = ::GrantedMembershipDto
    )

    // Quarantined inbound grant (shared-realm-membership-grant.md §12, GR-6 / D4):
    // a sealed grant the poll picked up but the user has not yet accepted, stored so
    // explicit accept can consume it later.
    composite(
        interfaceClass = PendingGrant::class,
        dataSchema = PendingGrant.Schema,
        dtoFactory = ::PendingGrantDto
    )

    // Retained-keyring row (shared-realm-membership-grant.md §13.4, GR-7): one
    // Syncable row per retired Kr per realm in the member's own personal realm, so
    // the trial-decrypt keyring (read content written before a rotation) survives a
    // restart and propagates to all the member's devices.
    composite(
        interfaceClass = RetainedRealmKey::class,
        dataSchema = RetainedRealmKey.Schema,
        dtoFactory = ::RetainedRealmKeyDto
    )

    // A33 migration announcement (delegated-device-admission-authority.md §8 P4):
    // a device's locally-minted admission public key, A4-signed, synced so an
    // A1-holder can answer with a cert grant. No private key on the row.
    composite(
        interfaceClass = AdmissionKeyAnnouncement::class,
        dataSchema = AdmissionKeyAnnouncement.Schema,
        dtoFactory = ::AdmissionKeyAnnouncementDto
    )

    // A33 cert delivery + Q2 re-root (P4): the A1-holder's depth-1 cert chain for
    // an announced admission key. Public, verifiable material only.
    composite(
        interfaceClass = AdmissionCertGrant::class,
        dataSchema = AdmissionCertGrant.Schema,
        dtoFactory = ::AdmissionCertGrantDto
    )

    service(
        IdentityService.Schema
    )
    // v1 → v2 (§5b R6, and `shared-realm-membership-records.md` MR-1 + RO D7, folded into the same
    // unshipped bump): `DeviceRecord`, `MembershipRecord`, `AwaitedAcceptance`, `OutboundAcceptance`, `RealmKeyDelivery`, `RealmGrantor` and `RealmKeyGeneration` are new types — nothing reshaped, so the step is empty. It is
    // registered because the chain is what the readable floor is derived from (HR #9).
    // ⚠ MR-3 reshapes `Member` (two optional fields appended), so the step is no longer empty: it maps the
    // frozen v1 shape onto the live one. The new fields are optional, so a v1 row arrives with them absent
    // — and is then not listed (an unsigned row vouches for nothing; no production shares, RO D8).
    schemaMigration(
        org.tenshu.core.common.schema.migration.schemaMigration(IdentityService.ID, fromVersion = 1) {
            migration(typeId = Member.ID, from = MEMBER_AT_V1, to = Member.Schema) {}
        },
    )

    // Signed device records (§5b R6): removals and re-admissions, synced, verified by readers.
    composite(
        interfaceClass = DeviceRecord::class,
        dataSchema = DeviceRecord.Schema,
        dtoFactory = ::DeviceRecordDto,
    )
    trait(
        dataSchema = DeviceRecord.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        },
    )
    trait(
        dataSchema = DeviceRecord.Schema,
        traitSchema = DeviceRecord.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    // Awaited acceptances (read-only-members D7): an owner device polls these for a member's acceptance.
    composite(
        interfaceClass = AwaitedAcceptance::class,
        dataSchema = AwaitedAcceptance.Schema,
        dtoFactory = ::AwaitedAcceptanceDto,
    )
    trait(
        dataSchema = AwaitedAcceptance.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        },
    )
    trait(
        dataSchema = AwaitedAcceptance.Schema,
        traitSchema = AwaitedAcceptance.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    // Realm key generations (GR-7): the order members adopt an owner's keys in.
    composite(
        interfaceClass = RealmKeyGeneration::class,
        dataSchema = RealmKeyGeneration.Schema,
        dtoFactory = ::RealmKeyGenerationDto,
    )
    trait(
        dataSchema = RealmKeyGeneration.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        },
    )
    trait(
        dataSchema = RealmKeyGeneration.Schema,
        traitSchema = RealmKeyGeneration.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    // Realm grantors (GR-7): which contact granted a member each realm — where its re-keys arrive.
    composite(
        interfaceClass = RealmGrantor::class,
        dataSchema = RealmGrantor.Schema,
        dtoFactory = ::RealmGrantorDto,
    )
    trait(
        dataSchema = RealmGrantor.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        },
    )
    trait(
        dataSchema = RealmGrantor.Schema,
        traitSchema = RealmGrantor.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    // Realm key deliveries (GR-7 catch-up): which key each member was last sent.
    composite(
        interfaceClass = RealmKeyDelivery::class,
        dataSchema = RealmKeyDelivery.Schema,
        dtoFactory = ::RealmKeyDeliveryDto,
    )
    trait(
        dataSchema = RealmKeyDelivery.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        },
    )
    trait(
        dataSchema = RealmKeyDelivery.Schema,
        traitSchema = RealmKeyDelivery.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    // Outbound acceptances (read-only-members D7): a member's undelivered acceptance drop.
    composite(
        interfaceClass = OutboundAcceptance::class,
        dataSchema = OutboundAcceptance.Schema,
        dtoFactory = ::OutboundAcceptanceDto,
    )
    trait(
        dataSchema = OutboundAcceptance.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        },
    )
    trait(
        dataSchema = OutboundAcceptance.Schema,
        traitSchema = OutboundAcceptance.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    // Signed membership records (`shared-realm-membership-records.md` MR-1): who is in a shared realm.
    composite(
        interfaceClass = MembershipRecord::class,
        dataSchema = MembershipRecord.Schema,
        dtoFactory = ::MembershipRecordDto,
    )
    trait(
        dataSchema = MembershipRecord.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        },
    )
    trait(
        dataSchema = MembershipRecord.Schema,
        traitSchema = MembershipRecord.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    operation(GetDevices.Schema)
    operation(GetSharedRealmMemberships.Schema)
    operation(GetRealmMembers.Schema)
    operation(GetRealmInvitations.Schema)
    operation(GetPendingAcceptances.Schema)

    trait(
        dataSchema = Device.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = Device.Schema,
        traitSchema = Device.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = DeviceHardware.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = DeviceHardware.Schema,
        traitSchema = DeviceHardware.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    // ⚠ DataIdProvider only, deliberately no `Syncable` twin: the row is keyed by deviceId so
    // `saveData` can find it, but it must never leave this device (DeviceLastSeen's KDoc).
    trait(
        dataSchema = DeviceLastSeen.Schema,
        traitSchema = DeviceLastSeen.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = SharedRealmMembership.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = SharedRealmMembership.Schema,
        traitSchema = SharedRealmMembership.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = Entitlement.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = Entitlement.Schema,
        traitSchema = Entitlement.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = Member.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = Member.Schema,
        traitSchema = Member.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = MemberAuthorityKey.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = MemberAuthorityKey.Schema,
        traitSchema = MemberAuthorityKey.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = OwnedRealmMaster.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = OwnedRealmMaster.Schema,
        traitSchema = OwnedRealmMaster.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = GrantedMembership.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = GrantedMembership.Schema,
        traitSchema = GrantedMembership.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = PendingGrant.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = PendingGrant.Schema,
        traitSchema = PendingGrant.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = RetainedRealmKey.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = RetainedRealmKey.Schema,
        traitSchema = RetainedRealmKey.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = AdmissionKeyAnnouncement.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = AdmissionKeyAnnouncement.Schema,
        traitSchema = AdmissionKeyAnnouncement.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = AdmissionCertGrant.Schema,
        traitSchema = Syncable.Schema,
        traitFactory = { _, schema ->
            val composite = schema as Composite
            object : Syncable, Composite by composite {
                override val schema get() = Syncable.Schema
                override val traitRequirements: TList<TraitRequirement> = SimpleTList(emptyList())
            }
        }
    )

    trait(
        dataSchema = AdmissionCertGrant.Schema,
        traitSchema = AdmissionCertGrant.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
}
