import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {

    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false

    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false

    id("io.github.ben-manes.versions") version("0.63.1")
    id("nl.littlerobots.version-catalog-update") version("1.1.1")
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.lint) apply false
    // On the root classpath so the iOS app-framework convention below can apply it: that block is
    // the only place SKIE is applied, with its analytics off (`ios-per-app-frameworks.md` §5.2).
    alias(libs.plugins.skie) apply false
    // On the root classpath so the open-source-licences convention below can apply it to every app.
    alias(libs.plugins.aboutlibraries) apply false
}

repositories {
    mavenCentral()
    google()
}

// ---------------------------------------------------------------------------
// Adaptive Kotlin/Native concurrency throttle — machine-wide, not build-wide.
//
// Each K/N compile/link runs in its own JVM sized by `kotlin.native.jvmArgs`
// (-Xmx4G here), and an LLVM link's *resident* footprint runs well above its
// -Xmx because the codegen/optimiser allocations are off-heap. With Gradle's
// default worker cap (= CPU count, 14 on this box) and `org.gradle.parallel`,
// nothing stops a dozen of those JVMs running at once — ~14 × 4G+ demanded
// against far less physical RAM, which is what turns a single heavy link into
// thrashing or `Java heap space` OOMs.
//
// We can't size the per-link heap from inside the build — Gradle snapshots
// `kotlin.native.jvmArgs` at startup, so it must stay in gradle.properties (or
// be overridden with -P / a user-level gradle.properties on a big dev box).
// What we *can* do is bound how many of those JVMs run concurrently so the
// total fits the machine. Effect: a 7-8 GB CI runner serialises native links
// (1 at a time); a 24 GB dev box runs a few in parallel. "Less parallel on
// weaker machines", automatically.
//
// ⚠⚠ THE SEMAPHORE MUST BE CROSS-PROCESS. A Gradle `BuildService`'s
// `maxParallelUsages` is scoped to ONE BUILD INVOCATION, so sizing it from
// *physical machine* RAM holds "the total fits the machine" only while one
// build runs at a time. With one agent session per worktree, and a Gradle
// daemon serving one build at a time, five checkouts mean five daemons, five
// copies of this service, and five independent conclusions that this machine
// has room for 2 × 6 GB of native link — 60 GB demanded on a 24 GB box. Each
// build is individually well-behaved and the machine still goes to swap.
//
// So the slots are FILES, held under a machine-wide directory outside every
// checkout, taken with an OS-level `FileLock`. A lock the kernel owns is the
// only kind that five unrelated JVMs can agree on, and it is released
// automatically if a daemon is force-quit — which, on this machine, happens.
//
// Override the machine budget with -Ptenshu.native.maxParallel=<n> (CI
// pinning, debugging). Set -Ptenshu.native.slotDir=<path> to relocate the
// slots (a sandboxed CI runner with no writable $HOME).
// ⚠ The interface is a top-level script declaration but the class below is NOT,
// and the split is forced by two rules that point in opposite directions:
//   * a top-level CLASS in a `.kts` compiles to a non-static inner class of the
//     script, which Gradle cannot instantiate — it fails at EXECUTION time with
//     "Class Build_gradle.X is a non-static inner class", from whichever native
//     task happened to run first;
//   * but Kotlin does not allow a nested INTERFACE inside a local class.
// A top-level interface is implicitly static, so it is safe where a class is
// not. There is no `buildSrc` here, which would otherwise be the home for both.
interface NativeSlotParams : org.gradle.api.services.BuildServiceParameters {
    val slots: org.gradle.api.provider.Property<Int>
    val slotDir: org.gradle.api.provider.Property<String>
}

run {

abstract class NativeSlotSemaphore :
    org.gradle.api.services.BuildService<NativeSlotParams>, AutoCloseable {

    // Held locks, keyed by task path. Guarded by `this`.
    private val held = mutableMapOf<String, Pair<java.nio.channels.FileChannel, java.nio.channels.FileLock>>()

    fun acquire(taskPath: String) {
        val dir = java.io.File(parameters.slotDir.get()).apply { mkdirs() }
        val slots = parameters.slots.get()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(15)

        while (true) {
            for (i in 0 until slots) {
                val channel = java.io.RandomAccessFile(java.io.File(dir, "native-slot-$i.lock"), "rw").channel
                val lock = try {
                    channel.tryLock()
                } catch (_: java.nio.channels.OverlappingFileLockException) {
                    // This JVM already holds that slot for another task. Not an
                    // error — `tryLock` reports an out-of-process holder with
                    // null but an in-process one by throwing, and both mean the
                    // same thing to us: occupied, try the next slot.
                    null
                }
                if (lock != null) {
                    synchronized(this) { held[taskPath] = channel to lock }
                    return
                }
                channel.close()
            }
            if (System.nanoTime() > deadline) {
                // A slot can be stranded by a task that failed between acquire
                // and release inside a still-running build (see `close`). Fail
                // open rather than hang a build for a memory optimisation —
                // slow beats stuck, and the log says which it was.
                // ⚠ `Logging.getLogger`, never the script's `logger`. The latter
                // is `Project.logger`, and referencing it here would make this
                // local class capture the script instance — reintroducing the
                // non-static-inner-class failure the comment above describes.
                org.gradle.api.logging.Logging.getLogger("tenshu.native.throttle")
                    .warn("K/N throttle: no slot after 15 min; proceeding UNTHROTTLED for $taskPath")
                return
            }
            Thread.sleep(500)
        }
    }

    fun release(taskPath: String) {
        val entry = synchronized(this) { held.remove(taskPath) } ?: return
        runCatching { entry.second.release() }
        runCatching { entry.first.close() }
    }

    // Build over: drop whatever the doLast actions did not, so a failed native
    // task strands its slot only for the rest of this build, never beyond it.
    override fun close() {
        synchronized(this) { held.keys.toList() }.forEach(::release)
    }
}

    val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            as com.sun.management.OperatingSystemMXBean
    val totalRamGb = osBean.totalMemorySize / (1024.0 * 1024 * 1024)
    val cores = Runtime.getRuntime().availableProcessors()

    // Per-task footprint = the K/N JVM's -Xmx plus headroom for off-heap LLVM
    // allocations. Parse -Xmx out of kotlin.native.jvmArgs so this tracks the
    // committed heap if it's ever bumped; default to 4 GB.
    val nativeHeapGb = (providers.gradleProperty("kotlin.native.jvmArgs").orNull ?: "")
        .let { Regex("-Xmx(\\d+)([gGmM])").find(it) }
        ?.let { m ->
            val n = m.groupValues[1].toDouble()
            if (m.groupValues[2].lowercase() == "g") n else n / 1024.0
        } ?: 4.0
    val perTaskGb = nativeHeapGb * 1.5

    // Reserve for the Gradle daemon + Kotlin daemon + OS before budgeting the
    // rest to native tasks. Conservative on purpose — off-heap overruns hurt.
    // ⚠ This reserve is per-MACHINE, and so is what it must cover: with N
    // concurrent builds there are N daemons, so raising `org.gradle.jvmargs`
    // or running more sessions eats into the same 8 GB. It is not a per-build
    // allowance and must not be re-read as one.
    val reservedGb = 8.0
    val computed = ((totalRamGb - reservedGb) / perTaskGb).toInt().coerceIn(1, cores)
    val maxParallelNative = providers.gradleProperty("tenshu.native.maxParallel")
        .orNull?.toIntOrNull()?.coerceAtLeast(1) ?: computed

    // ⚠⚠ Outside every checkout ON PURPOSE. A slot directory under the project
    // root would give each worktree its own set of slots and restore exactly
    // the per-build budgeting this block exists to remove — it would look
    // throttled and behave unthrottled.
    val slotDir = providers.gradleProperty("tenshu.native.slotDir").orNull
        ?: java.io.File(System.getProperty("user.home"), ".gradle/tenshu-native-slots").path

    val throttle = gradle.sharedServices.registerIfAbsent(
        "nativeSlotSemaphore", NativeSlotSemaphore::class.java
    ) {
        parameters.slots.set(maxParallelNative)
        parameters.slotDir.set(slotDir)
        // In-build cap as well as the cross-build one: without this, every
        // runnable native task would pile into `acquire` and sit there holding
        // a Gradle worker lease, starving the rest of the build of workers
        // while it blocks. Capped here, at most `maxParallelNative` of them
        // ever wait on a file lock.
        maxParallelUsages.set(maxParallelNative)
    }

    subprojects {
        // ⚠ `path` is read into a local and the ACTIONS CAPTURE THE STRING, never
        // the task. A `doFirst { ... task.path ... }` closing over the receiver
        // holds a `Task` reference at execution time, which the configuration
        // cache rejects outright ("invocation of 'Task.project' at execution
        // time" and friends). `throttle` is a `Provider<BuildService>`, which is
        // explicitly serializable into the cache entry — that one is fine to hold.
        val gate: (org.gradle.api.Task) -> Unit = { task ->
            val path = task.path
            task.usesService(throttle)
            task.doFirst { throttle.get().acquire(path) }
            task.doLast { throttle.get().release(path) }
        }
        tasks.withType(KotlinNativeCompile::class.java).configureEach { gate(this) }
        tasks.withType(KotlinNativeLink::class.java).configureEach { gate(this) }
    }

    gradle.projectsEvaluated {
        logger.lifecycle(
            ("K/N throttle: %.0f GB RAM, %d cores, ~%.1f GB/link → max %d concurrent native task(s) " +
                "MACHINE-WIDE (slots: %s)")
                .format(totalRamGb, cores, perTaskGb, maxParallelNative, slotDir)
        )
    }
}

// ---------------------------------------------------------------------------
// Android release identity — one place for all ten apps.
//
// Signing: a REAL keystore when its coordinates are provided (`TENSHU_RELEASE_*` in the
// environment — which is how `scripts/android-release.sh` passes them from the Keychain — or
// `tenshu.release*` in the gitignored `local.properties`); otherwise the debug keystore, so
// `assembleRelease` always produces an INSTALLABLE apk. ⚠ A debug-signed release is for testing
// only: Play refuses it, and `scripts/android-release.sh` refuses to hand one over. ⚠⚠ A build for
// outside Play (the Stripe rail) gets no fallback at all: see `directDistribution` below.
//
// ⚠⚠ Every app is signed with the SAME key. Per-module copies of this block are how eight of the
// ten apps came to have no release signing at all.
//
// R8: on for every release build, without renaming — the rules live in
// `gradle/android-release-rules.pro`.
//
// Version: Play requires `versionCode` to rise with every upload, per app. One number for all of
// them (`-Ptenshu.versionCode=N`, or `gradle.properties`) keeps the set in step.
// ---------------------------------------------------------------------------
val androidLocalProperties = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun releaseSigningSetting(key: String, env: String): String? =
    (System.getenv(env) ?: androidLocalProperties.getProperty(key))?.takeIf { it.isNotBlank() }

// ⚠⚠ A build for outside Play (`-Ptenshu.android.billingRail=stripe`, commerce-store-link-out.md D3)
// never falls back to the debug key. Play re-signs what it delivers, so a debug-signed bundle is
// merely refused there; a downloaded APK has no re-signing behind it, and its key is the identity
// users' devices pin. Debug variants still build: only release packaging is refused.
val directDistribution: Boolean =
    (findProperty("tenshu.android.billingRail") as String?)?.trim() == "stripe"
val DIRECT_RELEASE_ABI = "arm64-v8a"
val releaseKeyConfigured: Boolean =
    releaseSigningSetting("tenshu.releaseStoreFile", "TENSHU_RELEASE_STORE_FILE") != null

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            // Pinned so every release machine extracts symbols with the same tools (AGP 9.4.1's
            // default). AGP installs it on first use when the SDK licences are accepted.
            ndkVersion = "28.2.13676358"
            defaultConfig {
                versionCode = (findProperty("tenshu.versionCode") as String?)?.toIntOrNull() ?: 1
                versionName = (findProperty("tenshu.versionName") as String?) ?: "1.0.1"
            }
            val release = signingConfigs.create("release") {
                val storePath = releaseSigningSetting("tenshu.releaseStoreFile", "TENSHU_RELEASE_STORE_FILE")
                if (storePath != null) {
                    storeFile = File(storePath)
                    storePassword = releaseSigningSetting("tenshu.releaseStorePassword", "TENSHU_RELEASE_STORE_PASSWORD")
                    keyAlias = releaseSigningSetting("tenshu.releaseKeyAlias", "TENSHU_RELEASE_KEY_ALIAS")
                    keyPassword = releaseSigningSetting("tenshu.releaseKeyPassword", "TENSHU_RELEASE_KEY_PASSWORD")
                } else {
                    storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
            buildTypes.getByName("release") {
                signingConfig = release
                // R8 shrinks and optimizes, but never renames (`-dontobfuscate` in the rules file).
                // ⚠ The debug build is NOT minified, so the smoke gate never runs R8 output: a
                // missing keep rule shows up only in a release build.
                isMinifyEnabled = true
                isShrinkResources = true
                // Symbols for the native libraries that still carry them (the bundled SQLite, ONNX
                // Runtime's JNI glue — the vendors strip the rest), packed into the bundle for Play's
                // crash reports. ⚠ Needs the NDK: AGP extracts and strips with its tools.
                ndk {
                    debugSymbolLevel = "SYMBOL_TABLE"
                    // A download from tenshu.app is arm64-only (android-direct-apk-distribution.md D2):
                    // a universal APK carries four copies of every native library, 131 MB for Control
                    // instead of ~35. Play splits per device, so a Play bundle stays universal.
                    // `android-release.sh --direct` refuses an APK with any other ABI in it.
                    if (directDistribution) abiFilters += DIRECT_RELEASE_ABI
                }
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    rootProject.file("gradle/android-release-rules.pro"),
                )
            }
        }
        // ⚠ This catches the debug-key fallback only. A configured key that is not the side-channel
        // key (the upload key in local.properties, say) passes here; `android-release.sh --direct`
        // is what refuses any signer other than the pinned one.
        if (directDistribution && !releaseKeyConfigured) {
            // A String, not the project: the configuration cache cannot hold a Project reference.
            val module = path
            tasks.matching { it.name in setOf("packageRelease", "signReleaseBundle", "packageReleaseUniversalApk") }.configureEach {
                doFirst {
                    throw GradleException(
                        "$module: a release with -Ptenshu.android.billingRail=stripe is for outside Play, " +
                            "and no release key is configured (TENSHU_RELEASE_STORE_FILE or " +
                            "tenshu.releaseStoreFile), so it would be signed with the debug key. " +
                            "Build it with scripts/android-release.sh --direct.",
                    )
                }
            }
        }
    }
}

// iOS test compilation + the simulator test run are ENABLED — the `ios.yml`
// CI lane (docs/proposals/ios-proxy-architecture.md §8) runs
// `iosSimulatorArm64Test` on a macOS runner, so iOS test code cannot rot
// declared-but-unbuilt. Linking iOS test executables is the heaviest K/N step (each spins up a `-Xmx4G` K/N
// JVM, see `kotlin.native.jvmArgs`), so on a local `./gradlew test` Mac dev
// host it adds cost; that cost is the price of the gate.
subprojects {
    // Make a failing test say why, on the console, the first time it fails.
    //
    // Gradle's default prints one line — the exception's top frame — and the detail lives in an
    // XML report the *next* run overwrites. For a suite with a known load-dependent flake
    // population (docs/tech-debt.md §"The full gate is flaky under parallel load") that is the
    // expensive default: a failure that reproduces once in six gates hands you a class name and
    // nothing else, so every occurrence costs another full gate to re-observe rather than being
    // diagnosed from the run that already happened.
    //
    // ⚠ `showCauses` is the load-bearing one. `kotlinx-coroutines-test` reports a leaked
    // coroutine's throw as `UncaughtExceptionsBeforeTest` in whichever test runs *next*, and the
    // original — the only thing naming the actual culprit — rides as a suppressed/cause exception.
    // Without this it is unrecoverable from the console output.
    // ⚠⚠ Never serve a test from the build cache (`org.gradle.caching=true` in gradle.properties).
    // A FROM-CACHE test restores last run's XML without executing anything, and
    // `scripts/run-gate.sh` exists precisely to refuse a green made of tests that did not run.
    // `AbstractTestTask` covers JVM/Android `Test` plus Kotlin's JS and Native test tasks.
    tasks.withType<AbstractTestTask>().configureEach {
        outputs.doNotCacheIf("tests must execute to verify anything (run-gate.sh)") { true }
    }

    // ⚠⚠ Every browser test run reads ONE shared Karma config directory, `gradle/karma.config.d/`
    // (Mocha timeout + root-only Chrome sandbox flag — each snippet says why). Kotlin reads exactly
    // one directory per test task, defaulting to the module's own `karma.config.d/`, so with
    // per-module copies a snippet reached only the modules someone remembered: the 30 s timeout
    // lived in `:tenshu-sdk-js` alone, and `:app-notify` failed public CI on Mocha's 2 s default.
    // Set at execution time because `browser()` installs its Karma framework after this block
    // runs. A module's own `karma.config.d/` would be silently ignored, so it is refused instead.
    val sharedKarmaConfig = rootProject.file("gradle/karma.config.d")
    tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
        check(project.file("karma.config.d").listFiles { f -> f.extension == "js" }.isNullOrEmpty()) {
            "${project.path}/karma.config.d is ignored — put the snippet in gradle/karma.config.d/"
        }
        inputs.dir(sharedKarmaConfig).withPropertyName("sharedKarmaConfig")
        doFirst {
            val karma = (this as org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest).testFramework
            (karma as? org.jetbrains.kotlin.gradle.targets.js.testing.karma.KotlinKarma)
                ?.useConfigDirectory(sharedKarmaConfig)
        }
    }

    tasks.withType<Test>().configureEach {
        // ⚠⚠ A test that deadlocks otherwise holds its task — and the whole gate behind it — until
        // someone notices, with nothing on the console. Past the limit Gradle stops the worker and
        // FAILS the task, which
        // `scripts/run-gate.sh` then names. The slowest test task in a full gate takes a few
        // minutes; `-Ptenshu.testTaskTimeoutMinutes=N` moves the limit, `0` removes it.
        val timeoutMinutes = (findProperty("tenshu.testTaskTimeoutMinutes") as String?)?.toLongOrNull() ?: 20L
        if (timeoutMinutes > 0) timeout.set(java.time.Duration.ofMinutes(timeoutMinutes))
        // ⚠ A test JVM with no heap set takes the JVM default, a QUARTER of the machine's RAM — 4 GB
        // each on a 16 GB CI runner, so three in parallel next to the Gradle and Kotlin daemons
        // starved it and the runner was lost mid-build. `-Ptenshu.testMaxHeap=2g` (CI sets it)
        // bounds them; unset, nothing changes locally.
        (findProperty("tenshu.testMaxHeap") as String?)?.let { maxHeapSize = it }
        // ⚠ Pin the test JVM's locale to en-US. UI tests assert the English copy, and with the
        // German catalogues present Compose resources resolve `values-de` on a machine whose
        // default locale is German — every such test then fails there and passes elsewhere.
        // `jvmArgs`, not `systemProperty`: the default `Locale` is fixed at JVM start, and the
        // command-line `-D` also wins over a `JAVA_TOOL_OPTIONS` one. Covers every `Test` task:
        // desktopTest, jvmTest and the Android host tests alike.
        jvmArgs("-Duser.language=en", "-Duser.country=US")
        // ⚠⚠ Every desktop/JVM test JVM gets a home of its OWN, never the developer's. Desktop code
        // resolves app data under `user.home` (`DesktopAppPaths` → `~/.tenshu/<appId>`, window
        // state, sockets), and the integration harnesses keep the real `SecureStorageDelegate`,
        // so two gates running at once on one machine (parallel worktrees) shared
        // `~/.tenshu/app.tenshu.control.test/` and collided on its files. The real home rides
        // along as `tenshu.test.realHome` so `DesktopAppPaths.root()` can refuse to resolve into
        // it; `TestHomeIsolationTest` (:core-common) goes red if this block stops applying.
        // ⚠ Android host tests are left alone: they cannot reach `desktopMain`, and Robolectric
        // caches its android-all jars under `~/.m2` via `user.home`.
        // One fresh directory per test TASK EXECUTION (forks of one task would share it; none fork
        // today), under this task's build/tmp, so per worktree too; old runs are swept (below). ⚠ The lambda captures only these locals — a script
        // class would be a non-static inner class (see NativeSlotParams above) and drag the
        // script into the configuration cache.
        if (!name.endsWith("UnitTest") && !name.contains("AndroidHostTest")) {
            val runsDir = layout.buildDirectory.dir("tmp/$name/isolated-home").get().asFile
            val realHome = System.getProperty("user.home")
            jvmArgumentProviders += CommandLineArgumentProvider {
                runsDir.mkdirs()
                // Bounded on a tight disk: runs older than an hour go, and at most 3 earlier runs stay beside this one
                // (a gate writes one per test task per run, across every worktree).
                val stale = System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(1)
                val runs = runsDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
                runs.filterIndexed { i, f -> i >= 3 || f.lastModified() < stale }.forEach { it.deleteRecursively() }
                val run = java.nio.file.Files.createTempDirectory(runsDir.toPath(), "run-").toFile()
                val home = java.io.File(run, "home").apply { mkdirs() }
                listOf(
                    "-Duser.home=${home.absolutePath}",
                    "-Dtenshu.test.realHome=$realHome",
                    // Skiko's extracted natives are a cache, not app state: keep sharing it.
                    "-Dskiko.data.path=$realHome/.skiko",
                )
            }
        }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }

    afterEvaluate {
        // Device-arch test executables (`iosX64Test`/`iosArm64Test`) can't run
        // on the macOS simulator host, so linking them buys nothing the
        // simulator run doesn't already cover — keep them skipped everywhere.
        tasks.findByName("iosX64Test")?.enabled = false
        tasks.findByName("iosArm64Test")?.enabled = false

        // The iOS test gate covers the *client-facing* shared surface the iOS
        // app frameworks ship (`:core*`, the service `-common`/`-client`
        // modules, and the bare schema modules). The `-proxy` and
        // `-test-utils` modules carry proxy-side code that does NOT run in the
        // iOS app under proxy-mediated EPC (ios-proxy-architecture.md §4), so
        // their iOS tests do not run here (some carry genuine K/N-vs-JVM
        // behavioural gaps in proxy logic). Their iOS *main* still compiles,
        // keeping commonMain KMP-honest.
        //
        // ⚠ `:inference-service-proxy` is the exception: on route 1 its `iosMain` — the Core ML
        // session, the model cache, the ImageIO decoder — DOES run in an iOS app, and the
        // simulator is the only place those can be tested at all
        // (`ios-on-device-inference.md` §3.2).
        val proxyOnlyOffIos = (name.endsWith("-proxy") && name != "inference-service-proxy") ||
            name.endsWith("-test-utils")
        if (proxyOnlyOffIos) {
            // Disabling the *test-run* task alone is not enough: Gradle never
            // skips a disabled task's dependencies, so the upstream test-binary
            // link (`linkDebugTestIosSimulatorArm64`) would still execute and build
            // a simulator test executable that nothing ever runs. That LLVM link is
            // the single heaviest + most memory-hungry K/N step (each runs in its
            // own `-Xmx4G` K/N JVM, see `kotlin.native.jvmArgs`), and across the
            // ~19 `-proxy`/`-test-utils` modules it is both pure wasted work and a
            // source of `Java heap space` OOMs on `linkDebugTest…`.
            // Skip the test compile + link too; `main` compilation stays enabled
            // so commonMain remains KMP-honest.
            tasks.findByName("iosSimulatorArm64Test")?.enabled = false
            tasks.findByName("iosSimulatorArm64TestBinaries")?.enabled = false
            tasks.findByName("linkDebugTestIosSimulatorArm64")?.enabled = false
            tasks.findByName("compileTestKotlinIosSimulatorArm64")?.enabled = false
        }
    }
}

// ---------------------------------------------------------------------------
// iOS app frameworks — the one place one is shaped
//
// `docs/proposals/ios-per-app-frameworks.md` D3, D9 and §5.2. Applied BY NAME to every module that
// builds a `TenshuSdk` framework an iOS app links — every `:tenshu-sdk-ios-<app>` — so a new app
// module gets it without asking. (`:tenshu-sdk-ios-core` and `-region` are plain libraries and are
// excluded.)
//
// ⚠ Here, not in a `gradle/*.gradle.kts` script plugin: an applied script compiles against its own
// classpath and cannot see the Kotlin or SKIE plugin classes this root script loads.
//
// The module declares its targets and its framework binaries, and exports what ITS app's Swift
// reads. Everything every app needs is here, so a module cannot forget it:
//
//  - **SKIE, with analytics off.** ⚠⚠ SKIE phones home build analytics by default; that is a hard
//    reject for a zero-trust product (House Rule #1's ethos). The plugin is applied HERE and only
//    here, so no module can apply SKIE without the block below. The Kotlin ⨯ SKIE version pin
//    (`docs/tech-debt.md`) covers every module this applies to.
//  - **`baseName = "TenshuSdk"`, static** (D3): 136 Swift files say `import TenshuSdk`, and the
//    shared Swift fragments (`ios-storage`, `ios-basemap`) compile into several apps under that one
//    import. Each app links exactly one framework, so the shared name never clashes.
//  - **The base export list** (D9, §7): `:tenshu-sdk-ios-core` and the modules whose types core's
//    surfaces expose. Shared Swift names core types in every app (`ios-storage` → `RealmStorageSurface`,
//    `SyncActivitySurface`; Drive/Fit/Timeline → `EpcRelayPairing`), and `export(...)` is NOT
//    transitive, so each framework lists them itself. ⚠ Core's whole public surface is therefore in
//    every framework and a dead-code root in every app: anything in core that names one app's code
//    drags that app — and its platform frameworks — into all ten (§5.3).
//  - **`api(:tenshu-sdk-ios-core)`**, which carries `:control-shared-proxy` — the full proxy graph
//    every route-1 runtime assembles. ⚠⚠ D4/§6: every app must declare the SAME service versions;
//    each app's `<App>DeclaredServiceVersionsTest` pins them.
//

val iosSdkLibraryModules = setOf("tenshu-sdk-ios-core", "tenshu-sdk-ios-region")
val iosAppFrameworkBaseExports = listOf(
    ":tenshu-sdk-ios-core",
    ":core",
    ":core-common",
    ":core-client",
    // `SyncActivitySurface`
    ":sync-service-common",
    ":sync-service-client",
    // `RealmStorageSurface`
    ":storage-service-common",
    ":storage-service-client",
    // `EpcRelayPairing`
    ":epc-rendezvous-client",
    // `ErrorCopySurface` hands Swift an `ErrorCopyKey`. ⚠ Exported, not merely an `api`
    // dependency; `scripts/check-objc-clashes.py` was run for it.
    ":app-error-handling",
)
subprojects {
    if (!name.startsWith("tenshu-sdk-ios") || name in iosSdkLibraryModules) return@subprojects
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        apply(plugin = "co.touchlab.skie")
        extensions.configure<co.touchlab.skie.plugin.configuration.SkieExtension> {
            analytics {
                enabled.set(false)
            }
        }
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
            sourceSets.matching { it.name == "iosMain" }.configureEach {
                dependencies {
                    // An exported module must be an `api` dependency of the framework's compilation.
                    iosAppFrameworkBaseExports.forEach { api(project(it)) }
                }
            }
            // ⚠⚠ The pinned service-version list every app's `<App>DeclaredServiceVersionsTest`
            // compares its graph against (§6.2, D4). One committed copy, compiled into every app
            // framework module's tests.
            sourceSets.matching { it.name == "iosTest" }.configureEach {
                kotlin.srcDir(rootProject.file("tenshu-sdk-ios-core/src/serviceVersionPin/kotlin"))
                dependencies {
                    implementation(kotlin("test"))
                }
            }
            targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
                binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.Framework>().configureEach {
                    baseName = "TenshuSdk"
                    // Static: linked into the app, not embedded — no dynamic-framework signing dance.
                    isStatic = true
                    iosAppFrameworkBaseExports.forEach { export(project(it)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Open-source licence notices — every shipped binary carries its own
//
// `docs/oss-licences.md`. Apache-2.0 §4, MIT and BSD all require the notice to travel WITH the
// binary, so each artefact gets the list of exactly what IT links, generated at build time by
// AboutLibraries from the resolved dependency graph — never a hand list that drifts:
//
//  - **Android apps** (`com.android.application`): the plugin's own per-variant task writes
//    `res/raw/aboutlibraries.json`; `:ui-core`'s Android `loadBundledLicencesJson` reads it.
//  - **Desktop apps** (`*-desktop`): `exportLibraryDefinitionsJvm`, wired into `jvmMain`'s resources
//    at `/aboutlibraries.json`, read by the desktop `actual`.
//  - **iOS app frameworks** (`:tenshu-sdk-ios-<app>`): `exportLibraryDefinitions`, which
//    `scripts/ios-build-sdk.sh` runs and the `OssLicences` XcodeGen template copies into the app
//    bundle as `kotlin-licences.json` (Swift-side code is listed by hand in `ios-about/`).
//
// ⚠ Here, not in a `gradle/*.gradle.kts` script plugin, for the reason the iOS block above gives: an
// applied script cannot see plugin classes this root script loads.
// ⚠ OFFLINE mode: licence TEXTS come from `config/oss-licences/licenses/` (checked-in SPDX copies),
// never from the network. Online fetching can drop a licence text (Apache-2.0, say) from an app's
// JSON on a flaky connection with nothing but a log line — a notice that silently loses its
// licence text is the one failure this exists to prevent. `OssLicencesJsonTest` pins it.
// ⚠ `strictMode = WARN` + the allow-list below makes every build PRINT any dependency outside the
// permissive set — the audit in `docs/oss-licences.md` §4 is what a human decided about each one.
// Never flip it to FAIL without first deciding the open items there.
val ossLicencePermissive = listOf(
    "Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD", "Unlicense", "CC0-1.0",
    "Zlib", "BSL-1.0", "MIT-0", "Bouncy Castle Licence", "Android Software Development Kit License",
)
// Dual-licensed components whose POM also names a GPL licence, allowed for THAT library only — the
// permissive alternative we elect is recorded in `docs/oss-licences.md` §4.1. Keyed by the
// plugin's licence hash (what its warning prints), never the GPL licence as such: anything else
// under these licences still warns.
val ossLicenceDualElected = mapOf(
    // JavaCPP (via MapLibre's native FFI): Apache-2.0, or GPLv2+CPE, or GPL-2.0+. We elect Apache-2.0.
    "074cc43976712959e080ddcdfc85831f" to listOf("org.bytedeco:javacpp"),
    "db199403a2b13a9b762a9bf2bca5b8c6" to listOf("org.bytedeco:javacpp"),
    // Checker Framework annotations: MIT, or GPLv2+CPE. We elect MIT.
    "da5e524a4235731b9b96281090f5f82d" to listOf("org.checkerframework:checker-compat-qual"),
)
fun Project.applyOssLicences() {
    apply(plugin = "com.mikepenz.aboutlibraries.plugin")
    extensions.configure<com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension> {
        // Hermetic: licence texts come from `config/oss-licences/licenses`, never the network.
        offlineMode.set(true)
        collect {
            // BOMs / platforms ship no code.
            includePlatform.set(false)
            // Licence texts the plugin cannot fetch (a POM licence with no SPDX id), by hash.
            configPath.set(rootProject.layout.projectDirectory.dir("config/oss-licences"))
        }
        license {
            strictMode.set(com.mikepenz.aboutlibraries.plugin.StrictMode.WARN)
            allowedLicenses.addAll(ossLicencePermissive)
            allowedLicensesMap.putAll(ossLicenceDualElected)
        }
    }
}
/** Points the KMP target task `exportLibraryDefinitions<Target>` at a stable per-target file. */
fun Project.ossLicencesExport(target: String): Provider<RegularFile> {
    val file = layout.buildDirectory.file("generated/ossLicences/$target/aboutlibraries.json")
    extensions.configure<com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension> {
        exports.create(target) { outputFile.set(file) }
    }
    return file
}
subprojects {
    plugins.withId("com.android.application") { applyOssLicences() }
    if (name.endsWith("-desktop")) {
        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            applyOssLicences()
            val json = ossLicencesExport("jvm")
            // The task is registered when the module declares `jvm()`, i.e. after this runs.
            val exportTask = tasks.named { it == "exportLibraryDefinitionsJvm" }
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
                sourceSets.matching { it.name == "jvmMain" }.configureEach {
                    resources.srcDir(files(json.map { it.asFile.parentFile }).builtBy(exportTask))
                }
            }
        }
    }
    if (name.startsWith("tenshu-sdk-ios") && name !in iosSdkLibraryModules) {
        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            applyOssLicences()
            // ⚠ The DEVICE slice's graph, also for a simulator build: it is the one that ships, and
            // the two resolve the same modules.
            // ⚠⚠ Through the GLOBAL `exportLibraryDefinitions`, not the plugin's per-target
            // `exportLibraryDefinitionsIosArm64`: Kotlin/Native resolves `iosArm64CompileKlibraries`,
            // which the default `*CompileClasspath`/`*RuntimeClasspath` selection never matches, and
            // with `collect.all` the per-target task's exact-variant lookup then matches nothing
            // either — both produce an EMPTY list with only an info line (measured, plugin 15.2.0).
            // `all` + a name filter on the variant-less task selects every resolvable
            // `iosArm64*` configuration (test ones stay excluded).
            extensions.configure<com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension> {
                collect {
                    all.set(true)
                    filterVariants.add("iosArm64")
                }
                export { outputFile.set(layout.buildDirectory.file("generated/ossLicences/iosArm64/aboutlibraries.json")) }
            }
        }
    }
}

// ── Copyright lines and NOTICE files: what the POMs do not carry ────────────────────────────────
//
// The plugin builds each notice from POM metadata, which has neither of the two things the licences
// themselves demand (`docs/oss-licences.md` §1.1):
//
//  - **The copyright line.** MIT, BSD-2/3 and ISC require "the above copyright notice", but the
//    checked-in SPDX texts are templates (`Copyright (c) <year> <copyright holders>`). Each library
//    on such a licence gets its OWN copy of the text with the template line replaced.
//  - **Apache-2.0 §4(d) NOTICE files.** Reproduced verbatim as an extra "NOTICE" entry on the
//    library, which every screen (Compose, SwiftUI) shows after the licence text.
//
// Both come from the resolved ARTIFACTS first — LICENSE*/COPYING* lines starting `Copyright`,
// and NOTICE / NOTICE.txt / ThirdPartyNotices.txt files — so a new dependency is covered by the
// next build without anyone looking. Only what an artifact does not carry is hand-kept, in
// `config/oss-licences/copyrights.json` and `config/oss-licences/notices/` (README there).
//
// ⚠⚠ It runs as a `doLast` on the plugin's own task and rewrites that task's output in place, so
// every consumer (Android `res/raw`, desktop resources, the iOS bundle copy) gets the enriched file
// with no rewiring. A template-licensed library with no copyright line FAILS the build, naming it:
// shipping `<copyright holders>` is the defect this exists to prevent. `OssLicencesJsonTest` pins
// the result on desktop.
// ⚠ Kotlin/Native klibs carry no LICENSE/NOTICE files, so the iOS frameworks get the hand-kept
// half only (no iOS-linked library needs either).
run {
    class OssNoticesAction(
        private val outputs: org.gradle.api.file.FileCollection,
        private val artifacts: Provider<Set<org.gradle.api.artifacts.result.ResolvedArtifactResult>>?,
        private val copyrights: java.io.File,
        private val noticesDir: java.io.File,
    ) : org.gradle.api.Action<org.gradle.api.Task> {

        private val placeholder = Regex("<(year|owner|copyright holders?)>")
        private val platformSuffix = Regex("-(android|jvm|desktop|iosarm64|iossimulatorarm64|iosx64|js|wasm-js)$")

        override fun execute(task: org.gradle.api.Task) {
            val files = outputs.asFileTree.matching { include("**/aboutlibraries.json") }.files
                .ifEmpty { outputs.files.filter { it.isFile && it.name.endsWith(".json") }.toSet() }
            files.forEach { enrich(it) }
        }

        @Suppress("UNCHECKED_CAST")
        private fun enrich(file: java.io.File) {
            val root = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
            val libraries = (root["libraries"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }
            val licences = LinkedHashMap((root["licenses"] as Map<String, Map<String, Any?>>?).orEmpty())
            val ids = libraries.map { it["uniqueId"] as String }.toSet()
            fun libraryOf(group: String, module: String): String? =
                "$group:$module".takeIf { it in ids } ?: "$group:${module.replace(platformSuffix, "")}".takeIf { it in ids }

            // What the artifacts say, per library.
            val foundCopyright = mutableMapOf<String, LinkedHashSet<String>>()
            val foundNotices = mutableMapOf<String, LinkedHashSet<String>>()
            artifacts?.get()?.forEach { artifact ->
                val id = artifact.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
                    ?: return@forEach
                val library = libraryOf(id.group, id.module) ?: return@forEach
                readEntries(artifact.file) { name, text ->
                    val base = name.substringAfterLast('/').lowercase()
                    when {
                        base == "notice" || base == "notice.txt" || base == "notice.md" ||
                            base.startsWith("thirdpartynotices") ->
                            text.trim().takeIf { it.isNotEmpty() }?.let { foundNotices.getOrPut(library) { LinkedHashSet() }.add(it) }
                        base.startsWith("license") || base.startsWith("licence") || base.startsWith("copying") ->
                            text.lineSequence().map { it.trim() }
                                .filter { it.startsWith("Copyright") && it.none { c -> c == '<' || c == '[' || c == '{' } }
                                .take(5)
                                .forEach { foundCopyright.getOrPut(library) { LinkedHashSet() }.add(it) }
                    }
                }
            }

            // What the hand-kept config adds: exact ids, or `group:prefix*`.
            val overrides = (if (copyrights.isFile) groovy.json.JsonSlurper().parse(copyrights) as Map<String, Any?> else emptyMap())
                .filterKeys { !it.startsWith("_") }
            fun override(id: String): List<String>? {
                val hit = overrides[id] ?: overrides.entries.firstOrNull { (k, _) -> k.endsWith("*") && id.startsWith(k.dropLast(1)) }?.value
                return when (hit) { null -> null; is String -> listOf(hit); else -> (hit as List<String>) }
            }
            // `notices/<group>__<artifact>@<version>.txt`: pinned to the version it was copied from.
            val handNotices = noticesDir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().associate { f ->
                val stem = f.name.removeSuffix(".txt")
                stem.substringBefore('@').replace("__", ":") to (stem.substringAfter('@', "") to f.readText().trim())
            }

            val missing = mutableListOf<String>()
            val stale = mutableListOf<String>()
            libraries.forEach { library ->
                val id = library["uniqueId"] as String
                val keys = (library["licenses"] as List<String>?).orEmpty().toMutableList()
                val rewritten = keys.map { key ->
                    val licence = licences[key] ?: return@map key
                    val text = licence["content"] as String? ?: return@map key
                    if (!placeholder.containsMatchIn(text)) return@map key
                    val lines = override(id) ?: foundCopyright[id]?.toList()
                    if (lines.isNullOrEmpty()) { missing += "$id ($key)"; return@map key }
                    val own = "$key@$id"
                    licences[own] = LinkedHashMap(licence).apply {
                        put("hash", own)
                        put("content", text.lineSequence().joinToString("\n") { line ->
                            if (placeholder.containsMatchIn(line) && line.trimStart().startsWith("Copyright")) lines.joinToString("\n") else line
                        })
                    }
                    own
                }.toMutableList()
                val notices = LinkedHashSet(foundNotices[id].orEmpty())
                handNotices[id]?.let { (version, text) ->
                    if (version.isNotEmpty() && version != library["artifactVersion"]) {
                        stale += "$id: notices/ copy is for $version, the build links ${library["artifactVersion"]}"
                    } else notices += text
                }
                notices.forEach { text ->
                    val key = "NOTICE-" + java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    licences.getOrPut(key) { linkedMapOf("hash" to key, "name" to "NOTICE", "url" to null, "content" to text) }
                    rewritten += key
                }
                library["licenses"] = rewritten
            }
            if (missing.isNotEmpty() || stale.isNotEmpty()) {
                throw org.gradle.api.GradleException(
                    "Open-source notices for ${file.path}:\n" +
                        missing.joinToString("") { "  - no copyright line for $it: its licence text is a template. Add it to config/oss-licences/copyrights.json\n" } +
                        stale.joinToString("") { "  - stale NOTICE override, $it: re-copy it into config/oss-licences/notices/\n" } +
                        "  (docs/oss-licences.md §1.1)",
                )
            }
            // Drop the templates nothing references any more, so no shipped text carries a placeholder.
            val used = libraries.flatMap { (it["licenses"] as List<String>) }.toSet()
            licences.keys.retainAll { it in used || !placeholder.containsMatchIn(licences[it]?.get("content") as String? ?: "") }
            file.writeText(groovy.json.JsonOutput.toJson(LinkedHashMap(root).apply {
                put("libraries", libraries); put("licenses", licences)
            }))
        }

        /** Every LICENSE/NOTICE-shaped entry of a jar/aar (or an exploded directory). */
        private fun readEntries(file: java.io.File, visit: (String, String) -> Unit) {
            fun wanted(name: String): Boolean {
                val base = name.substringAfterLast('/').lowercase()
                return !base.endsWith(".class") && (base.startsWith("notice") || base.startsWith("thirdpartynotices") ||
                    base.startsWith("license") || base.startsWith("licence") || base.startsWith("copying"))
            }
            when {
                file.isDirectory -> file.walkTopDown().filter { it.isFile && wanted(it.name) }
                    .forEach { visit(it.name, it.readText()) }
                file.isFile && (file.name.endsWith(".jar") || file.name.endsWith(".aar")) -> runCatching {
                    java.util.zip.ZipFile(file).use { zip ->
                        zip.entries().asSequence().filter { !it.isDirectory && wanted(it.name) && it.size < 2_000_000 }
                            .forEach { visit(it.name, zip.getInputStream(it).bufferedReader().readText()) }
                    }
                }
            }
        }
    }

    val configDir = rootProject.layout.projectDirectory.dir("config/oss-licences")
    subprojects {
        // Which configuration's artifacts each consumed task describes: Android `prepareLibraryDefinitions<Variant>`
        // → `<variant>RuntimeClasspath` (the `res/raw` copy); desktop `exportLibraryDefinitionsJvm`
        // → `jvmRuntimeClasspath`; the iOS frameworks' `exportLibraryDefinitions` → none (klibs).
        fun classpathFor(taskName: String): Pair<String, String>? = when {
            taskName.startsWith("prepareLibraryDefinitions") ->
                (taskName.removePrefix("prepareLibraryDefinitions").replaceFirstChar { it.lowercase() } + "RuntimeClasspath") to "android-classes-jar"
            taskName == "exportLibraryDefinitionsJvm" -> "jvmRuntimeClasspath" to "jar"
            else -> null
        }
        val consumed = { t: org.gradle.api.Task ->
            t.name.startsWith("prepareLibraryDefinitions") || t.name == "exportLibraryDefinitionsJvm" ||
                (t.name == "exportLibraryDefinitions" && t.project.name.startsWith("tenshu-sdk-ios"))
        }
        plugins.withId("com.mikepenz.aboutlibraries.plugin") {
            tasks.withType(com.mikepenz.aboutlibraries.plugin.AboutLibrariesTask::class.java).matching(consumed).configureEach {
                val artifacts = classpathFor(name)?.let { (configuration, type) ->
                    configurations.getByName(configuration).incoming.artifactView {
                        lenient(true)
                        // ⚠ External modules only: our own modules ship no third-party notices, and
                        // asking for their jars would make the licence task wait on every compile.
                        componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                        attributes { attribute(Attribute.of("artifactType", String::class.java), type) }
                    }.artifacts
                }
                if (artifacts != null) {
                    inputs.files(artifacts.artifactFiles).withPropertyName("ossNoticeArtifacts")
                        .withPathSensitivity(PathSensitivity.NONE)
                }
                inputs.dir(configDir).withPropertyName("ossNoticeConfig").withPathSensitivity(PathSensitivity.RELATIVE)
                doLast(OssNoticesAction(outputs.files, artifacts?.resolvedArtifacts, configDir.file("copyrights.json").asFile, configDir.dir("notices").asFile))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// iOS Xcode project generation (XcodeGen)
// ---------------------------------------------------------------------------
// `control-ios/*.xcodeproj` and `drive-ios/*.xcodeproj` are gitignored and
// generated from each app's tracked `project.yml` via XcodeGen. Because
// XcodeGen resolves `sources: - path: Sources` at generate time and lists every
// Swift file individually in the .pbxproj, a checkout that adds/removes a file
// leaves a stale project (symptom: "<Type> not found in scope" in a sibling
// file). These tasks regenerate the projects; the `.githooks/` hooks run the
// same thing automatically on checkout/merge once installed.
//
// Requires XcodeGen on PATH (`brew install xcodegen`).
//
// project.yml resolves ${TENSHU_TEAM_ID} at generate time — the [sdk=iphoneos*] device
// signing team (ios-signing.md §2). Same resolution order as the macOS signing flow
// (-Ptenshu.teamId → $TENSHU_TEAM_ID → local.properties); absent → empty team, which
// only matters for real-device builds (simulator config is unconditional).
val tenshuIosTeamId: String =
    (project.findProperty("tenshu.teamId") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("TENSHU_TEAM_ID")?.takeIf { it.isNotBlank() }
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
            java.util.Properties().apply { f.inputStream().use { load(it) } }
                .getProperty("tenshu.teamId")
        }?.takeIf { it.isNotBlank() }
        ?: ""

val generateControlIosProject by tasks.registering(Exec::class) {
    group = "ios"
    description = "Regenerate control-ios/ControlIos.xcodeproj from project.yml (XcodeGen)."
    workingDir = layout.projectDirectory.dir("control-ios").asFile
    inputs.file("control-ios/project.yml")
    inputs.dir("control-ios/Sources")
    outputs.dir("control-ios/ControlIos.xcodeproj")
    environment("TENSHU_TEAM_ID", tenshuIosTeamId)
    executable = "xcodegen"
    args("generate")
}

val generateDriveIosProject by tasks.registering(Exec::class) {
    group = "ios"
    description = "Regenerate drive-ios/DriveIos.xcodeproj from project.yml (XcodeGen)."
    workingDir = layout.projectDirectory.dir("drive-ios").asFile
    inputs.file("drive-ios/project.yml")
    inputs.dir("drive-ios/Sources")
    outputs.dir("drive-ios/DriveIos.xcodeproj")
    environment("TENSHU_TEAM_ID", tenshuIosTeamId)
    executable = "xcodegen"
    args("generate")
}

val generateTimelineIosProject by tasks.registering(Exec::class) {
    group = "ios"
    description = "Regenerate timeline-ios/TimelineIos.xcodeproj from project.yml (XcodeGen)."
    workingDir = layout.projectDirectory.dir("timeline-ios").asFile
    inputs.file("timeline-ios/project.yml")
    inputs.dir("timeline-ios/Sources")
    outputs.dir("timeline-ios/TimelineIos.xcodeproj")
    environment("TENSHU_TEAM_ID", tenshuIosTeamId)
    executable = "xcodegen"
    args("generate")
}

val generateFitIosProject by tasks.registering(Exec::class) {
    group = "ios"
    description = "Regenerate fit-ios/FitIos.xcodeproj from project.yml (XcodeGen)."
    workingDir = layout.projectDirectory.dir("fit-ios").asFile
    inputs.file("fit-ios/project.yml")
    inputs.dir("fit-ios/Sources")
    outputs.dir("fit-ios/FitIos.xcodeproj")
    environment("TENSHU_TEAM_ID", tenshuIosTeamId)
    executable = "xcodegen"
    args("generate")
}

val generateHomeIosProject by tasks.registering(Exec::class) {
    group = "ios"
    description = "Regenerate home-ios/HomeIos.xcodeproj from project.yml (XcodeGen)."
    workingDir = layout.projectDirectory.dir("home-ios").asFile
    inputs.file("home-ios/project.yml")
    inputs.dir("home-ios/Sources")
    outputs.dir("home-ios/HomeIos.xcodeproj")
    environment("TENSHU_TEAM_ID", tenshuIosTeamId)
    executable = "xcodegen"
    args("generate")
}

val generateMapsIosProject by tasks.registering(Exec::class) {
    group = "ios"
    description = "Regenerate maps-ios/MapsIos.xcodeproj from project.yml (XcodeGen)."
    workingDir = layout.projectDirectory.dir("maps-ios").asFile
    inputs.file("maps-ios/project.yml")
    inputs.dir("maps-ios/Sources")
    outputs.dir("maps-ios/MapsIos.xcodeproj")
    environment("TENSHU_TEAM_ID", tenshuIosTeamId)
    executable = "xcodegen"
    args("generate")
}

val generateContactsIosProject by tasks.registering(Exec::class) {
    group = "ios"
    description = "Regenerate contacts-ios/ContactsIos.xcodeproj from project.yml (XcodeGen)."
    workingDir = layout.projectDirectory.dir("contacts-ios").asFile
    inputs.file("contacts-ios/project.yml")
    inputs.dir("contacts-ios/Sources")
    outputs.dir("contacts-ios/ContactsIos.xcodeproj")
    environment("TENSHU_TEAM_ID", tenshuIosTeamId)
    executable = "xcodegen"
    args("generate")
}

val generateCalendarIosProject by tasks.registering(Exec::class) {
    group = "ios"
    description = "Regenerate calendar-ios/CalendarIos.xcodeproj from project.yml (XcodeGen)."
    workingDir = layout.projectDirectory.dir("calendar-ios").asFile
    inputs.file("calendar-ios/project.yml")
    inputs.dir("calendar-ios/Sources")
    outputs.dir("calendar-ios/CalendarIos.xcodeproj")
    environment("TENSHU_TEAM_ID", tenshuIosTeamId)
    executable = "xcodegen"
    args("generate")
}

val generatePhotosIosProject by tasks.registering(Exec::class) {
    group = "ios"
    description = "Regenerate photos-ios/PhotosIos.xcodeproj from project.yml (XcodeGen)."
    workingDir = layout.projectDirectory.dir("photos-ios").asFile
    inputs.file("photos-ios/project.yml")
    inputs.dir("photos-ios/Sources")
    outputs.dir("photos-ios/PhotosIos.xcodeproj")
    environment("TENSHU_TEAM_ID", tenshuIosTeamId)
    executable = "xcodegen"
    args("generate")
}

val generateMiaIosProject by tasks.registering(Exec::class) {
    group = "ios"
    description = "Regenerate mia-ios/MiaIos.xcodeproj from project.yml (XcodeGen)."
    workingDir = layout.projectDirectory.dir("mia-ios").asFile
    inputs.file("mia-ios/project.yml")
    inputs.dir("mia-ios/Sources")
    outputs.dir("mia-ios/MiaIos.xcodeproj")
    environment("TENSHU_TEAM_ID", tenshuIosTeamId)
    executable = "xcodegen"
    args("generate")
}

// The on-device inference measurement harness (docs/proposals/ios-on-device-inference.md §4.2): a project
// like the ten apps', never shipped and in no smoke set.
val generateInferenceHarnessIosProject by tasks.registering(Exec::class) {
    group = "ios"
    description = "Regenerate inference-harness-ios/In3mHarness.xcodeproj from project.yml (XcodeGen)."
    workingDir = layout.projectDirectory.dir("inference-harness-ios").asFile
    inputs.file("inference-harness-ios/project.yml")
    inputs.dir("inference-harness-ios/Sources")
    outputs.dir("inference-harness-ios/In3mHarness.xcodeproj")
    environment("TENSHU_TEAM_ID", tenshuIosTeamId)
    executable = "xcodegen"
    args("generate")
}

tasks.register("generateXcodeProjects") {
    group = "ios"
    description = "Regenerate all iOS Xcode projects (control-ios + drive-ios + timeline-ios + " +
        "fit-ios + home-ios + mia-ios + maps-ios + contacts-ios + calendar-ios + photos-ios + " +
        "the IN-3m harness)."
    dependsOn(
        generateControlIosProject, generateDriveIosProject, generateTimelineIosProject,
        generateFitIosProject, generateHomeIosProject, generateMiaIosProject,
        generateMapsIosProject, generateContactsIosProject, generateCalendarIosProject,
        generatePhotosIosProject, generateInferenceHarnessIosProject,
    )
}

tasks.register("iosKotlinCheck") {
    group = "verification"
    description = "Compile every iosMain Kotlin source set. NOT covered by " +
        "`desktopTest jvmTest test` — see the comment below."
    // ⚠⚠ `./gradlew desktopTest jvmTest test` does NOT compile a single line of `iosMain`.
    // It builds the JVM / desktop / Android targets; the iOS source sets are only built by an
    // iOS compile task. Seventeen modules have an `iosMain`, so the exposure is wide — and the
    // failure is silent: a missing import in `iosMain` passes the full gate ("1893 tasks, all
    // executed") and surfaces only when someone builds for iOS.
    //
    // Every iOS app links one `:tenshu-sdk-ios-<app>` framework (`ios-per-app-frameworks.md`), so
    // compiling the ten app modules, plus `-core` and `-region` they build on, for one simulator
    // arch typechecks every `iosMain` any app reaches: the union of their dependency graphs. One
    // arch, not four: this is a source-level check, and the other
    // slices would report the same errors for four times the wall clock.
    // ⚠ A new iOS app module must be added here, or its code compiles in no check.
    dependsOn(":tenshu-sdk-ios-core:compileKotlinIosSimulatorArm64")
    dependsOn(":tenshu-sdk-ios-region:compileKotlinIosSimulatorArm64")
    dependsOn(":tenshu-sdk-ios-control:compileKotlinIosSimulatorArm64")
    dependsOn(":tenshu-sdk-ios-drive:compileKotlinIosSimulatorArm64")
    dependsOn(":tenshu-sdk-ios-calendar:compileKotlinIosSimulatorArm64")
    dependsOn(":tenshu-sdk-ios-contacts:compileKotlinIosSimulatorArm64")
    dependsOn(":tenshu-sdk-ios-home:compileKotlinIosSimulatorArm64")
    dependsOn(":tenshu-sdk-ios-mia:compileKotlinIosSimulatorArm64")
    dependsOn(":tenshu-sdk-ios-maps:compileKotlinIosSimulatorArm64")
    dependsOn(":tenshu-sdk-ios-photos:compileKotlinIosSimulatorArm64")
    dependsOn(":tenshu-sdk-ios-timeline:compileKotlinIosSimulatorArm64")
    dependsOn(":tenshu-sdk-ios-fit:compileKotlinIosSimulatorArm64")

    // ⚠⚠ **The app frameworks do not in fact reach everything.** `:ui-core` has an `iosMain` (design
    // tokens for Compose-Multiplatform-on-iOS, consumed by `:ui-map`) and nothing in the SDK's
    // dependency graph pulls it in, so without the line below an `iosMain` file added there
    // compiles **nowhere** in this task, which reports success in seconds having not built it.
    //
    // ⚠ Which is to say the app frameworks are a *heuristic*, not a proof: they cover whatever the SDK
    // happens to depend on. A module with an `iosMain` that no iOS binary links is exactly the one
    // that needs this most, because nothing else will ever compile it.
    dependsOn(":ui-core:compileKotlinIosSimulatorArm64")

    // ⚠⚠ Refuse rather than pass. Kotlin/Native will not cross-compile an Apple target off macOS
    // — every `compileKotlinIosSimulatorArm64` is skipped with `onlyIf 'Cross compilation should
    // be supported on host'` — so on Linux this task would report BUILD SUCCESSFUL having
    // compiled nothing at all, which is the *worst* kind of green: it carries zero information
    // and reads exactly like the real thing. Every Claude Code remote session runs on Linux, and
    // `AGENTS.md` names this task as THE gate for an `iosMain` change, so a false green would be
    // load-bearing in the one place it could do most harm.
    //
    // ⚠ CI is unaffected: `.github/workflows/ios.yml` runs on `macos-latest` and invokes
    // `iosSimulatorArm64Test` + the per-app framework links, never this task.
    doFirst {
        val os = System.getProperty("os.name").orEmpty()
        check(os.startsWith("Mac", ignoreCase = true)) {
            "iosKotlinCheck cannot verify anything on $os — Kotlin/Native only compiles Apple " +
                "targets on macOS, so every iOS compile task is skipped and a pass would mean " +
                "nothing. Run it on macOS; an iosMain change made elsewhere is unverified. " +
                "See docs/tech-debt.md \"iosKotlinCheck was a no-op off macOS\"."
        }
    }
}

tasks.register("iosTestCheck") {
    group = "verification"
    description = "Run every module's iOS simulator test suite. NOT covered by " +
        "`scripts/run-gate.sh` — see the comment below."
    // ⚠⚠ These tests are real, and nothing else runs them locally.
    //
    // `scripts/run-gate.sh` is `desktopTest jvmTest test testAndroidHostTest`;
    // `iosSimulatorArm64Test` is in none of those and `test` does not aggregate it. The task is
    // enabled (above) and wired otherwise only to `.github/workflows/ios.yml` — which is intermittently
    // dark (`docs/tech-debt.md` → "CI blocked by GitHub Actions billing"). So this is the only local
    // way the suite that can catch an **iOS-vs-JVM behavioural divergence** runs.
    //
    // What it protects is not hypothetical: `LibsodiumCryptoBackendsTest` pins the iOS crypto
    // backends against **JVM vectors** — the X25519 shared secret, Ed25519, Argon2id with the
    // production salt, and the iOS AEAD decrypting a **JVM-produced** blob. Those four are exactly
    // what a pairing handshake between an iPhone and an Android phone rests on.
    //
    // ⚠ Deliberately its OWN task rather than a line in `run-gate.sh`: that gate must stay runnable
    // on Linux, and every Apple target is silently skipped there. Same reason `iosKotlinCheck` is
    // separate, and it refuses off macOS for the same reason (below).
    //
    // ⚠ Enumerated, not discovered: `subprojects { … }` at configuration time would couple this to
    // the enable/disable policy above, and a module that quietly loses its iOS tests should show up
    // as a deleted line here rather than as a silently smaller run.
    // ⚠ There is no umbrella `:tenshu-sdk-ios`: its tests live in the app modules or in
    // `:tenshu-sdk-ios-core` (`ios-per-app-frameworks.md`).
    listOf(
        ":app-outbox",
        ":core-common",
        ":fit-projection",
        ":fit-shared-data",
        ":fit-shared-file",
        ":inference-harness-ios",
        ":inference-service-proxy",
        ":map-region-app",
        ":tenshu-sdk-ios-calendar",
        ":tenshu-sdk-ios-contacts",
        ":tenshu-sdk-ios-control",
        ":tenshu-sdk-ios-core",
        ":tenshu-sdk-ios-drive",
        ":tenshu-sdk-ios-fit",
        ":tenshu-sdk-ios-home",
        ":tenshu-sdk-ios-maps",
        ":tenshu-sdk-ios-mia",
        ":tenshu-sdk-ios-photos",
        ":tenshu-sdk-ios-timeline",
        ":timeline-projection",
        ":timeline-shared-data",
    ).forEach { dependsOn("$it:iosSimulatorArm64Test") }

    // ⚠⚠ Refuse rather than pass — identical reasoning to `iosKotlinCheck`. Off macOS every iOS
    // test task is skipped, and BUILD SUCCESSFUL would mean nothing while reading exactly like the
    // real thing.
    doFirst {
        val os = System.getProperty("os.name").orEmpty()
        check(os.startsWith("Mac", ignoreCase = true)) {
            "iosTestCheck cannot verify anything on $os — Kotlin/Native only builds Apple " +
                "targets on macOS, so every iOS test task is skipped and a pass would mean " +
                "nothing. Run it on macOS."
        }
    }
}

tasks.register<Exec>("installGitHooks") {
    group = "ios"
    description = "Point git at the tracked .githooks dir so the iOS Xcode projects " +
        "auto-regenerate on checkout/merge. Undo with " +
        "`git config --unset core.hooksPath`."
    commandLine("git", "config", "core.hooksPath", ".githooks")
    doLast { logger.lifecycle("Installed: core.hooksPath -> .githooks") }
}

// ---------------------------------------------------------------------------
// The project graph for `scripts/run-gate.sh --quick`: every project's directory, any source
// directory it compiles from outside it, the projects it depends on (any configuration — main,
// test and per-target alike), and the gate's test and compile tasks it has. The script reads it to find what a change can break.
//
// Written at configuration time, only when asked (`-Ptenshu.projectGraph=<file>`), because it is
// a read of the configured model and nothing else: `./gradlew help --no-configuration-cache
// -Ptenshu.projectGraph=build/project-graph.json`. ⚠ `--no-configuration-cache`, or a cache hit
// skips configuration and writes no file.
// ---------------------------------------------------------------------------
providers.gradleProperty("tenshu.projectGraph").orNull?.let { out ->
    val gateTasks = listOf(
        "desktopTest", "jvmTest", "test", "testAndroidHostTest", "jsTest",
        "compileTestKotlinJs", "compileTestKotlinIosSimulatorArm64",
        // Read by the iOS CI workflow to shard the simulator tests across runners.
        "linkDebugTestIosSimulatorArm64", "iosSimulatorArm64Test",
    )
    gradle.projectsEvaluated {
        val json = rootProject.allprojects.filter { it != rootProject }.sortedBy { it.path }.joinToString(",\n", "[\n", "\n]\n") { p ->
            val deps = p.configurations.flatMap { c ->
                c.dependencies.withType(org.gradle.api.artifacts.ProjectDependency::class.java).map { it.path }
            }.filter { it != p.path }.toSortedSet()
            val tasks = gateTasks.filter { it in p.tasks.names }
            val dir = p.projectDir.relativeTo(rootProject.projectDir).invariantSeparatorsPath
            // ⚠ Source dirs OUTSIDE the project's own directory (`kotlin.srcDir("../other/…")`): a
            // compile-time dependency with no project dependency behind it, so the graph above
            // would miss it. A change under one of these is a change to this project.
            val kotlin = p.extensions.findByType(org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension::class.java)
            val foreign = kotlin?.sourceSets.orEmpty().flatMap { it.kotlin.srcDirs + it.resources.srcDirs }
                .filter { !it.startsWith(p.projectDir) && it.startsWith(rootProject.projectDir) }
                .map { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }.toSortedSet()
            fun list(xs: Collection<String>) = xs.joinToString(",", "[", "]") { "\"$it\"" }
            """  {"path": "${p.path}", "dir": "$dir", "srcDirs": ${list(foreign)}, "deps": ${list(deps)}, "tasks": ${list(tasks)}}"""
        }
        rootProject.file(out).apply { parentFile.mkdirs() }.writeText(json)
    }
}
