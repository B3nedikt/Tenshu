package org.tenshu.routine.common.program

import kotlinx.serialization.json.Json
import org.tenshu.routine.Routine

/**
 * Parses and encodes `Routine.programJson` (`routines-engine.md` §4.2).
 *
 * ⭐ **[ProgramParse.UnsupportedVersion] is a value, never an exception.**
 * RE-D10's fence needs *two* distinct consequences from one verdict — do not
 * execute **and** do not claim the executor slot — so the caller must be able to
 * branch on it. A thrown parse error would collapse both into "something went
 * wrong", and the routine would silently never run anywhere: exactly the HR#9
 * silent-garbling class, re-created inside a single row.
 */
sealed interface ProgramParse {

    /** The program is understood and may be executed. */
    class Parsed(val program: RoutineProgram) : ProgramParse

    /**
     * Authored by a newer engine than this one. ⚠ The caller must neither
     * execute **nor** claim — it falls through to the RE-D2 grace fallback on a
     * device that does understand it.
     */
    class UnsupportedVersion(val version: Long) : ProgramParse

    /** Structurally broken, or a vocabulary member this version should know and doesn't. */
    class Malformed(val reason: String) : ProgramParse
}

class EncodedProgram(
    val programJson: String,
    val programVersion: Long,
)

interface RoutineProgramCodec {
    fun parse(json: String, version: Long): ProgramParse
    fun encode(program: RoutineProgram): EncodedProgram
}

class JsonRoutineProgramCodec(
    private val supportedVersion: Long = PROGRAM_VERSION,
) : RoutineProgramCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun parse(json: String, version: Long): ProgramParse {
        if (version > supportedVersion) return ProgramParse.UnsupportedVersion(version)
        if (version < 1) return ProgramParse.Malformed("programVersion $version is not a version")
        if (json.length > Routine.MAX_PROGRAM_BYTES) {
            return ProgramParse.Malformed("program exceeds ${Routine.MAX_PROGRAM_BYTES} bytes")
        }
        val program = runCatching { this.json.decodeFromString<RoutineProgram>(json) }
            .getOrElse { return ProgramParse.Malformed(it.message ?: "unparseable program") }
        return validate(program)?.let { ProgramParse.Malformed(it) } ?: ProgramParse.Parsed(program)
    }

    override fun encode(program: RoutineProgram): EncodedProgram = EncodedProgram(
        programJson = json.encodeToString(program),
        programVersion = supportedVersion,
    )

    /**
     * Semantic checks the type system does not carry. ⚠ Enforced at *authoring*
     * time by `SaveRoutine`, so a nonsensical routine is refused with a reason
     * rather than becoming one that silently never fires.
     */
    private fun validate(program: RoutineProgram): String? {
        if (program.actions.isEmpty()) return "a routine needs at least one action"

        when (val trigger = program.trigger) {
            is Trigger.TimeOfDay -> {
                if (trigger.minuteOfDay !in 0..1439) return "minuteOfDay out of range"
                if (trigger.days.any { it !in 1..7 }) return "days must be ISO 1..7"
            }

            is Trigger.Sun -> Unit
            is Trigger.DeviceEvent -> if (trigger.deviceId.isBlank()) return "deviceEvent needs a deviceId"
            is Trigger.Geofence -> if (trigger.placeId.isBlank()) return "geofence needs a placeId"
            is Trigger.VoicePhrase -> if (trigger.phrase.isBlank()) return "voicePhrase needs a phrase"
        }

        program.conditions.forEach { condition ->
            when (condition) {
                is Condition.TimeWindow -> {
                    if (condition.fromMinuteOfDay !in 0..1439) return "timeWindow.from out of range"
                    if (condition.toMinuteOfDay !in 0..1439) return "timeWindow.to out of range"
                }

                is Condition.DeviceStateIs ->
                    if (condition.deviceId.isBlank()) return "deviceStateIs needs a deviceId"

                is Condition.Presence -> Unit
            }
        }

        program.actions.forEach { action ->
            when (action) {
                is Action.SetPower ->
                    if (action.deviceId.isBlank()) return "setPower needs a deviceId"

                is Action.SetLevel -> {
                    if (action.deviceId.isBlank()) return "setLevel needs a deviceId"
                    if (action.percent !in 0..100) return "setLevel percent must be 0..100"
                }

                is Action.Notify ->
                    if (action.title.isBlank() && action.body.isBlank()) return "notify needs text"

                is Action.RunRoutine ->
                    if (action.routineId.isBlank()) return "runRoutine needs a routineId"
            }
        }
        return null
    }
}
