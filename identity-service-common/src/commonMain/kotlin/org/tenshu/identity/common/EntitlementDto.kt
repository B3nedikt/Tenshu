package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.time.UnixInstant
import org.tenshu.identity.Entitlement

internal class EntitlementDto(
    fieldValues: List<Data?>
) : Entitlement, CompositeDto(fieldValues) {

    override val ownerId: TString get() = getField(Entitlement.Fields.OWNER_ID)
    override val publisherId: TString get() = getField(Entitlement.Fields.PUBLISHER_ID)
    override val productId: TString get() = getField(Entitlement.Fields.PRODUCT_ID)
    override val validFrom: UnixInstant get() = getField(Entitlement.Fields.VALID_FROM)
    override val validUntil: UnixInstant get() = getField(Entitlement.Fields.VALID_UNTIL)
    override val publisherSignature: TByteArray get() = getField(Entitlement.Fields.PUBLISHER_SIGNATURE)
}
