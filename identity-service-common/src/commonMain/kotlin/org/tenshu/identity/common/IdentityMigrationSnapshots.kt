package org.tenshu.identity.common

import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.timestamp
import org.tenshu.identity.Member

/**
 * ⚠⚠ **Frozen** snapshot of `Member` at Identity **v1** — the input shape of the v1 → v2 migration step.
 * Never edit it to track `Member`: it must stay exactly what v1 stores and peers wrote, field for field and
 * in order (positional composites). The live shape is `Member.Schema`.
 */
internal val MEMBER_AT_V1: CompositeSchema = composite(
    id = Member.ID,
    fields = {
        string(Member.Fields.REALM_ID)
        string(Member.Fields.COMPOSITE_KEY)
        string(Member.Fields.MEMBER_ID)
        string(Member.Fields.DISPLAY_NAME)
        string(Member.Fields.CONTACT_FINGERPRINT)
        string(Member.Fields.ROLE)
        timestamp(Member.Fields.JOINED_AT)
    },
)
