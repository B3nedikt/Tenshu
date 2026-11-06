package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.time.UnixInstant
import org.tenshu.identity.Member

/**
 * Wire DTO for [Member] (D5 in-realm member record). Materialised by the binary
 * parser when a member row arrives over shared-realm sync; cross-module callers
 * build instances via `Member.create(...)`, never this class directly.
 */
class MemberDto(
    fieldValues: List<Data?>,
) : Member, CompositeDto(fieldValues) {

    override val realmId: TString get() = getField(Member.Fields.REALM_ID)
    override val compositeKey: TString get() = getField(Member.Fields.COMPOSITE_KEY)
    override val memberId: TString get() = getField(Member.Fields.MEMBER_ID)
    override val displayName: TString get() = getField(Member.Fields.DISPLAY_NAME)
    override val contactFingerprint: TString get() = getField(Member.Fields.CONTACT_FINGERPRINT)
    override val role: TString get() = getField(Member.Fields.ROLE)
    override val joinedAt: UnixInstant get() = getField(Member.Fields.JOINED_AT)
}
