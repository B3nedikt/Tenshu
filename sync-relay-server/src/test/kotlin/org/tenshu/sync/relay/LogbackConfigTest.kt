package org.tenshu.sync.relay

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.status.Status
import org.slf4j.Logger.ROOT_LOGGER_NAME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the production `logback.xml` (`docs/how-to/server-logging.md`): INFO by default, the
 * operational lines still on, the noisy libraries off, `LOG_LEVEL` honoured, and the e-mail backstop
 * actually masking. Loads the shipped file into a private [LoggerContext], so it tests the config
 * the fat jar carries rather than whatever the test JVM happened to initialise.
 */
class LogbackConfigTest {

    private fun configured(logLevel: String? = null): LoggerContext = productionLogback(logLevel)

    @Test
    fun defaultsToInfoWithTheOperationalLinesOn() {
        val context = configured()
        assertEquals(Level.INFO, context.getLogger(ROOT_LOGGER_NAME).level)
        // `Content sweep: armed, T=…`, the entitlement mode line and CallLogging all log through
        // Ktor's application logger — quieting `io.ktor` must not take it with it.
        assertTrue(context.getLogger("io.ktor.server.Application").isInfoEnabled)
        assertTrue(context.getLogger("org.tenshu.sync.relay.EntitlementEnforcement").isInfoEnabled)
        assertFalse(context.getLogger("org.tenshu.sync.relay.SyncMailbox").isDebugEnabled)
    }

    @Test
    fun thirdPartyLibrariesAreQuietAndStayQuietUnderDebug() {
        for (context in listOf(configured(), configured(logLevel = "DEBUG"))) {
            for (name in listOf("software.amazon.awssdk.request", "io.netty.channel", "io.ktor.server.engine", "org.sqlite.core")) {
                assertFalse(context.getLogger(name).isInfoEnabled, "$name should be WARN")
                assertTrue(context.getLogger(name).isWarnEnabled, "$name should still WARN")
            }
        }
    }

    @Test
    fun logLevelRaisesOurOwnLines() {
        val context = configured(logLevel = "DEBUG")
        assertTrue(context.getLogger("org.tenshu.sync.relay.SyncMailbox").isDebugEnabled)
        assertTrue(context.getLogger("io.ktor.server.Application").isDebugEnabled)
    }

    @Test
    fun aMisspeltLogLevelReadsAsDebugNotInfo() {
        // ⚠ The documented trap (logback's Level.toLevel default), pinned so a logback upgrade that
        // changes it is noticed: an operator who misspells the level gets MORE logging, not less.
        val context = configured(logLevel = "DEGUB")
        assertEquals(Level.DEBUG, context.getLogger(ROOT_LOGGER_NAME).level)
        assertFalse(context.getLogger("io.netty.channel").isInfoEnabled, "libraries stay pinned at WARN")
    }

    @Test
    fun emailAddressesAreMaskedInMessagesAndStackTraces() {
        val context = configured()
        val logger = context.getLogger("org.tenshu.test")
        val failure = IllegalStateException(
            "550 5.1.1 <jane.doe+x@example.co.uk>: Recipient address rejected",
            RuntimeException("cause names bob@mail.example.org"),
        )
        val line = render(context, LoggingEvent(javaClass.name, logger, Level.WARN, "send to {} failed", failure, arrayOf("alice@example.com")))

        assertFalse("alice@" in line || "jane.doe" in line || "bob@" in line, "an address survived:\n$line")
        assertTrue("send to ***@example.com failed" in line, line)
        assertTrue("<***@example.co.uk>" in line, line)
        assertTrue("***@mail.example.org" in line, line)
        // The stack trace is still there — and printed once, not also auto-appended unmasked.
        assertTrue("at org.tenshu.sync.relay.LogbackConfigTest" in line, line)
        assertEquals(1, Regex("IllegalStateException").findAll(line).count(), line)
        // The timestamp/level/logger prefix operators read in `docker logs`.
        assertTrue(Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}\S* WARN  \[""").containsMatchIn(line), line)
    }

    @Test
    fun linesWithoutAnAddressAreUntouched() {
        val context = configured()
        val logger = context.getLogger("io.ktor.server.Application")
        val message = "Content sweep: armed, T=900000 ms, every 300000 ms"
        val line = render(context, LoggingEvent(javaClass.name, logger, Level.INFO, message, null, null))
        assertTrue(line.trimEnd().endsWith("io.ktor.server.Application - $message"), line)
    }

    @Test
    fun ktorsEngineFailureLineLosesItsPath() {
        // DefaultEnginePipeline.logFailure's two shapes (F4 of relay-metadata-minimisation). The
        // end-to-end twin is RouteTemplateLoggingTest.theEngineFailureLineReachesDiskWithoutItsPath.
        val context = configured()
        val logger = context.getLogger("io.ktor.server.Application")
        val debug = "Unhandled: GET - /share/aB3_-xYz09QwErTyUiOpAs. Exception class java.util.concurrent.CancellationException: gone"
        val error = "500 Internal Server Error: PUT - //sync/content/blob-0123456789abcdef"
        for (message in listOf(debug, error)) {
            val line = render(context, LoggingEvent(javaClass.name, logger, Level.ERROR, message, null, null))
            assertFalse("aB3_-xYz" in line || "blob-0123" in line, "the path survived:\n$line")
            assertTrue(Regex(""": (GET|PUT) - <path>""").containsMatchIn(line), line)
        }
    }

    private fun render(context: LoggerContext, event: ILoggingEvent): String = context.renderLine(event)
}

/**
 * The shipped `logback.xml` loaded into a private [LoggerContext] — the config the fat jar carries,
 * not whatever the test JVM initialised. Also used by `RouteTemplateLoggingTest`.
 */
internal fun productionLogback(logLevel: String? = null): LoggerContext {
    val url = assertNotNull(LogbackConfigTest::class.java.classLoader.getResource("logback.xml"), "logback.xml is not on the classpath")
    return LoggerContext().apply {
        // Same lookup `${LOG_LEVEL:-INFO}` makes for the env var: context properties come first.
        if (logLevel != null) putProperty("LOG_LEVEL", logLevel)
        JoranConfigurator().also { it.context = this }.doConfigure(url)
        val problems = statusManager.copyOfStatusList.filter { it.level >= Status.WARN }
        assertTrue(problems.isEmpty(), "logback reported problems loading logback.xml: $problems")
    }
}

/** [event] as the production STDOUT appender would write it. */
@Suppress("UNCHECKED_CAST")
internal fun LoggerContext.renderLine(event: ILoggingEvent): String {
    val appender = getLogger(ROOT_LOGGER_NAME).getAppender("STDOUT") as OutputStreamAppender<ILoggingEvent>
    return String(appender.encoder.encode(event), Charsets.UTF_8)
}
