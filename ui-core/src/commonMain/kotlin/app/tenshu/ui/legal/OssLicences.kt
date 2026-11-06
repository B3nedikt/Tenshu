package app.tenshu.ui.legal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import app.tenshu.ui.resources.Res
import app.tenshu.ui.resources.oss_data_models_credit
import app.tenshu.ui.resources.oss_data_models_title
import app.tenshu.ui.resources.oss_data_noto_credit
import app.tenshu.ui.resources.oss_data_noto_title
import app.tenshu.ui.resources.oss_data_osm_credit
import app.tenshu.ui.resources.oss_data_osm_title
import app.tenshu.ui.resources.oss_data_photon_credit
import app.tenshu.ui.resources.oss_data_photon_title
import app.tenshu.ui.resources.oss_data_protomaps_credit
import app.tenshu.ui.resources.oss_data_protomaps_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.StringResource

/**
 * One open-source component an app binary ships, as the build recorded it.
 *
 * Built from the `aboutlibraries.json` the AboutLibraries Gradle plugin writes for each app at build
 * time (root `build.gradle.kts`, `docs/oss-licences.md`), so the list is exactly what that binary
 * links — never a hand list.
 */
@Immutable
data class OssComponent(
    /** Maven coordinate without version, `group:artifact`. Stable; the list's key. */
    val id: String,
    val name: String,
    val version: String?,
    val website: String?,
    val licences: List<OssLicence>,
)

/** A licence as the notices show it. [text] is null when the build has no copy (name + [url] only). */
@Immutable
data class OssLicence(val name: String, val url: String?, val text: String?)

/**
 * Data and assets an app shows or bundles that need a credit of their own — map data, fonts, a
 * geocoder's results. Not Maven dependencies, so the plugin cannot see them; each app passes the
 * ones it uses to [OpenSourceLicencesScreen] from [DataAttributions].
 */
@Immutable
class DataAttribution(
    val title: StringResource,
    val credit: StringResource,
    /** The licence's name. A legal name, so deliberately not translated. */
    val licence: String,
    val url: String?,
)

/** The data credits the apps draw from. `docs/oss-licences.md` §3 records which app needs which. */
object DataAttributions {
    /** ⚠⚠ ODbL 1.0 §4.3: every app that renders or derives from OSM data carries this. */
    val OpenStreetMap = DataAttribution(
        Res.string.oss_data_osm_title,
        Res.string.oss_data_osm_credit,
        "Open Database License (ODbL) 1.0",
        "https://www.openstreetmap.org/copyright",
    )
    val Protomaps = DataAttribution(
        Res.string.oss_data_protomaps_title,
        Res.string.oss_data_protomaps_credit,
        "BSD 3-Clause (schema) · ODbL 1.0 (data)",
        "https://protomaps.com",
    )

    /**
     * The map label glyphs. ⚠ The OFL's own condition is met by `OFL.txt`, which ships beside the
     * glyphs in every map-bearing app (`ui-map/src/androidMain/assets/glyphs/`, mirrored into the
     * iOS bundle); this row is the visible credit.
     */
    val NotoSans = DataAttribution(
        Res.string.oss_data_noto_title,
        Res.string.oss_data_noto_credit,
        "SIL Open Font License 1.1",
        "https://openfontlicense.org",
    )

    /** Reverse geocoding (opt-in) — results are OSM data served by komoot's Photon. */
    val Photon = DataAttribution(
        Res.string.oss_data_photon_title,
        Res.string.oss_data_photon_credit,
        "Open Database License (ODbL) 1.0",
        "https://photon.komoot.io",
    )

    /** Inference models are downloaded, not bundled; Settings → Models states each one's licence. */
    val Models = DataAttribution(
        Res.string.oss_data_models_title,
        Res.string.oss_data_models_credit,
        "Apache-2.0 (per model)",
        null,
    )

    /** What every app that draws the offline basemap owes. */
    val Basemap: List<DataAttribution> = listOf(OpenStreetMap, Protomaps, NotoSans)
}

/**
 * Parses the plugin's JSON into the components it lists, sorted by name.
 *
 * The format is two members: `libraries` (each naming its licences by key) and `licenses` (key →
 * `name`, `url`, `content`). ⚠ Parsed here rather than with `aboutlibraries-core`: that library's JVM
 * classes are Java 21 bytecode (15.2.0), and this module and its tests run on 17 — the classes fail
 * to load at the first parse, which only a test catches.
 *
 * @throws IllegalArgumentException (a `SerializationException`) on malformed input.
 */
fun parseOssLicences(json: String): List<OssComponent> {
    val root = Json.parseToJsonElement(json).jsonObject
    val licences = (root["licenses"] as? JsonObject).orEmpty().mapValues { (_, value) ->
        val licence = value.jsonObject
        OssLicence(
            name = licence.string("name") ?: "",
            url = licence.string("url"),
            text = licence.string("content")?.takeIf(String::isNotBlank),
        )
    }
    return (root["libraries"] as? JsonArray).orEmpty().map { element ->
        val library = element.jsonObject
        val id = library.string("uniqueId") ?: ""
        OssComponent(
            id = id,
            name = library.string("name") ?: id,
            version = library.string("artifactVersion"),
            website = library.string("website"),
            licences = (library["licenses"] as? JsonArray).orEmpty()
                .mapNotNull { licences[it.jsonPrimitive.content] }
                .sortedBy { it.name },
        )
    }.sortedBy { it.name.lowercase() }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The three states of this binary's own notices. */
sealed interface OssLicencesState {
    data object Loading : OssLicencesState

    /** ⚠ A build that was not run through the convention (e.g. an IDE run of a test shell). */
    data object Missing : OssLicencesState
    data class Loaded(val components: List<OssComponent>) : OssLicencesState
}

/** The resource every app binary carries its notices under (Android `res/raw`, desktop classpath root). */
internal const val BUNDLED_LICENCES_NAME = "aboutlibraries"

/**
 * Reads this binary's bundled `aboutlibraries.json`, or null when it has none. Blocking; called off
 * the main thread by [rememberOssLicences].
 */
@Composable
internal expect fun bundledLicencesReader(): () -> String?

/** This binary's notices, read and parsed off the main thread. */
@Composable
fun rememberOssLicences(): State<OssLicencesState> {
    val read = bundledLicencesReader()
    return produceState<OssLicencesState>(OssLicencesState.Loading, read) {
        value = withContext(Dispatchers.Default) {
            val json = read() ?: return@withContext OssLicencesState.Missing
            runCatching { OssLicencesState.Loaded(parseOssLicences(json)) }
                .getOrElse { OssLicencesState.Missing }
        }
    }
}
