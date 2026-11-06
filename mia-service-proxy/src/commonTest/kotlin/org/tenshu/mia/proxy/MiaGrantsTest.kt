package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.get
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.toTBoolean
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaHit
import org.tenshu.mia.MiaSource
import org.tenshu.mia.askMia
import org.tenshu.preference.RealmSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MQ-D6's grants, end to end: a revoked source drops out of planning, and Mia says which one
 * and where to change it.
 */
class MiaGrantsTest {

    private val now = 1_784_116_800_000L
    private val yesterday = now - 27 * 60 * 60 * 1000L

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, "UTC")
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun TestProxy.seedMoment() {
        executionContext.saveData(
            Moment.create(
                context = tContext,
                momentId = "m1",
                timestamp = UnixInstantDto(yesterday),
                kind = "Visit",
                title = "Blue Bottle",
                subject = SubjectRef.create(tContext, "place", "k1"),
            )
        )
    }

    private fun TestProxy.setGrant(source: MiaSource, granted: Boolean) {
        executionContext.saveData(
            RealmSetting.create(
                context = tContext,
                preferenceId = source.preference.id,
                value = granted.toTBoolean(tContext),
            )
        )
    }

    @Test
    fun withNoRowsAtAllEverySourceIsGranted() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedMoment()

        // ⚠⚠ Absent must mean GRANTED here. Failing closed would silently disable an
        // assistant nobody has configured — the fail-closed posture lives in the validator,
        // not in the absence of a preference row.
        assertEquals(MiaSource.entries.toSet(), proxy.executionContext.readMiaGrants())
        assertEquals(MiaAnswer.VERDICT_ANSWERED, ask(proxy, "yesterday").verdict.get())
    }

    @Test
    fun aRevokedSourceIsRefusedByNameRatherThanAnsweredEmpty() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedMoment()
        proxy.setGrant(MiaSource.TIMELINE, granted = false)

        val answer = ask(proxy, "yesterday")

        // ⚠⚠ NOT "answered with nothing" — that would tell the user their timeline is empty
        // when it is full, which is the failure MQ-D6's "says why" clause exists to prevent.
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertTrue(answer.hits.toList().isEmpty())
        assertTrue(answer.explanation.get().contains("timeline"), answer.explanation.get())
        assertTrue(answer.explanation.get().contains("settings"), answer.explanation.get())
    }

    @Test
    fun revokingOneSourceLeavesTheOthersWorking() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedMoment()
        proxy.fileService().let { }
        proxy.setGrant(MiaSource.TIMELINE, granted = false)

        assertEquals(
            MiaSource.entries.toSet() - MiaSource.TIMELINE,
            proxy.executionContext.readMiaGrants(),
        )
        // A files question is untouched by a timeline revocation.
        assertEquals(MiaAnswer.VERDICT_ANSWERED, ask(proxy, "find the invoice").verdict.get())
    }

    @Test
    fun aRevokedSourceIsAlsoOffTheValidatorsAllowlist() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        // ⚠ Belt and braces: the planner refuses first, but if a chain for a revoked source
        // ever reached the validator it must not run. Two checks, two questions — only this
        // one is the security boundary.
        val policy = QueryPlanLowering.policyFor(MiaSource.entries.toSet() - MiaSource.TIMELINE)
        assertTrue(MiaSource.TIMELINE.chainReferenceId !in policy.allowedServiceIds)
        assertTrue(MiaSource.FILES.chainReferenceId in policy.allowedServiceIds)
    }

    @Test
    fun turningASourceBackOnRestoresIt() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedMoment()

        proxy.setGrant(MiaSource.TIMELINE, granted = false)
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, ask(proxy, "yesterday").verdict.get())

        proxy.setGrant(MiaSource.TIMELINE, granted = true)
        val answer = ask(proxy, "yesterday")
        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(
            listOf("Blue Bottle"),
            answer.hits.toList().filterIsInstance<MiaHit>().map { it.title.get() },
        )
    }
}
