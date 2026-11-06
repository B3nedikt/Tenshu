package app.tenshu.control.data

import org.tenshu.core.app.AppManifest
import org.tenshu.core.app.AppManifestSource
import org.tenshu.core.app.AppPermission
import org.tenshu.core.app.CapabilityClass
import org.tenshu.core.app.GestureRequirement

/**
 * v1 [AppManifestSource]: a compiled-in table of first-party manifests
 * (`control-app-registration.md` §4 / §7, `tenshu-rei.md` §3.2). This is the
 * `ReiSource`-shaped seam the future ledger-backed source replaces; until then
 * Control reads first-party app identity from here.
 *
 * Apps with no bundled entry (third-party / dev-mode builds) return `null` —
 * the Apps UI then falls back to the synced `AppRegistration` fields (appId,
 * install date, verified badge) and surfaces only the permissions for which a
 * grant row actually exists.
 */
class BundledAppManifestSource : AppManifestSource {

    // Derived from the one declaration (control-app-catalogue.md C1). This list used to be
    // maintained here and the client-id allowlist maintained separately, joined by an appId
    // string — a join that degraded silently when it missed.
    override fun manifest(appId: String): AppManifest? =
        BundledAppCatalogue.manifestsByAppId[appId]

    companion object {
        /**
         * Provisional first-party Drive `appId`. **Contract:** whatever the IPC handshake
         * registers Drive under (via `RegisterApp`, once that trigger is wired) MUST match
         * this string, or the manifest join falls through to the no-manifest fallback. Kept
         * here as the single source of truth until the handshake binding pins it.
         */
        const val DRIVE_APP_ID = "app.tenshu.drive"

        /**
         * Drive's bundled manifest. The permission set uses the real file-service operation
         * ids so the descriptor lines up with future grant enforcement; the `FileSystem`
         * write ops are `Picker` (mediated per-invocation), reads are `Ambient` (standing
         * once granted), and `deleteFile` additionally `RequiresExplicitConfirmation`.
         */
        val DRIVE_MANIFEST = AppManifest(
            appId = DRIVE_APP_ID,
            name = "Drive",
            iconName = "drive",
            version = "1.0.0",
            shortDescription = "Files, folders, and sync across your devices.",
            permissions = listOf(
                AppPermission(
                    serviceId = "tenshu/file/service",
                    operationId = "tenshu/file/getData",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Read your files",
                ),
                AppPermission(
                    serviceId = "tenshu/file/service",
                    operationId = "tenshu/file/getPreview",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Generate file previews",
                ),
                // ⚠ Projecting any `File.Thumbnail*` relation dispatches this — it is the read
                // behind every thumbnail the app draws, and it PULLS the class from the relay when
                // this device has none. Declared late: all three thumbnail-drawing apps dispatched
                // it undeclared for the whole synced-thumbnails and XXLARGE tracks, so the Apps
                // screen under-reported them (`drive-xxlarge-thumbnail-rung.md` §9.8).
                AppPermission(
                    serviceId = "tenshu/file/service",
                    operationId = "tenshu/file/getStoredThumbnail",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Load file thumbnails synced from your other devices",
                ),
                AppPermission(
                    serviceId = "tenshu/ingest/service",
                    operationId = "tenshu/ingest/service/pickSecurityScopedFolder",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Choose folders to back up",
                ),
                AppPermission(
                    serviceId = "tenshu/file/system",
                    operationId = "tenshu/file/system/createFolder",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Create folders",
                ),
                AppPermission(
                    serviceId = "tenshu/file/system",
                    operationId = "tenshu/file/system/uploadFiles",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Add files",
                ),
                AppPermission(
                    serviceId = "tenshu/file/system",
                    operationId = "tenshu/file/system/moveFile",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Move and rename files",
                ),
                AppPermission(
                    serviceId = "tenshu/file/system",
                    operationId = "tenshu/file/system/deleteFile",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresExplicitConfirmation,
                    humanLabel = "Delete files",
                ),
            ),
        )

        /** Fit's first-party `appId` — must match `wireSchemaAnnounce(appId = …)` in Fit's DI. */
        const val FIT_APP_ID = "app.tenshu.fit"

        /**
         * Fit's bundled manifest.
         *
         * ⚠ **Narrow, deliberately.** Every Fit health *write* funnels through
         * `HealthImportWriter`, which dispatches exactly `recordWorkout` (the dense route+HR
         * path) and `recordImportedHealth` (one batched call carrying sleep, daily activity,
         * nutrition, samples, menstruation days and medical records as list params). The
         * per-kind `recordSleepSession` / `recordDailyActivity` / … operations exist on the
         * service but Fit never calls them, so listing them here would over-declare.
         *
         * The three `…HealthSource` / `…ImportRun` entries are the Data sources registry
         * (`fit-data-sources-restructure.md` DS-2) — bookkeeping *about* imports rather than
         * health data itself. They landed with their callers, one phase after the operations
         * themselves: `fitManifest_declaresOnlyTheOpsFitActuallyDispatches` refuses a permission
         * for an op nothing dispatches, so DS-1 could not declare them. ⚠⚠ The entry is not
         * optional now — an operation Fit dispatches without one fails at **dispatch on a
         * device**, not at compile: unit tests and `compileDebugKotlin` both stay green, and the
         * symptom is a runtime permission refusal from Control. ⚠ Fit is a deliberately
         * un-built `android-smoke.sh` canary, so the smoke gate will not catch it either.
         *
         * Both are [CapabilityClass.Ambient] with [GestureRequirement.None] — the honest
         * reading, not the flattering one: Fit writes from background importers and an outbox
         * drain, so there is no per-invocation gesture gate to claim, and the capability is
         * standing rather than picker-mediated.
         *
         * ⚠⚠ **This list cannot express what Fit READS**, which is the more powerful half.
         * Fit reads every health relation the service exposes (`workouts`, `sleepSessions`,
         * `healthSamples`, `menstruationDays`, `medicalRecords`, …) via service-data relations
         * and `select`/`flow()` — and relations are not operations, so an `operationId`-keyed
         * permission list has nothing to name. Drive only looks better here because *its*
         * reads happen to go through a real op (`tenshu/file/getData`). Closing that gap is a
         * permission-model question, not a Fit one; noted so the omission is not mistaken for
         * "Fit reads nothing".
         *
         * Not listed: `tenshu/health/getWorkoutStreams`. Fit never dispatches it — the
         * `Workout.Streams` on-demand property resolves **proxy-side** in
         * `materialisePendingOnDemandProperties`, so the op fires inside Control while serving
         * Fit's select rather than crossing IPC as a Fit call.
         */
        val FIT_MANIFEST = AppManifest(
            appId = FIT_APP_ID,
            name = "Fit",
            iconName = "fit",
            version = "1.0.0",
            shortDescription = "Workouts, sleep, and health history on your own devices.",
            permissions = listOf(
                AppPermission(
                    serviceId = "tenshu/health/service",
                    operationId = "tenshu/health/service/recordWorkout",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Record your workouts",
                ),
                AppPermission(
                    serviceId = "tenshu/health/service",
                    operationId = "tenshu/health/service/recordImportedHealth",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Import and store your health data",
                ),
                AppPermission(
                    serviceId = "tenshu/health/service",
                    operationId = "tenshu/health/service/deleteWorkout",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Delete your workouts",
                ),
                AppPermission(
                    serviceId = "tenshu/health/service",
                    operationId = "tenshu/health/service/registerHealthSource",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "List the health apps this device imports from",
                ),
                AppPermission(
                    serviceId = "tenshu/health/service",
                    operationId = "tenshu/health/service/recordImportRun",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Keep a history of your imports",
                ),
                AppPermission(
                    serviceId = "tenshu/health/service",
                    operationId = "tenshu/health/service/setSourceAutoImport",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Turn automatic importing on or off",
                ),
            ),
        )

        /** Maps' first-party `appId` — matches `wireSchemaAnnounce(appId = …)` in `MapsClientModule`. */
        const val MAPS_APP_ID = "app.tenshu.maps"

        /**
         * Maps' bundled manifest.
         *
         * The region ops are the honest picker case: the user chooses a region on a map and
         * confirms a multi-hundred-megabyte download, and deleting one is destructive enough to
         * warrant the same confirmation posture as Drive's `deleteFile`.
         *
         * ⚠ `recordPlace` is [CapabilityClass.Ambient] because Maps' only caller today is
         * `MapsPlaceSeeder` — the seed-through-the-real-op path, which runs unattended at
         * startup, not a user "save this place" gesture. If a real place-editing surface lands,
         * this entry is the one to re-classify.
         *
         * ⚠⚠ Not listed, and not listable: the offline **catalog** and **installed** region
         * reads are relations, not operations, so an `operationId`-keyed permission has nothing
         * to name — the same read-side gap documented on [FIT_MANIFEST].
         */
        val MAPS_MANIFEST = AppManifest(
            appId = MAPS_APP_ID,
            name = "Maps",
            iconName = "maps",
            version = "1.0.0",
            shortDescription = "Offline maps and places, without anyone watching where you look.",
            permissions = listOf(
                AppPermission(
                    serviceId = "tenshu/mapRegion/service",
                    operationId = "tenshu/mapRegion/service/downloadRegion",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Download map regions for offline use",
                ),
                AppPermission(
                    serviceId = "tenshu/mapRegion/service",
                    operationId = "tenshu/mapRegion/service/deleteRegion",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresExplicitConfirmation,
                    humanLabel = "Delete downloaded map regions",
                ),
                AppPermission(
                    serviceId = "tenshu/place/service",
                    operationId = "tenshu/place/service/recordPlace",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Save places",
                ),
            ),
        )

        /** Timeline's first-party `appId` — matches `wireSchemaAnnounce(appId = …)` in `TimelineClientModule`. */
        const val TIMELINE_APP_ID = "app.tenshu.timeline"

        /**
         * Timeline's bundled manifest — the widest first-party surface, and the one where an
         * honest declaration matters most: every entry is [CapabilityClass.Ambient] with
         * [GestureRequirement.None] because Timeline writes from **background capture** (dwells,
         * movement traces) and from import, never from a per-invocation gesture. There is no
         * picker to claim.
         *
         * ⚠ `recordWorkout` is not a copy-paste slip: `TimelineImportWriter` writes workouts
         * carried by an import into `:health-service` — the same rows Fit surfaces. Two apps
         * writing one service's data is exactly the "apps are lenses over shared data" model,
         * and the permission list is where that stops being invisible.
         *
         * ⚠ `railLinesIn` / `reverseGeocodeCandidates` are *reads* that happen to ride ops (the
         * proxy-mediated egress path), so unlike most reads they can be named here at all.
         *
         * ⚠ **`photosCapturedBetween` is what closed most of the T-P1 honesty gap.** Finding a
         * photo used to walk the user's whole file tree over the `files` relation — and relations
         * are not operations, so an `operationId`-keyed list had nothing to name. Since T-P2 the
         * walk happens inside the proxy, behind an op that *can* be named and whose label says what
         * it does. The residual understatement is real but much smaller: this op reads every image
         * file's path and capture time to answer, which the label cannot convey and the permission
         * model still cannot express ([FIT_MANIFEST] records the same limitation for Fit's health
         * reads).
         */
        val TIMELINE_MANIFEST = AppManifest(
            appId = TIMELINE_APP_ID,
            name = "Timeline",
            iconName = "timeline",
            version = "1.0.0",
            shortDescription = "Where you have been, recorded on your devices and nowhere else.",
            permissions = listOf(
                AppPermission(
                    serviceId = "tenshu/timeline/service",
                    operationId = "tenshu/timeline/service/recordMoment",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Record places you visit",
                ),
                AppPermission(
                    serviceId = "tenshu/timeline/service",
                    operationId = "tenshu/timeline/service/recordMovementTrace",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Record the routes you travel",
                ),
                AppPermission(
                    serviceId = "tenshu/timeline/service",
                    operationId = "tenshu/timeline/service/recordSnappedRoute",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Draw your train rides on the tracks they ran on",
                ),
                AppPermission(
                    serviceId = "tenshu/timeline/service",
                    operationId = "tenshu/timeline/service/recordTrip",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Group your visits into trips",
                ),
                AppPermission(
                    serviceId = "tenshu/place/service",
                    operationId = "tenshu/place/service/recordPlace",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Save places",
                ),
                AppPermission(
                    serviceId = "tenshu/mapRegion/service",
                    operationId = "tenshu/mapRegion/service/reverseGeocodeCandidates",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Name the places you visit, on-device",
                ),
                AppPermission(
                    serviceId = "tenshu/mapRegion/service",
                    operationId = "tenshu/mapRegion/service/railLinesIn",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Recognise train journeys",
                ),
                AppPermission(
                    serviceId = "tenshu/health/service",
                    operationId = "tenshu/health/service/recordWorkout",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Store workouts found in an import",
                ),
                AppPermission(
                    serviceId = "tenshu/file/service",
                    operationId = "tenshu/file/getPreview",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Show photos you took, on the moments they belong to",
                ),
                // ⚠ Projecting any `File.Thumbnail*` relation dispatches this — it is the read
                // behind every thumbnail the app draws, and it PULLS the class from the relay when
                // this device has none. Declared late: all three thumbnail-drawing apps dispatched
                // it undeclared for the whole synced-thumbnails and XXLARGE tracks, so the Apps
                // screen under-reported them (`drive-xxlarge-thumbnail-rung.md` §9.8).
                AppPermission(
                    serviceId = "tenshu/file/service",
                    operationId = "tenshu/file/getStoredThumbnail",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Load photo thumbnails synced from your other devices",
                ),
                AppPermission(
                    serviceId = "tenshu/file/system",
                    operationId = "tenshu/file/system/photosCapturedBetween",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Find which photos you took while something was happening",
                ),
                // X-6 (`drive-xxlarge-thumbnail-rung.md` §4.4): the moment hero asks whether this
                // device already holds the photo's own file, so it can skip naming a large
                // thumbnail class the device does not need. ⚠ An O(1) stat, no bytes read — but a
                // dispatch Timeline did not make before, and the Apps screen derives "Services
                // used" from these entries, so omitting it under-reports what the app does.
                AppPermission(
                    serviceId = "tenshu/file/system",
                    operationId = "tenshu/file/system/hasLocalData",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Check whether a photo is already on this device",
                ),
            ),
        )

        /** Home's first-party `appId` — matches `wireSchemaAnnounce(appId = …)` in `HomeClientModule`. */
        const val HOME_APP_ID = "app.tenshu.home"
        const val MIA_APP_ID = "app.tenshu.mia"

        /**
         * ⚠ These two must match the `wireSchemaAnnounce(appId = …)` literals in
         * `CalendarClientModule` / `ContactsClientModule`, and — because both ship a signed
         * macOS bundle — the `bundleID` pinned in their `nativeDistributions.macOS` block.
         * The desktop peer-credential gate derives its allowlist from this catalogue, so a
         * mismatch here is not a cosmetic naming slip: the app is refused at the socket and
         * spins forever on a session that can never open.
         */
        const val CALENDAR_APP_ID = "app.tenshu.calendar"
        const val CONTACTS_APP_ID = "app.tenshu.contacts"

        /**
         * ⚠ Same contract as the two above: must match `wireSchemaAnnounce(appId = …)` in
         * `PhotosClientModule` and the `bundleID` pinned in `:photos-desktop`'s
         * `nativeDistributions.macOS` block.
         */
        const val PHOTOS_APP_ID = "app.tenshu.photos"

        /**
         * Home's bundled manifest.
         *
         * ⚠ **One operation, and it is coarser than the UI suggests.** Turning a light on,
         * setting a level and opening a bridge's pairing window are three client extensions over
         * the *same* `issueDeviceCommand` op, so an `operationId`-keyed permission cannot
         * separate "control my lights" from "put my hub into pairing mode". Naming the
         * difference would need per-command capability tagging — a permission-model question
         * (`tenshu-rei.md` §2.2), not a Home one.
         *
         * [CapabilityClass.Ambient] rather than picker-mediated: commands are user-initiated in
         * today's UI, but the routines engine is designed to issue them headlessly, and a
         * standing capability is the truthful reading of where this is going.
         *
         * ⚠ Hub status is a relation (read), so it is not listable — see [FIT_MANIFEST].
         */
        val HOME_MANIFEST = AppManifest(
            appId = HOME_APP_ID,
            name = "Home",
            iconName = "home",
            version = "1.0.0",
            shortDescription = "Your lights, plugs and hubs, controlled from your own devices.",
            permissions = listOf(
                AppPermission(
                    serviceId = "tenshu/home/device/service",
                    operationId = "tenshu/home/device/service/issueDeviceCommand",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Control your smart-home devices",
                ),
            ),
        )

        /**
         * Mia (`mia-query-layer.md` MQ-1). ⚠ The declared permission is the **query
         * layer**, not the services it reads: `AskMia` is the only op Mia invokes, and
         * every cross-service read happens inside Control (MQ-D2). Naming file-service
         * here would describe a call Mia never makes and would misstate the boundary the
         * MQ-D10 validator actually enforces.
         */
        val MIA_MANIFEST = AppManifest(
            appId = MIA_APP_ID,
            name = "Mia",
            iconName = "mia",
            version = "1.0.0",
            shortDescription = "Ask about your own data, and get your own data back.",
            permissions = listOf(
                AppPermission(
                    serviceId = "tenshu/mia/service",
                    operationId = "tenshu/mia/service/askMia",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Answer questions about your files",
                ),
            ),
        )

        /**
         * Calendar (`calendar-app.md`). The declared set is the three operations the app
         * **actually invokes** — `SaveEvent`, `SaveCalendar`, `DeleteCalendar` — rather than
         * every op `:calendar-service` defines. `DeleteEvent`, `DeleteOccurrence`,
         * `DeleteReminder`, `SaveEventOverride`, `RefreshSubscriptions` and
         * `DeliverDueReminders` exist on the service but have no caller in
         * `calendar-shared-logic` / `-ui`; declaring them would describe calls the app never
         * makes and widen the grant surface for nothing. Add them when a caller appears.
         *
         * Reads are absent for a structural reason, not an oversight: Calendar reads through
         * the chain DSL over the service's data relations, and there is no read *operation*
         * to name — unlike Drive, whose `getData` / `getPreview` are real ops.
         */
        val CALENDAR_MANIFEST = AppManifest(
            appId = CALENDAR_APP_ID,
            name = "Calendar",
            iconName = "calendar",
            version = "1.0.0",
            shortDescription = "Your days, planned and synced across your own devices.",
            permissions = listOf(
                AppPermission(
                    serviceId = "tenshu/calendar/service",
                    operationId = "tenshu/calendar/service/saveEvent",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Add and edit events",
                ),
                AppPermission(
                    serviceId = "tenshu/calendar/service",
                    operationId = "tenshu/calendar/service/saveCalendar",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Create and edit calendars",
                ),
                AppPermission(
                    serviceId = "tenshu/calendar/service",
                    operationId = "tenshu/calendar/service/deleteCalendar",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresExplicitConfirmation,
                    humanLabel = "Delete calendars",
                ),
            ),
        )

        /**
         * Contacts (`contacts-app.md`). Same scoping rule as Calendar: `CreatePerson` and
         * `DeletePerson` are the only `:contact-service` ops with a caller in
         * `contacts-shared-logic` / `-ui`.
         *
         * ⚠ The invitation handshake ops (`CreateContactInvite`, `CompleteContactHandshake`,
         * `GetContactSas`, `AwaitInviteAck`, `ListPendingRedemptions`) are deliberately **not**
         * here. They are driven by Control's own pairing UI, not by the Contacts app — putting
         * them in this manifest would attribute identity-acquisition capability to the wrong
         * peer, which is exactly the boundary `contact-service-identity-model.md` draws.
         */
        val CONTACTS_MANIFEST = AppManifest(
            appId = CONTACTS_APP_ID,
            name = "Contacts",
            iconName = "contacts",
            version = "1.0.0",
            shortDescription = "The people you know, kept on your devices and nowhere else.",
            permissions = listOf(
                AppPermission(
                    serviceId = "tenshu/contact/service",
                    operationId = "tenshu/contact/service/createPerson",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Add and edit contacts",
                ),
                AppPermission(
                    serviceId = "tenshu/contact/service",
                    operationId = "tenshu/contact/service/deletePerson",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresExplicitConfirmation,
                    humanLabel = "Delete contacts",
                ),
            ),
        )

        /**
         * Photos' bundled manifest (`photos-app.md` PH-1).
         *
         * ⚠⚠ **This KDoc used to say "One permission, and no writes at all", and it stopped being
         * true two phases later.** It also said the write ops "arrive with the phases that add
         * them, not before" — PH-2 added albums and favourites, PH-5 added the trash, and neither
         * came back here. Photos dispatched twelve operations while declaring one, for months.
         *
         * ⚠⚠ **What that cost is disclosure, not function.** Control's Apps screen says in as many
         * words: *"Declared by the app. Tenshu does not currently restrict what an installed app
         * can do."* Nothing was refused — verified on a device, where Control's own Recent Events
         * shows *"Added folder: /Trash"* from an undeclared `createFolder`. The damage is that the
         * screen whose entire job is telling the user what an app may do listed only "Show your
         * photos", and **Services used** — which is derived from these entries — named only
         * `file/service`, omitting `photo/service` and `inference/service` entirely.
         *
         * ⚠ Nothing was guarding this and nothing could have. `BundledAppTablesTest`'s
         * `…declaresOnlyTheOpsFitActuallyDispatches` compares the manifest against a **hardcoded
         * set**, not against the code, so it catches over-declaration only — and only for Fit.
         * `photosManifest_declaresOnlyTheOpsPhotosActuallyDispatches` below is the same kind of
         * change-detector, and it is honest about that in its own KDoc.
         *
         * ⚠ A sweep on 2026-09-03 found this is not a Photos quirk: six of the nine bundled apps
         * under-declare, 39 operations in total. Calendar (3 declared, 10 undeclared) and Drive
         * (6 declared, 10 undeclared) are next; Maps, Home and Contacts are already correct.
         *
         * ⚠ Reads that go through the chain DSL still cannot be named here — a chain read over a
         * data relation has no operation id, which is the reason [CALENDAR_MANIFEST] records. What
         * is listed is every operation Photos dispatches that *has* an id.
         */
        val PHOTOS_MANIFEST = AppManifest(
            appId = PHOTOS_APP_ID,
            name = "Photos",
            iconName = "photos",
            version = "1.0.0",
            shortDescription = "Every picture you've backed up, newest first.",
            permissions = listOf(
                // ── reading the library ──────────────────────────────────────────────────────
                AppPermission(
                    serviceId = "tenshu/file/service",
                    operationId = "tenshu/file/getPreview",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Show your photos",
                ),
                // ⚠ Projecting any `File.Thumbnail*` relation dispatches this — it is the read
                // behind every thumbnail the app draws, and it PULLS the class from the relay when
                // this device has none. Declared late: all three thumbnail-drawing apps dispatched
                // it undeclared for the whole synced-thumbnails and XXLARGE tracks, so the Apps
                // screen under-reported them (`drive-xxlarge-thumbnail-rung.md` §9.8).
                AppPermission(
                    serviceId = "tenshu/file/service",
                    operationId = "tenshu/file/getStoredThumbnail",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Load photo thumbnails synced from your other devices",
                ),
                AppPermission(
                    serviceId = "tenshu/file/system",
                    operationId = "tenshu/file/system/hasLocalData",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Check whether a photo is stored on this device",
                ),
                // ⚠⚠ X-4 (`drive-xxlarge-thumbnail-rung.md` §4.6): the only Photos operation that
                // can pull a whole original down. The Apps screen is where a user learns that
                // opening a photograph may spend their data, so an undeclared op here hides exactly
                // the cost the proxy's metered gate exists to bound.
                AppPermission(
                    serviceId = "tenshu/file/system",
                    operationId = "tenshu/file/system/ensureThumbnailClass",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.None,
                    humanLabel = "Download a photo to make a sharper preview of it",
                ),
                // ⚠ The inference service carries its own two-switch consent (realm *and* device,
                // absence = no), so this line is not the only gate — but it is the only place the
                // user is told Photos asks a model about their pictures at all.
                AppPermission(
                    serviceId = "tenshu/inference/service",
                    operationId = "tenshu/inference/photosLike",
                    capabilityClass = CapabilityClass.Ambient,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Find photos by describing them",
                ),

                // ── albums and favourites (PH-2) ─────────────────────────────────────────────
                AppPermission(
                    serviceId = "tenshu/photo/service",
                    operationId = "tenshu/photo/service/saveAlbum",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Create and rename albums",
                ),
                AppPermission(
                    serviceId = "tenshu/photo/service",
                    operationId = "tenshu/photo/service/deleteAlbum",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresExplicitConfirmation,
                    humanLabel = "Delete albums",
                ),
                AppPermission(
                    serviceId = "tenshu/photo/service",
                    operationId = "tenshu/photo/service/addToAlbum",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Add photos to albums",
                ),
                AppPermission(
                    serviceId = "tenshu/photo/service",
                    operationId = "tenshu/photo/service/removeFromAlbum",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Remove photos from albums",
                ),
                AppPermission(
                    serviceId = "tenshu/photo/service",
                    operationId = "tenshu/photo/service/setPhotoFlags",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Mark photos as favourites",
                ),

                // ── the trash (PH-5) ─────────────────────────────────────────────────────────
                //
                // ⚠ `createFolder` is here because trashing a photograph creates `/Trash` on
                // first use. Photos never creates any other folder, and the label says so rather
                // than borrowing Drive's general "Create folders".
                AppPermission(
                    serviceId = "tenshu/file/system",
                    operationId = "tenshu/file/system/createFolder",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Create the Trash folder",
                ),
                AppPermission(
                    serviceId = "tenshu/file/system",
                    operationId = "tenshu/file/system/moveFile",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Move photos to and from the trash",
                ),
                AppPermission(
                    serviceId = "tenshu/file/system",
                    operationId = "tenshu/file/system/deleteFile",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresExplicitConfirmation,
                    humanLabel = "Delete photos permanently",
                ),
                // ⚠ Bookkeeping, and listed anyway. These two record where a trashed photograph
                // came from so it can be put back; they are not separately invocable by the user.
                // Hiding them is what this manifest already did once, and the point of the entry
                // is that the screen matches the code rather than matching a mental model of it.
                AppPermission(
                    serviceId = "tenshu/photo/service",
                    operationId = "tenshu/photo/service/recordTrashedPhoto",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Remember trashed photos so they can be restored",
                ),
                AppPermission(
                    serviceId = "tenshu/photo/service",
                    operationId = "tenshu/photo/service/clearTrashedPhoto",
                    capabilityClass = CapabilityClass.Picker,
                    gesture = GestureRequirement.RequiresUserGesture,
                    humanLabel = "Forget a trashed photo once it is restored or deleted",
                ),
            ),
        )
    }
}
