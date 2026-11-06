package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.RetryPolicy

internal class RetryPolicyDto(
    fieldValues: List<Data?>
) : RetryPolicy, CompositeDto(fieldValues)
