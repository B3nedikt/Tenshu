package org.tenshu.contact.client

import org.tenshu.contact.AwaitInviteAck
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.contact.ContactService
import org.tenshu.contact.CreateContactInvite
import org.tenshu.contact.ClearPersonAvatar
import org.tenshu.contact.CreatePerson
import org.tenshu.contact.DeletePerson
import org.tenshu.contact.GetContactSas
import org.tenshu.contact.ImportContact
import org.tenshu.contact.ListPendingRedemptions
import org.tenshu.contact.RedeemContactInvite
import org.tenshu.contact.GetPersonAvatar
import org.tenshu.contact.GetContactSourcePhotos
import org.tenshu.contact.LinkTenshuIdentity
import org.tenshu.contact.MergePersons
import org.tenshu.contact.RenamePerson
import org.tenshu.contact.RevokeContactInvite
import org.tenshu.contact.SaveContactSource
import org.tenshu.contact.SetPersonAvatar
import org.tenshu.contact.UnlinkTenshuIdentity
import org.tenshu.contact.VerifyTenshuIdentity
import org.tenshu.contact.client.impl.AwaitInviteAckRo
import org.tenshu.contact.client.impl.CompleteContactHandshakeRo
import org.tenshu.contact.client.impl.ContactServiceRo
import org.tenshu.contact.client.impl.CreateContactInviteRo
import org.tenshu.contact.client.impl.ClearPersonAvatarRo
import org.tenshu.contact.client.impl.CreatePersonRo
import org.tenshu.contact.client.impl.DeletePersonRo
import org.tenshu.contact.client.impl.GetContactSasRo
import org.tenshu.contact.client.impl.ImportContactRo
import org.tenshu.contact.client.impl.ListPendingRedemptionsRo
import org.tenshu.contact.client.impl.RedeemContactInviteRo
import org.tenshu.contact.client.impl.GetPersonAvatarRo
import org.tenshu.contact.client.impl.GetContactSourcePhotosRo
import org.tenshu.contact.client.impl.LinkTenshuIdentityRo
import org.tenshu.contact.client.impl.MergePersonsRo
import org.tenshu.contact.client.impl.RenamePersonRo
import org.tenshu.contact.client.impl.RevokeContactInviteRo
import org.tenshu.contact.client.impl.SaveContactSourceRo
import org.tenshu.contact.client.impl.SetPersonAvatarRo
import org.tenshu.contact.client.impl.UnlinkTenshuIdentityRo
import org.tenshu.contact.client.impl.VerifyTenshuIdentityRo
import org.tenshu.contact.InviteAckResult
import org.tenshu.contact.PendingRedemptionInfo
import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.type
import org.tenshu.core.schema.CompositeSchema

val contactServiceClientModule = tModule {

    operation(
        interfaceClass = CreateContactInvite::class,
        protocolSchema = CreateContactInvite.Schema,
        roFactory = ::CreateContactInviteRo
    )

    operation(
        interfaceClass = RevokeContactInvite::class,
        protocolSchema = RevokeContactInvite.Schema,
        roFactory = ::RevokeContactInviteRo
    )

    operation(
        interfaceClass = RedeemContactInvite::class,
        protocolSchema = RedeemContactInvite.Schema,
        roFactory = ::RedeemContactInviteRo
    )

    operation(
        interfaceClass = AwaitInviteAck::class,
        protocolSchema = AwaitInviteAck.Schema,
        roFactory = ::AwaitInviteAckRo
    )

    operation(
        interfaceClass = CompleteContactHandshake::class,
        protocolSchema = CompleteContactHandshake.Schema,
        roFactory = ::CompleteContactHandshakeRo
    )

    operation(
        interfaceClass = ListPendingRedemptions::class,
        protocolSchema = ListPendingRedemptions.Schema,
        roFactory = ::ListPendingRedemptionsRo
    )

    // Contact CRUD (`contacts-app.md` §6). ⚠ The `roFactory` registration is what
    // dispatch actually resolves — declaring the op schema alone leaves every call dying as
    // an opaque `error/internal`.
    operation(
        interfaceClass = CreatePerson::class,
        protocolSchema = CreatePerson.Schema,
        roFactory = ::CreatePersonRo
    )

    operation(
        interfaceClass = RenamePerson::class,
        protocolSchema = RenamePerson.Schema,
        roFactory = ::RenamePersonRo
    )

    operation(
        interfaceClass = DeletePerson::class,
        protocolSchema = DeletePerson.Schema,
        roFactory = ::DeletePersonRo
    )

    operation(
        interfaceClass = UnlinkTenshuIdentity::class,
        protocolSchema = UnlinkTenshuIdentity.Schema,
        roFactory = ::UnlinkTenshuIdentityRo
    )

    // Re-verification (`contacts-app.md` D4/Q2a).
    operation(
        interfaceClass = GetContactSas::class,
        protocolSchema = GetContactSas.Schema,
        roFactory = ::GetContactSasRo
    )

    operation(
        interfaceClass = VerifyTenshuIdentity::class,
        protocolSchema = VerifyTenshuIdentity.Schema,
        roFactory = ::VerifyTenshuIdentityRo
    )

    // Source records (`contact-service-identity-model.md` §4.3/§5).
    operation(
        interfaceClass = SaveContactSource::class,
        protocolSchema = SaveContactSource.Schema,
        roFactory = ::SaveContactSourceRo
    )

    operation(
        interfaceClass = ImportContact::class,
        protocolSchema = ImportContact.Schema,
        roFactory = ::ImportContactRo
    )

    // Avatars (`contacts-app.md` D1/Q4). ⚠⚠ GetPersonAvatar resolves the DEFERRED
    // `Person.Avatar` dynamic property, and a dynamicProperty dispatches through THIS
    // registration — not `operation(Schema)` in the common module. Without it every select
    // projecting the avatar dies as an opaque `error/internal`.
    operation(
        interfaceClass = SetPersonAvatar::class,
        protocolSchema = SetPersonAvatar.Schema,
        roFactory = ::SetPersonAvatarRo
    )

    operation(
        interfaceClass = ClearPersonAvatar::class,
        protocolSchema = ClearPersonAvatar.Schema,
        roFactory = ::ClearPersonAvatarRo
    )

    operation(
        interfaceClass = GetPersonAvatar::class,
        protocolSchema = GetPersonAvatar.Schema,
        roFactory = ::GetPersonAvatarRo
    )

    // The resolver of the content-plane `ContactSource.Photos` (`contacts-app.md` §9.10).
    // ⚠⚠ The dynamic property dispatches through THIS registration, as for the avatar above.
    operation(
        interfaceClass = GetContactSourcePhotos::class,
        protocolSchema = GetContactSourcePhotos.Schema,
        roFactory = ::GetContactSourcePhotosRo
    )

    // User-assisted merge (`contacts-app.md` D7).
    operation(
        interfaceClass = LinkTenshuIdentity::class,
        protocolSchema = LinkTenshuIdentity.Schema,
        roFactory = ::LinkTenshuIdentityRo
    )

    operation(
        interfaceClass = MergePersons::class,
        protocolSchema = MergePersons.Schema,
        roFactory = ::MergePersonsRo
    )

    // Typed ROs for the transient op-return composites (the `ShareLinkResult`
    // pattern): `createRequestObject` resolves these for the op return types.
    type(
        interfaceClass = InviteAckResult::class,
        dataSchema = InviteAckResult.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, InviteAckResult.Schema), InviteAckResult {
                override val schema: CompositeSchema get() = InviteAckResult.Schema
            }
        }
    )
    type(
        interfaceClass = PendingRedemptionInfo::class,
        dataSchema = PendingRedemptionInfo.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, PendingRedemptionInfo.Schema), PendingRedemptionInfo {
                override val schema: CompositeSchema get() = PendingRedemptionInfo.Schema
            }
        }
    )

    service(
        interfaceClass = ContactService::class,
        serviceSchema = ContactService.Schema,
        roFactory = ::ContactServiceRo
    )
}
