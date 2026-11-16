package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.identity.MembershipRecord

/** Wire DTO for [MembershipRecord] (`shared-realm-membership-records.md` §3.1). */
class MembershipRecordDto(
    fieldValues: List<Data?>,
) : MembershipRecord, CompositeDto(fieldValues)
