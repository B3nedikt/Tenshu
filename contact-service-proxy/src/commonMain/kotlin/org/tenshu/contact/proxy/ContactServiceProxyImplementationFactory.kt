package org.tenshu.contact.proxy

import org.tenshu.contact.AwaitInviteAck
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.contact.CreateContactInvite
import org.tenshu.contact.CreatePerson
import org.tenshu.contact.ClearPersonAvatar
import org.tenshu.contact.DeletePerson
import org.tenshu.contact.GetContactSourcePhotos
import org.tenshu.contact.SweepContactPhotos
import org.tenshu.contact.GetContactSas
import org.tenshu.contact.GetPersonAvatar
import org.tenshu.contact.LinkTenshuIdentity
import org.tenshu.contact.MergePersons
import org.tenshu.contact.ImportContact
import org.tenshu.contact.ListPendingRedemptions
import org.tenshu.contact.RedeemContactInvite
import org.tenshu.contact.RenamePerson
import org.tenshu.contact.RevokeContactInvite
import org.tenshu.contact.SaveContactSource
import org.tenshu.contact.SetPersonAvatar
import org.tenshu.contact.UnlinkTenshuIdentity
import org.tenshu.contact.VerifyTenshuIdentity
import org.tenshu.contact.proxy.impl.AwaitInviteAckImplementation
import org.tenshu.contact.proxy.impl.CompleteContactHandshakeImplementation
import org.tenshu.contact.proxy.impl.CreateContactInviteImplementation
import org.tenshu.contact.proxy.impl.CreatePersonImplementation
import org.tenshu.contact.proxy.impl.ClearPersonAvatarImplementation
import org.tenshu.contact.proxy.impl.DeletePersonImplementation
import org.tenshu.contact.proxy.impl.GetContactSourcePhotosImplementation
import org.tenshu.contact.proxy.impl.SweepContactPhotosImplementation
import org.tenshu.contact.proxy.impl.GetContactSasImplementation
import org.tenshu.contact.proxy.impl.GetPersonAvatarImplementation
import org.tenshu.contact.proxy.impl.LinkTenshuIdentityImplementation
import org.tenshu.contact.proxy.impl.MergePersonsImplementation
import org.tenshu.contact.proxy.impl.ImportContactImplementation
import org.tenshu.contact.proxy.impl.ListPendingRedemptionsImplementation
import org.tenshu.contact.proxy.impl.RedeemContactInviteImplementation
import org.tenshu.contact.proxy.impl.RenamePersonImplementation
import org.tenshu.contact.proxy.impl.RevokeContactInviteImplementation
import org.tenshu.contact.proxy.impl.SaveContactSourceImplementation
import org.tenshu.contact.proxy.impl.SetPersonAvatarImplementation
import org.tenshu.contact.proxy.impl.UnlinkTenshuIdentityImplementation
import org.tenshu.contact.proxy.impl.VerifyTenshuIdentityImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementation

fun contactServiceProxyImplementationFactory(
    id: String,
    components: ContactProxyComponents,
): OperationImplementation? {
    return when (id) {
        CreateContactInvite.ID -> CreateContactInviteImplementation(components)
        RevokeContactInvite.ID -> RevokeContactInviteImplementation(components)
        RedeemContactInvite.ID -> RedeemContactInviteImplementation(components)
        AwaitInviteAck.ID -> AwaitInviteAckImplementation(components)
        CompleteContactHandshake.ID -> CompleteContactHandshakeImplementation(components)
        ListPendingRedemptions.ID -> ListPendingRedemptionsImplementation(components)
        // Contact CRUD — local writes, no relay client, crypto or identity key. ⚠ DeletePerson
        // takes the components for its tombstones and the photo blobs (`contacts-app.md` §9.10).
        CreatePerson.ID -> CreatePersonImplementation()
        RenamePerson.ID -> RenamePersonImplementation()
        DeletePerson.ID -> DeletePersonImplementation(components)
        UnlinkTenshuIdentity.ID -> UnlinkTenshuIdentityImplementation()
        // Re-verification: the SAS derivation needs the realm's own A13, so it takes the
        // components; recording the verdict is a plain local write.
        GetContactSas.ID -> GetContactSasImplementation(components)
        VerifyTenshuIdentity.ID -> VerifyTenshuIdentityImplementation()
        // Source records — writes over the raw vCard payload. Big PHOTO lines move to the content
        // plane (§9.10), so these take the byte store and the emit seam.
        SaveContactSource.ID -> SaveContactSourceImplementation(components)
        ImportContact.ID -> ImportContactImplementation(components)
        GetContactSourcePhotos.ID -> GetContactSourcePhotosImplementation(components.fileDatabase)
        SweepContactPhotos.ID -> SweepContactPhotosImplementation(components)
        SetPersonAvatar.ID -> SetPersonAvatarImplementation()
        ClearPersonAvatar.ID -> ClearPersonAvatarImplementation()
        GetPersonAvatar.ID -> GetPersonAvatarImplementation()
        LinkTenshuIdentity.ID -> LinkTenshuIdentityImplementation()
        MergePersons.ID -> MergePersonsImplementation()
        else -> null
    }
}
