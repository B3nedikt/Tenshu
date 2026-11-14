package app.tenshu.control.data

import org.tenshu.core.app.GestureRequirement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BundledAppIdentityResolver] (*who is calling*) and [BundledAppManifestSource] (*what is it*)
 * are **derived views** over [BundledAppCatalogue] (`control-app-catalogue.md` C1).
 *
 * ⚠ There is deliberately no test that every resolvable app id has a bundled manifest: a
 * catalogue entry *carries* its manifest, so "allowlisted but undescribable" cannot be written
 * down.
 *
 * What this pins are the failure modes a *single* list actually has: two apps colliding on one
 * client id, and the derived views disagreeing with their source.
 */
class BundledAppTablesTest {

    private val resolver = BundledAppIdentityResolver()
    private val manifests = BundledAppManifestSource()

    @Test
    fun everyDeclaredClientResolvesToItsOwnApp() {
        // Whole-catalogue rather than a hand-listed sample: a newly declared app is covered the
        // moment it is added, which hand-listed per-app tests do not give.
        assertTrue(BundledAppCatalogue.apps.isNotEmpty())
        BundledAppCatalogue.apps.forEach { app ->
            assertTrue(app.clients.isNotEmpty(), "${app.appId} declares no client binding")
            app.clients.forEach { binding ->
                assertEquals(
                    app.appId,
                    resolver.resolveAppId(binding.clientId),
                    "${binding.clientId} must resolve to ${app.appId}",
                )
            }
        }
    }

    @Test
    fun everyDeclaredAppIsDescribable() {
        // Structural: the manifest *is* the entry.
        BundledAppCatalogue.apps.forEach { app ->
            val manifest = assertNotNull(manifests.manifest(app.appId), "${app.appId} has no manifest")
            assertEquals(app.appId, manifest.appId, "manifest appId must match its catalogue entry")
            assertTrue(manifest.name.isNotBlank(), "${app.appId} has no display name")
        }
    }

    @Test
    fun noTwoAppsClaimTheSameClientId() {
        // `associate` keeps the last winner, so a collision would silently route one app's calls
        // to another. The catalogue rejects it at init; this pins that the declared ids really
        // are distinct, so that guard is never the only thing between us and the bug.
        val ids = BundledAppCatalogue.apps.flatMap { app -> app.clients.map { it.clientId } }
        assertEquals(ids.size, ids.distinct().size, "duplicate client id across apps: $ids")
    }

    @Test
    fun desktopBindingsAreTheBundleIdentifiers() {
        // Desktop client ids are code-signing identifiers, so they must equal the bundle ids the
        // apps are built with — a mismatch is invisible at compile time and surfaces only as an
        // app that silently never registers. Drive intentionally uses the shared iOS/macOS bundle
        // identifier so App Store Connect can offer universal purchase.
        val desktop = BundledAppCatalogue.apps.flatMap { app ->
            app.clients.filterIsInstance<ClientBinding.MacCodeSignature>()
                .map { app.appId to it.identifier }
        }
        assertTrue(desktop.isNotEmpty(), "at least Drive and Fit ship signed desktop bundles")
        val expected = mapOf(
            "app.tenshu.drive" to "app.tenshu.drive.ios",
            "app.tenshu.fit" to "app.tenshu.fit.ios",
            "app.tenshu.maps" to "app.tenshu.maps.ios",
            "app.tenshu.timeline" to "app.tenshu.timeline.ios",
            "app.tenshu.home" to "app.tenshu.home.ios",
            "app.tenshu.mia" to "app.tenshu.mia.ios",
            "app.tenshu.calendar" to "app.tenshu.calendar.ios",
            "app.tenshu.contacts" to "app.tenshu.contacts.ios",
            "app.tenshu.photos" to "app.tenshu.photos.ios",
        )
        desktop.forEach { (appId, identifier) ->
            assertEquals(expected[appId], identifier, "unexpected desktop bundle identifier for $appId")
        }
    }

    /**
     * The catalogue pins each app's code-signing identifier; the bundle is signed with whatever
     * `bundleID` its `<app>-desktop/build.gradle.kts` declares. Nothing else connects the two, so
     * a mismatch used to surface only as an app the gate refuses at the socket. This reads the
     * build files, and also pins the rule they follow: the macOS bundle id is the app's iOS bundle
     * id (`project.yml`), in every channel (`desktop-macos-distribution.md` D12/C4).
     */
    @Test
    fun desktopBindingsMatchTheBuildFilesAndTheIosBundleIds() {
        val root = repoRoot()
        val apps = BundledAppCatalogue.apps.map { app ->
            app.appId to app.clients.filterIsInstance<ClientBinding.MacCodeSignature>().single().identifier
        } + ("app.tenshu.control" to macOsBundleIdFor("app.tenshu.control"))
        assertEquals(10, apps.size, "ten first-party desktop apps: $apps")
        apps.forEach { (appId, identifier) ->
            val name = appId.removePrefix("app.tenshu.")
            val gradle = File(root, "$name-desktop/build.gradle.kts")
            val declared = Regex("""bundleID = "([^"]+)"""").findAll(gradle.readText()).map { it.groupValues[1] }.toList()
            assertEquals(listOf(identifier), declared, "bundleID in $gradle for $appId")
            val iosIds = Regex("""PRODUCT_BUNDLE_IDENTIFIER(?:\[[^\]]*])?: *(\S+)""")
                .findAll(File(root, "$name-ios/project.yml").readText()).map { it.groupValues[1] }.toSet()
            assertTrue(identifier in iosIds, "$appId: macOS id $identifier is not an iOS bundle id of $name-ios ($iosIds)")
            assertEquals("app.tenshu.$name.ios", identifier, "$appId: macOS bundle id")
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }
    }

    /**
     * The collision guard has to be *exercised*, not merely present. It only ever runs against a
     * list that is correct, so without this it would be asserted rather than verified —
     * and could stop working silently, which is precisely the class of failure it exists to
     * prevent.
     */
    @Test
    fun twoAppsClaimingOneClientIdIsRejected() {
        val collidingId = "app.tenshu.drive.android"
        val colliding = listOf(
            FirstPartyApp(
                manifest = BundledAppManifestSource.DRIVE_MANIFEST,
                clients = listOf(ClientBinding.AndroidPackage(collidingId)),
            ),
            FirstPartyApp(
                manifest = BundledAppManifestSource.FIT_MANIFEST,
                clients = listOf(ClientBinding.AndroidPackage(collidingId)),
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> { clientIndexOf(colliding) }
        assertTrue(
            failure.message.orEmpty().contains(collidingId),
            "the error must name the offending id, got: ${failure.message}",
        )
    }

    @Test
    fun oneAppDeclaredTwiceIsRejected() {
        val duplicated = listOf(
            FirstPartyApp(BundledAppManifestSource.DRIVE_MANIFEST, emptyList()),
            FirstPartyApp(BundledAppManifestSource.DRIVE_MANIFEST, emptyList()),
        )

        val failure = assertFailsWith<IllegalArgumentException> { manifestIndexOf(duplicated) }
        assertTrue(failure.message.orEmpty().contains(BundledAppManifestSource.DRIVE_APP_ID))
    }

    /** The happy path must still build, or the guards above prove nothing. */
    @Test
    fun aWellFormedCatalogueBuildsBothIndexes() {
        assertEquals(BundledAppCatalogue.apps.size, manifestIndexOf(BundledAppCatalogue.apps).size)
        assertTrue(clientIndexOf(BundledAppCatalogue.apps).isNotEmpty())
    }

    @Test
    fun unknownClient_isNotRegistered() {
        assertNull(resolver.resolveAppId("dev.example.notes"), "the allowlist is the gate")
    }

    /**
     * An unattested desktop peer arrives under a unique `unattested-<uuid>` id
     * (`desktop-ipc-client-identity.md` P2.2). It must resolve to nothing: failing closed into
     * *anonymous* is the point — failing closed into a fallback identity is the bug that track
     * exists to prevent.
     */
    @Test
    fun unattestedPeer_resolvesToNoApp() {
        assertNull(resolver.resolveAppId("unattested-3f2b1c4a-0000-4000-8000-000000000000"))
        assertNull(resolver.resolveAppId("unattested-"))
    }

    /**
     * Guards against over-declaring. Fit's writes all funnel through `HealthImportWriter`, which
     * dispatches exactly these two ops — the per-kind `recordSleepSession`/`recordDailyActivity`/…
     * operations exist on the service but Fit batches them into `recordImportedHealth` instead.
     * A permission list is a consent surface; padding it with ops the app never calls is a
     * quiet over-grant.
     */
    @Test
    /**
     * ⚠⚠ **The name overpromises, and it matters.** This does not compare the manifest against
     * anything Fit dispatches — it compares it against the hardcoded set below. It catches a
     * permission *added* without a caller; it cannot catch a caller added without a permission
     * (`recordHealthSample`, for one, is dispatched and not declared here).
     *
     * ⚠ Under-declaration has no automated guard and this shape of test cannot provide one:
     * knowing what an app dispatches means reading its call sites, not its manifest.
     */
    fun fitManifest_declaresOnlyTheOpsFitActuallyDispatches() {
        val fit = assertNotNull(manifests.manifest(BundledAppManifestSource.FIT_APP_ID))
        assertEquals(
            setOf(
                "tenshu/health/service/recordWorkout",
                "tenshu/health/service/recordImportedHealth",
                "tenshu/health/service/deleteWorkout",
                // The Data sources registry (fit-data-sources-restructure.md DS-2). Each one has
                // a real dispatcher in HealthImportRunRecorder — this list is what makes
                // "declared but never called" fail, so add an entry only alongside its caller.
                "tenshu/health/service/registerHealthSource",
                "tenshu/health/service/recordImportRun",
                "tenshu/health/service/setSourceAutoImport",
            ),
            fit.permissions.map { it.operationId }.toSet(),
        )
        assertTrue(
            fit.permissions.all { it.serviceId == "tenshu/health/service" },
            "Fit declares health-service permissions only",
        )
    }

    /**
     * Photos' manifest, pinned (`photos-app.md`; the bundle-wide sweep is in `tech-debt.md`).
     *
     * ⚠⚠ Read the note on the Fit case above before trusting this one: it is the same kind of
     * change-detector, so it freezes the list rather than deriving it. What it buys is that a
     * change adding a `photoService.…` call has to come back here.
     *
     * ⚠ Photos is the first app whose manifest spans three services, and `services` on the Apps
     * screen is derived from these entries — so a missing permission does not just drop a line,
     * it can drop a whole service from what the user is shown.
     */
    @Test
    fun photosManifest_declaresOnlyTheOpsPhotosActuallyDispatches() {
        val photos = assertNotNull(manifests.manifest(BundledAppManifestSource.PHOTOS_APP_ID))
        assertEquals(
            setOf(
                // reads
                "tenshu/file/getPreview",
                // ⚠ Every `File.Thumbnail*` projection dispatches this, and it pulls the class
                // from the relay when this device has none.
                "tenshu/file/getStoredThumbnail",
                "tenshu/file/system/hasLocalData",
                // Mint on view — the one Photos op that can download a whole original.
                "tenshu/file/system/ensureThumbnailClass",
                "tenshu/inference/photosLike",
                // People (IN-6): grouping, naming, and the name-suggestion "No" (F6c-s)
                "tenshu/inference/faceGroups",
                "tenshu/inference/nameFaceGroup",
                "tenshu/inference/rejectFaceSuggestion",
                "tenshu/inference/faceSuggestionRejections",
                // albums and favourites
                "tenshu/photo/service/saveAlbum",
                "tenshu/photo/service/deleteAlbum",
                "tenshu/photo/service/addToAlbum",
                "tenshu/photo/service/removeFromAlbum",
                "tenshu/photo/service/setPhotoFlags",
                // the trash
                "tenshu/file/system/createFolder",
                "tenshu/file/system/moveFile",
                "tenshu/file/system/deleteFile",
                "tenshu/photo/service/recordTrashedPhoto",
                "tenshu/photo/service/clearTrashedPhoto",
                // the editor's Save as copy (ED-1)
                "tenshu/file/system/uploadFiles",
                "tenshu/file/system/sourceFileUpload",
                // the non-destructive edit (ED-3)
                "tenshu/photo/service/setPhotoEdit",
                "tenshu/photo/service/clearPhotoEdit",
            ),
            photos.permissions.map { it.operationId }.toSet(),
        )
        assertEquals(
            setOf("tenshu/file/service", "tenshu/file/system", "tenshu/photo/service",
                  "tenshu/inference/service"),
            photos.permissions.map { it.serviceId }.toSet(),
            "the Apps screen derives \"Services used\" from these, so a dropped serviceId hides a " +
                "whole capability",
        )
    }

    /**
     * ⚠⚠ The destructive operations must not be invocable without an explicit confirmation, and
     * this is the only place that says so. `GestureRequirement` decides how Control's widget gates
     * a permission, so a delete that ships as `RequiresUserGesture` is one stray tap away from
     * being irreversible.
     */
    @Test
    fun photosDestructiveOpsRequireExplicitConfirmation() {
        val photos = assertNotNull(manifests.manifest(BundledAppManifestSource.PHOTOS_APP_ID))
        val destructive = setOf(
            "tenshu/file/system/deleteFile",
            "tenshu/photo/service/deleteAlbum",
        )
        photos.permissions.filter { it.operationId in destructive }.forEach {
            assertEquals(
                GestureRequirement.RequiresExplicitConfirmation,
                it.gesture,
                it.operationId + " is destructive and must ask first",
            )
        }
        assertEquals(
            destructive,
            photos.permissions.filter {
                it.gesture == GestureRequirement.RequiresExplicitConfirmation
            }.map { it.operationId }.toSet(),
            "and nothing else claims to be destructive",
        )
    }
}
