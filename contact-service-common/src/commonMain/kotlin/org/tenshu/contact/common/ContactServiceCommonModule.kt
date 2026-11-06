package org.tenshu.contact.common

import org.tenshu.contact.ContactInvite
import org.tenshu.contact.ContactInviteDataIdProvider
import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.ContactSourceDataIdProvider
import org.tenshu.contact.ContentId
import org.tenshu.contact.GetContactSourcePhotos
import org.tenshu.contact.Photos
import org.tenshu.contact.SweepContactPhotos
import org.tenshu.contact.InviteAckResult
import org.tenshu.contact.PendingRedemptionInfo
import org.tenshu.contact.Person
import org.tenshu.contact.Avatar
import org.tenshu.contact.GetPersonAvatar
import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.PersonAvatarDataIdProvider
import org.tenshu.contact.PersonDataIdProvider
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.TenshuIdentityDataIdProvider
import org.tenshu.contact.common.impl.ContactInviteDataIdProviderImpl
import org.tenshu.contact.common.impl.ContactInviteDto
import org.tenshu.contact.common.impl.ContactInviteRo
import org.tenshu.contact.common.impl.ContactSourceDataIdProviderImpl
import org.tenshu.contact.common.impl.ContactSourceDto
import org.tenshu.contact.common.impl.ContactSourceRo
import org.tenshu.contact.common.impl.InviteAckResultDto
import org.tenshu.contact.common.impl.PendingRedemptionInfoDto
import org.tenshu.contact.common.impl.PersonAvatarDataIdProviderImpl
import org.tenshu.contact.common.impl.PersonAvatarDto
import org.tenshu.contact.common.impl.PersonAvatarRo
import org.tenshu.contact.common.impl.PersonDataIdProviderImpl
import org.tenshu.contact.common.impl.PersonDto
import org.tenshu.contact.common.impl.PersonRo
import org.tenshu.contact.common.impl.TenshuIdentityDataIdProviderImpl
import org.tenshu.contact.common.impl.TenshuIdentityDto
import org.tenshu.contact.common.impl.TenshuIdentityRo
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.dynamicProperty
import org.tenshu.core.common.module.fulfilValueRequirement
import org.tenshu.core.common.module.valueProperty
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.trait.ContentReferencing

val contactServiceCommonModule = tModule {

    composite(
        interfaceClass = Person::class,
        dataSchema = Person.Schema,
        dtoFactory = ::PersonDto,
        roFactory = { chain, ctx ->
            PersonRo(
                requestChain = chain,
                context = ctx,
                schema = Person.Schema,
            )
        }
    )

    composite(
        interfaceClass = TenshuIdentity::class,
        dataSchema = TenshuIdentity.Schema,
        dtoFactory = ::TenshuIdentityDto,
        roFactory = { chain, ctx ->
            TenshuIdentityRo(
                requestChain = chain,
                context = ctx,
                schema = TenshuIdentity.Schema,
            )
        }
    )

    composite(
        interfaceClass = ContactInvite::class,
        dataSchema = ContactInvite.Schema,
        dtoFactory = ::ContactInviteDto,
        roFactory = { chain, ctx ->
            ContactInviteRo(
                requestChain = chain,
                context = ctx,
                schema = ContactInvite.Schema,
            )
        }
    )

    composite(
        interfaceClass = ContactSource::class,
        dataSchema = ContactSource.Schema,
        dtoFactory = ::ContactSourceDto,
        roFactory = { chain, ctx ->
            ContactSourceRo(
                requestChain = chain,
                context = ctx,
                schema = ContactSource.Schema,
            )
        }
    )

    composite(
        interfaceClass = PersonAvatar::class,
        dataSchema = PersonAvatar.Schema,
        dtoFactory = ::PersonAvatarDto,
        roFactory = { chain, ctx ->
            PersonAvatarRo(
                requestChain = chain,
                context = ctx,
                schema = PersonAvatar.Schema,
            )
        }
    )

    // Transient op-return shapes (mirroring `ShareLinkResult` in
    // :file-service-common): wire-parseable composites, never persisted.
    composite(
        interfaceClass = InviteAckResult::class,
        dataSchema = InviteAckResult.Schema,
        dtoFactory = ::InviteAckResultDto
    )

    composite(
        interfaceClass = PendingRedemptionInfo::class,
        dataSchema = PendingRedemptionInfo.Schema,
        dtoFactory = ::PendingRedemptionInfoDto
    )

    service(ContactService.Schema)

    // ⚠⚠ The deferred `Person.Avatar` needs BOTH of these, and the client module's
    // `roFactory` on top. Registering the op alone leaves the property unknown to the
    // resolver; registering neither makes every select projecting the avatar die as an
    // opaque `error/internal`. Pinned by
    // `PersonAvatarTest.the deferred avatar property resolves through the chain`.
    operation(GetPersonAvatar.Schema)
    dynamicProperty(fromId = Person.ID, property = Person.Avatar)

    // `contacts-app.md` §9.10: a card's big PHOTO lines on the content plane — the
    // `Workout.Streams` registration set exactly. ⚠⚠ The client module's `roFactory` for
    // `GetContactSourcePhotos` is the third half; without it every select projecting
    // `ContactSource.Photos` dies as an opaque `error/internal`.
    operation(GetContactSourcePhotos.Schema)
    dynamicProperty(fromId = ContactSource.ID, property = ContactSource.Photos)
    // The content identity `ContactSource.Photos` is addressed by. A named slot, not a field: it
    // reshapes no positional row.
    valueProperty(fromId = ContactSource.ID, property = ContactSource.ContentId) { TStringDto(it as String) }
    fulfilValueRequirement(
        fromId = ContactSource.ID,
        requirementId = ContentReferencing.CONTENT_ID,
        property = ContactSource.ContentId,
    )
    operation(SweepContactPhotos.Schema)

    trait(
        dataSchema = Person.Schema,
        traitSchema = PersonDataIdProvider.Schema,
        traitFactory = { context, schema ->
            PersonDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = TenshuIdentity.Schema,
        traitSchema = TenshuIdentityDataIdProvider.Schema,
        traitFactory = { context, schema ->
            TenshuIdentityDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = ContactInvite.Schema,
        traitSchema = ContactInviteDataIdProvider.Schema,
        traitFactory = { context, schema ->
            ContactInviteDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = ContactSource.Schema,
        traitSchema = ContactSourceDataIdProvider.Schema,
        traitFactory = { context, schema ->
            ContactSourceDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = PersonAvatar.Schema,
        traitSchema = PersonAvatarDataIdProvider.Schema,
        traitFactory = { context, schema ->
            PersonAvatarDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
}
