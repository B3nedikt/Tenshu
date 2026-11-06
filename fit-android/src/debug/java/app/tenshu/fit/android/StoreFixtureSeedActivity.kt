package app.tenshu.fit.android

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Volume
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * DEBUG ONLY — writes the store-screenshot fixture's daily figures into **Health Connect**
 * (`docs/how-to/store-screenshots.md` §8), the Android twin of fit-ios's `StoreFixtureSeed`.
 *
 * ⚠⚠ **Data only, never a screen anyone screenshots, and nothing reaches the realm here.** The
 * screenshot walk starts this activity, waits for its one line of text, stops the app, and launches
 * Fit normally: the figures then come in through the shipping Health Connect import, after the walk
 * answers the read-consent sheet Fit shows on launch by taps. Health Connect has no way in from adb
 * (its shell inserts only a canned test step record) and Fit never writes to it, so this is the
 * smallest writer there is — the same role Apple Health's write consent plays in the iOS seed.
 *
 * ⚠⚠ The write permissions are asked for through **Health Connect's own consent sheet** (the walk
 * answers it), never `pm grant`. Health Connect's aggregates count only the apps on its per-type
 * data-source priority list, and the sheet is what puts a writer there: with `pm grant` the records
 * were written, and every steps and distance aggregate still came back 0.
 *
 * Reads `health.json` (`store/fixtures/fit/generate_fit.py`) from the app's own files directory,
 * where the walk writes it with `run-as` (a debug build is debuggable). ⚠ Not the external files
 * directory: a folder `adb push` makes there is the shell's, and the app cannot read it. The workouts are NOT here: they are GPX files the walk
 * imports through Profile → Google Takeout, the shipping file import.
 */
class StoreFixtureSeedActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var line: TextView

    private val askToWrite = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(WRITE)) {
            write()
        } else {
            report("seed: FAILED Health Connect write access refused (${WRITE.size - granted.intersect(WRITE).size} missing)")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        line = TextView(this).apply { text = "seeding…"; setPadding(48, 160, 48, 48) }
        setContentView(line)
        if (savedInstanceState != null) return
        scope.launch {
            val granted = HealthConnectClient.getOrCreate(this@StoreFixtureSeedActivity)
                .permissionController.getGrantedPermissions()
            if (granted.containsAll(WRITE)) write() else askToWrite.launch(WRITE)
        }
    }

    private fun report(result: String) {
        Log.i(TAG, result)
        line.text = result
    }

    private fun write() {
        scope.launch {
            val result = runCatching { seed() }.fold(
                onSuccess = { "seed: OK $it records" },
                onFailure = { "seed: FAILED ${it::class.simpleName}: ${it.message}" },
            )
            report(result)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun seed(): Int {
        val file = File(filesDir, "store-fixture/health.json")
        require(file.isFile) { "no ${file.path}" }
        val json = JSONObject(file.readText())
        val records = mutableListOf<Record>()

        val quantities = json.getJSONArray("quantities")
        for (i in 0 until quantities.length()) {
            val q = quantities.getJSONObject(i)
            quantity(q.getString("type"), q.getDouble("value"), Instant.parse(q.getString("start")), Instant.parse(q.getString("end")))
                ?.let(records::add)
        }

        // Each `inBed` span is one night; the stages inside it are that night's.
        val sleep = json.getJSONArray("sleep")
        val spans = (0 until sleep.length()).map { sleep.getJSONObject(it) }
        val nights = spans.filter { it.getString("stage") == "inBed" }
        for (night in nights) {
            val start = Instant.parse(night.getString("start"))
            val end = Instant.parse(night.getString("end"))
            val stages = spans.mapNotNull { s ->
                val type = STAGES[s.getString("stage")] ?: return@mapNotNull null
                val from = Instant.parse(s.getString("start"))
                val to = Instant.parse(s.getString("end"))
                if (from < start || to > end) null else SleepSessionRecord.Stage(from, to, type)
            }.sortedBy { it.startTime }
            records += SleepSessionRecord(
                startTime = start, startZoneOffset = offset(start),
                endTime = end, endZoneOffset = offset(end),
                metadata = Metadata.manualEntry(),
                stages = stages,
            )
        }

        val client = HealthConnectClient.getOrCreate(this)
        records.chunked(500).forEach { client.insertRecords(it) }
        return records.size
    }

    private fun quantity(type: String, value: Double, start: Instant, rawEnd: Instant): Record? {
        // An interval record needs end > start; the fixture writes a meal or a drink as an instant.
        val end = if (rawEnd > start) rawEnd else start.plusSeconds(60)
        val s = offset(start)
        val e = offset(end)
        val m = Metadata.manualEntry()
        return when (type) {
            "stepCount" -> StepsRecord(start, s, end, e, value.toLong(), m)
            "distanceWalkingRunning" -> DistanceRecord(start, s, end, e, Length.meters(value), m)
            // Apple Health's active energy; Fit reads Health Connect's total calories.
            "activeEnergyBurned" -> TotalCaloriesBurnedRecord(start, s, end, e, Energy.kilocalories(value), m)
            "dietaryEnergyConsumed" -> NutritionRecord(
                startTime = start, startZoneOffset = s, endTime = end, endZoneOffset = e,
                metadata = m, energy = Energy.kilocalories(value),
            )
            "dietaryWater" -> HydrationRecord(start, s, end, e, Volume.milliliters(value), m)
            "restingHeartRate" -> RestingHeartRateRecord(start, s, value.toLong(), m)
            "bodyMass" -> WeightRecord(start, s, Mass.kilograms(value), m)
            "height" -> HeightRecord(start, s, Length.meters(value), m)
            "oxygenSaturation" -> OxygenSaturationRecord(start, s, Percentage(if (value <= 1.0) value * 100 else value), m)
            "respiratoryRate" -> RespiratoryRateRecord(start, s, value, m)
            else -> error("unknown quantity type $type")
        }
    }

    private fun offset(at: Instant): ZoneOffset = ZoneId.systemDefault().rules.getOffset(at)

    private companion object {
        const val TAG = "FitStoreFixtureSeed"

        /** Exactly the WRITE permissions src/debug/AndroidManifest.xml declares. */
        val WRITE: Set<String> = setOf(
            StepsRecord::class, DistanceRecord::class, TotalCaloriesBurnedRecord::class, NutritionRecord::class,
            HydrationRecord::class, SleepSessionRecord::class, WeightRecord::class, HeightRecord::class,
            RestingHeartRateRecord::class, OxygenSaturationRecord::class, RespiratoryRateRecord::class,
        ).map { HealthPermission.getWritePermission(it) }.toSet()
        val STAGES = mapOf(
            "core" to SleepSessionRecord.STAGE_TYPE_LIGHT,
            "deep" to SleepSessionRecord.STAGE_TYPE_DEEP,
            "rem" to SleepSessionRecord.STAGE_TYPE_REM,
            "awake" to SleepSessionRecord.STAGE_TYPE_AWAKE,
        )
    }
}
