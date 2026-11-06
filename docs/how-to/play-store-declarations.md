# Play Console declarations owed before any listing

**Status:** checklist, nothing submitted (2026-08-11). No Tenshu app is listed on Google Play yet.
The per-app click-through answers (App access, content rating, target audience, Data safety, and which
of the forms below each app owes) are [`play-console-answers.md`](play-console-answers.md) (2026-10-23);
this file keeps the justification copy.

Several permissions the first-party Android apps declare are **policy-gated**: Play requires a written
declaration, and in some cases a demo video, before the app can be published — and a missing one is a
*rejection at review*, not a warning. This file records what is owed and the justification for each, so
the work is a form-filling exercise rather than a re-derivation under time pressure.

> ⚠⚠ **Verify against the live policy when you submit.** Play's permission policies change, and the
> forms move. This is our justification content and an inventory of what triggers a form; it is **not**
> a snapshot of current Play policy, and it is not legal advice. Check each item in the Console's own
> flow at submission time.

⚠ Written now because [`timeline-movement-capture.md`](../proposals/timeline-movement-capture.md) §30
re-declared `ACCESS_BACKGROUND_LOCATION` after it had once been dropped, and that permission is the one Play
scrutinises hardest. Restricting this file to that one permission would have left the identical trap
for the health permissions and the foreground-service types, which are **more** likely to block a
listing: there are **16** `health.READ_*` declarations and **4** distinct foreground-service types
across the apps, against one location permission.

---

## The inventory

Surveyed from the shipped manifests, 2026-08-11; Photos and Mia added 2026-10-13. "Form?" means Play requires an explicit declaration
beyond the manifest.

| App | Permission | Form? | Why we need it |
|---|---|---|---|
| timeline | `ACCESS_BACKGROUND_LOCATION` | **yes + video** | capture must *restart* itself after an OEM kill (§1 below) |
| timeline | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | policy-gated | Doze stops the capture loop; core function (§2) |
| fit | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | policy-gated | an OEM killed a live workout recording; asked at Start (§2) |
| timeline | `ACTIVITY_RECOGNITION` | no | step cadence separates cycling from running |
| timeline | `ACCESS_WIFI_STATE` | no | connected-AP BSSID for indoor place identity; no scanning |
| timeline, fit | `FOREGROUND_SERVICE_LOCATION` | **yes** | FGS-type declaration (§3) |
| control, drive, fit | `FOREGROUND_SERVICE_DATA_SYNC` | **yes** | FGS-type declaration (§3) |
| fit | `FOREGROUND_SERVICE_HEALTH`, `…_CONNECTED_DEVICE` | **yes** | FGS-type declaration (§3) |
| fit | 16 × `android.permission.health.READ_*` | **yes, separate** | Health Connect declaration (§4) |
| drive | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE` | **yes** | broad media access for a file manager (§5) |
| drive | `ACCESS_MEDIA_LOCATION` | no | preserves a photo's own EXIF location on upload |
| photos | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE` | **yes** | its own source picker lists what is on the phone; it uploads nothing itself (§5) |
| mia | `RECORD_AUDIO` | no (disclosure) | dictation with the on-device recogniser only; no audio leaves the device |
| contacts | `READ_CONTACTS` | no (disclosure) | the app *is* a contacts app |
| control, drive | `CAMERA` | no (disclosure) | QR scanning only — device pairing (Control) and contact-invite redemption (Drive); no photo capture |
| control | `SCHEDULE_EXACT_ALARM` | no | ⚠ the gated one is `USE_EXACT_ALARM`, which we do **not** declare |
| all | — | **Data safety form** | every app, regardless of permissions (§6) |

⚠ Not declared anywhere, and worth keeping that way: `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`,
`USE_EXACT_ALARM`, `NEARBY_WIFI_DEVICES`, `READ_PHONE_STATE`. Each carries its own
policy gate, and none is needed.

---

## 1. `ACCESS_BACKGROUND_LOCATION` (Timeline)

The hardest gate: Play requires the feature to be **core**, **prominently disclosed**, and shown in a
**demo video** of the actual in-app flow.

**Justification (draft copy for the form):**

> Timeline builds a private, on-device journal of the places the user visits and the journeys between
> them. Recording is opt-in, off by default, and controlled by a single "Capture my timeline" switch in
> the app. Background location is required because the feature is *continuous* recording, which by
> definition runs while the app is not in the foreground: the user starts capture once and expects the
> day to be recorded without opening the app again.
>
> Specifically, it is needed so recording can **resume by itself**. Android permits a foreground
> service started from the background but refuses to grant it the `location` type unless the app holds
> background location, so after the operating system or a manufacturer's battery manager kills the app,
> capture cannot restart and stays dead until the user next opens it. Measured on Android 16.
>
> Location data is stored end-to-end encrypted and is readable only on the user's own devices. It is
> not sent to us, not sold, and not shared with third parties.

⚠ Two things must be true *in the build we submit*, and both already are — check they still hold:

- **Requested in context, with a rationale, and not bundled.** `MainActivity.maybeAskForBackgroundLocation`
  shows an explanatory dialog and asks only after fine location is held (Android auto-denies a bundled
  background request), once, guarded by a pref.
- **Refusing it degrades rather than breaks.** Capture still works while the app is open, so the
  permission is genuinely an enhancement of a working feature — which is what the "core functionality"
  test is really asking.

⚠ The video must show the *user-visible* feature (the timeline filling in), not a settings screen.

## 2. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Timeline)

Play restricts this to apps whose core function is genuinely broken by Doze, and rejects it as a
general "make my app more reliable" measure.

> Timeline's core function is continuous location recording. Under Doze the capture loop is suspended
> and gaps appear in the user's day. The app asks for the exemption from within its own settings, with
> an explanation, and continues to work — with reduced accuracy — if the user declines.

⚠ It must remain a *user-initiated, explained* request. An unprompted intent on first launch is the
shape that gets rejected.

## 3. Foreground-service types

Android 14 requires each FGS to declare a type; Play requires a **separate declaration per type**
describing the user-facing feature and why a foreground service is the right mechanism.

| type | app | feature | why FGS |
|---|---|---|---|
| `location` | timeline, fit | continuous timeline capture; workout recording | must keep sampling while backgrounded |
| `dataSync` | control, drive, fit | end-to-end encrypted sync between the user's devices | uploads/downloads must survive backgrounding |
| `health` | fit | workout recording | user-initiated session that must not be killed mid-exercise |
| `connectedDevice` | fit | heart-rate sensor over Bluetooth | a live sensor session |

⚠⚠ **`dataSync` is for user-initiated work.** Play's table (answer/13392821) names "daily auto backup
of all new photos" as what it is *not* for, and the video "cannot show fully automatic or background
backup". Drive's automatic Synced Folders backup therefore runs as WorkManager jobs with no Drive
foreground service; Drive's `dataSync` declaration covers a folder upload and *Back up now*, both started
by the user (`drive-synced-folders-phase2.md` §4.1a). Control's `ProxyService` holds its `dataSync`
service only for user-started transfers (a task a client marked user-started, a map-region or model
download); automatic uploads and the sync push run as Control's own WorkManager jobs
(`control-background-work-off-fgs.md`, `play-console-answers.md` §13 D7).

⚠ Each declaration should say what the user sees (a notification they can act on) and why WorkManager
is not sufficient — for `location` and `health` the answer is that the work is *continuous sampling*
tied to a live user session, not deferrable batch work. ⚠ Where deferrable work *is* what we do, we use
`PlatformScheduler`/WorkManager already, and saying so strengthens the case for the ones we keep.

## 4. Health Connect (Fit) — a separate declaration

Fit declares **16** `health.READ_*` permissions — 15 record types plus `READ_HEALTH_DATA_HISTORY`,
which is a capability grant rather than a record type. ⚠ Since 2025 the Health Connect justification is
the second screen of the **Health apps declaration** (App content), not a separate form; it asks for a
justification per permission. Three of ours attract extra scrutiny:

- ⚠⚠ **`READ_MENSTRUATION`** is sensitive-category data — **GDPR Art. 9 special category**, as
  `MenstruationDay`'s own KDoc records. It *is* consumed: Fit's **Cycle** Browse category (`fit-app.md`
  §12 B3b), which is deliberately **data-driven** — shown only when such days exist, never gated on a
  profile sex field. The declaration must name that feature, and the answer to "why do you need it" is
  the Cycle card, not "completeness of import".
- ⚠ **`READ_HEALTH_DATA_HISTORY`** grants data older than 30 days. Health Connect otherwise clamps
  reads to 30 days, so it is what makes "import full history" possible at all; the justification must
  name that feature.
- ⚠ **`READ_EXERCISE_ROUTES`** is location data inside health data and is gated separately. It backs
  the route map on a workout's detail screen.
- ⚠⚠ **`READ_OXYGEN_SATURATION` / `READ_RESPIRATORY_RATE`** — Play **rejected** the 2026-11 declaration
  over `RespiratoryRate` ("declarations … may be mismatching with the requested data types"): both were
  imported but no screen showed them. They now back the **Vitals** detail's switcher (Resting HR /
  Blood oxygen / Breathing), and the Vitals card falls back to them when there is no resting HR. Name
  that path per permission — "Browse → Vitals → Blood oxygen" — never just the data type's name.

> Fit shows the user their own workouts and health records, imported from Health Connect at their
> request, and stores them end-to-end encrypted on their own devices. Nothing is transmitted to us or to
> third parties, and we cannot read it. Data is read only after the user grants Health Connect
> permission and chooses to import.

**The rationale screen Health Connect requires** is `HealthPermissionsRationaleActivity` (fit-android):
what Fit reads (the 15 record types by name, plus the history grant), that it only reads, where the
data goes, and the privacy policy link. Health Connect opens it two ways, both declared in the
manifest and pinned by `HealthPermissionsRationaleManifestTest`: the
`androidx.health.connect.action.SHOW_PERMISSIONS_RATIONALE` filter (the consent sheet's privacy link,
Android 13 and below) and the `ViewPermissionUsageActivity` alias with `VIEW_PERMISSION_USAGE` +
`HEALTH_PERMISSIONS` (Settings → Health Connect, Android 14+). ⚠ Its copy (`fit_hc_rationale_*`) names
every record type: a READ permission added to the manifest owes a word there, and a WRITE permission
would make "Fit only reads" false — the test fails on one in the release manifest.

⚠ **Before submitting, re-check that every declared read still has a consumer in the app.** A
permission declared but unused is a rejection risk, and — measured twice on this codebase — the class
of thing that drifts silently, since an unconsumed read fails invisibly rather than loudly.

## 5. Photo and video permissions (Drive)

Play's photo/video policy expects apps to use the system picker unless broad access is core. Drive is a
file manager whose purpose is to back up and browse the user's own files, which is the recognised
"core functionality" case — but it must be declared.

> Drive is a file manager and backup app. Users select folders to back up, and Drive uploads their
> contents end-to-end encrypted to storage only they can read. Broad media access is required because
> the user is choosing *folders to sync continuously*, not individual files at one moment, which the
> one-shot picker cannot express.

⚠ We already declare `READ_MEDIA_VISUAL_USER_SELECTED`, so the partial-access path is handled; the
declaration should say broad access is requested for continuous folder sync and the picker is used for
one-off attachment flows.

## 6. Data safety form (every app)

⚠⚠ **Superseded (2026-10-23) — see [`play-console-answers.md`](play-console-answers.md) §11.** This
section used to say *"do not answer 'no data collected' because we cannot read it"*. Play's own
definition says the opposite: data sent off the device "that is unreadable by you or anyone other than
the sender and recipient as a result of end-to-end encryption does not need to be disclosed"
(answer/10787469). Realm data is therefore not declared; what *is* declared is what leaves a device
readable — Control's account email, ids and purchase history, Drive's share-link metadata, Timeline's
opt-in geocoder lookups. The per-app answers and the uploadable CSVs live
there.

⚠ **The form also covers what our third-party SDKs send — today nothing.** No app ships ML Kit
any more (the QR scanner decodes with ZXing, on the device). ⚠⚠ **Control still packages Google's
`com.google.android.datatransport` logging**, for two libraries: MediaPipe Tasks (face grouping,
every rail) and the Play Billing Library (Play rail). Their events never leave the phone because
Control's manifest strips the transport's only upload backend (`TransportBackendDiscovery`), and
`checkNoTelemetryBackend<Variant>` fails the build if it comes back. Answer from
[`play-console-answers.md`](play-console-answers.md) §11.0; if that check is ever removed, the
SDK rows come back. (No event is even stored: with no backend, the scheduler rejects it.)
⚖️ **That covers datatransport only.** Control also packages `play-services-base/-location/-tasks`,
and any GMS API call reports call telemetry to Google Play services over IPC
(`gms.common.internal.TelemetryLogging`) — a separate channel the strip does not touch. Control
calls **no** GMS API: nothing in the repo imports `com.google.android.gms`, the libraries arrive
only as Play Billing's dependencies and Billing calls none of them, and R8 removes `GoogleApi`,
`TelemetryLogging` and every `gms.location` class from the release APK (§11.0 there). A new
Google dependency, or a first `LocationServices`/`ActivityRecognition` call, reopens this.

---

## Related

- [`timeline-movement-capture.md`](../proposals/timeline-movement-capture.md) §30 — why background
  location is required, with the system log line that proves it, and §26.6 (the superseded reasoning).
- [`docs/sync/threat-model.md`](../sync/threat-model.md) — A35 (location capture), the substance behind
  the data-safety answers.
- [`docs/policy/acceptable-use-and-limits.md`](../policy/acceptable-use-and-limits.md) — the
  user-facing policy the store listing must not contradict.
