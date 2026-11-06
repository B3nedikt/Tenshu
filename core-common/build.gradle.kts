import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.core.common"
        compileSdk = 37
        minSdk = 24

        withHostTest {
            // BinderTaskTransportRebindTest builds the real transport against a fake Context; the
            // generated AIDL stub and `Intent.setPackage` are android.jar methods the host has no
            // body for. Default values instead of "not mocked" lets it drive the real
            // ServiceConnection callbacks.
            isReturnDefaultValues = true
        }
    }

    jvm("desktop")

    js {
        browser()
        // ES-module output. Required so we can import sync-named ESM npm libs
        // (@noble/hashes, etc.) without UMD's `@JsModule + @JsNonModule` dance,
        // and matches the convention adopted in :tenshu-sdk-js.
        useEsModules()
    }

    // commonTest sources import TestProxy from :core-test-utils, which lives in
    // that module's nonJsMain (it bridges runBlocking, not portable to JS).
    // Disable JS test compilation here until Phase T sets up real JS test
    // infrastructure (Vitest-equivalent or Karma+Mocha; see
    // docs/web/roadmap.md). Main JS compilation (the SDK surface) is unaffected.
    tasks.matching { it.name == "compileTestKotlinJs" }.configureEach { enabled = false }

    // iOS targets stay declared so commonMain remains KMP-honest (catches
    // accidental JVM-only deps), but we don't produce a `.framework` here:
    // there is no iOS app consuming this module yet, and every module
    // declaring its own framework all named `shared` was pure build-time
    // cost. When an iOS app appears, expose a proper `XCFramework` on the
    // umbrella module(s) that actually ship.
    //
    // Each iOS target binds the crypto backends (Ed25519/X25519/
    // XChaCha20-Poly1305/Argon2id — CryptoBackendsModule.ios.kt) to
    // libsodium through a cinterop. The static lib + headers are produced
    // per-target by `scripts/build-libsodium-apple.sh` (pinned + checksummed
    // source, built fresh into the module build dir — no binaries in git),
    // and the corresponding `cinteropLibsodium<Target>` task depends on that
    // build. See docs/proposals/ios-proxy-architecture.md Phase 6a.
    val libsodiumScript = rootProject.file("scripts/build-libsodium-apple.sh")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        val targetName = iosTarget.targetName
        val capitalised = targetName.replaceFirstChar { it.uppercase() }
        val libsodiumDir = layout.buildDirectory.dir("libsodium/$targetName")

        val buildLibsodium = tasks.register<Exec>("buildLibsodium$capitalised") {
            description = "Cross-compiles a static libsodium for $targetName."
            // Apple toolchain only (xcrun/clang). On a non-Mac CI host the iOS cinterop/compile
            // tasks are *disabled* by KGP, but a disabled task's dependencies still execute — so
            // without this guard `./gradlew check` on the Linux `test` job runs this Exec and fails
            // with exit 127 (the repo-wide CI red). Skipping it on non-Mac mirrors what KGP already
            // does to the cinterop; the real iOS build runs on the macOS `ios.yml` lane.
            onlyIf { HostManager.hostIsMac }
            val outDir = libsodiumDir.get().asFile
            inputs.file(libsodiumScript)
            outputs.dir(outDir)
            commandLine(libsodiumScript.absolutePath, targetName, outDir.absolutePath)
        }

        iosTarget.compilations.getByName("main").cinterops.create("libsodium") {
            defFile(project.file("src/nativeInterop/cinterop/libsodium.def"))
            val outDir = libsodiumDir.get().asFile
            includeDirs(outDir.resolve("include"))
            // Locate libsodium.a (named in the .def's `staticLibraries`) in
            // the per-target build output.
            extraOpts("-libraryPath", outDir.resolve("lib").absolutePath)
        }

        tasks.named("cinteropLibsodium$capitalised") {
            dependsOn(buildLibsodium)
        }
    }

    // The iOS shared store's SQLite (docs/proposals/ios-shared-store-sqlite.md §2.1.2, D12): the
    // vendored amalgamation in src/nativeInterop/sqlite/, compiled per target with Xcode's clang,
    // and a cinterop over the same vendored header. No `androidx.sqlite` artifact is used on iOS.
    //
    // ⚠ Two targets, not three: no `iosX64`. The driver lives in `iosSqliteMain` (below), which
    // only these two depend on, so `iosX64` compiles without the store (D1, option (b)).
    //
    // ⚠⚠ This static library is the only SQLite an iOS binary may link. A module that also pulled
    // in `androidx.sqlite:sqlite-bundled` for iOS would carry a second copy of every `sqlite3_*`
    // symbol.
    val sqliteScript = rootProject.file("scripts/build-sqlite-apple.sh")
    val sqliteSourceDir = project.file("src/nativeInterop/sqlite")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        val targetName = iosTarget.targetName
        val capitalised = targetName.replaceFirstChar { it.uppercase() }
        val sqliteDir = layout.buildDirectory.dir("sqlite/$targetName")

        val buildSqlite = tasks.register<Exec>("buildSqlite$capitalised") {
            description = "Compiles the vendored SQLite amalgamation for $targetName."
            // Same guard and same reason as `buildLibsodium…` above.
            onlyIf { HostManager.hostIsMac }
            val outDir = sqliteDir.get().asFile
            inputs.file(sqliteScript)
            inputs.dir(sqliteSourceDir)
            outputs.dir(outDir)
            commandLine(sqliteScript.absolutePath, targetName, outDir.absolutePath)
        }

        iosTarget.compilations.getByName("main").cinterops.create("tenshusqlite") {
            defFile(project.file("src/nativeInterop/cinterop/tenshusqlite.def"))
            includeDirs(sqliteSourceDir)
            extraOpts("-libraryPath", sqliteDir.get().asFile.resolve("lib").absolutePath)
        }

        tasks.named("cinteropTenshusqlite$capitalised") {
            dependsOn(buildSqlite)
            // ⚠⚠ The static library is embedded in the cinterop klib, and the cinterop task knows
            // only the .def and the header: without this input a rebuilt library (another compile
            // option, another SQLite version) left the task UP-TO-DATE and every binary went on
            // linking the old one. Seen 2026-10-06: SQLITE_DEFAULT_AUTOVACUUM was added, the
            // library rebuilt, and the tests still ran against the build without it.
            inputs.dir(sqliteDir.map { it.dir("lib") }).withPropertyName("tenshusqliteStaticLibrary")
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(project(":core"))

                api(libs.koinCore)
                api(libs.koinMultiplatform)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.io)
                implementation(libs.kotlinx.datetime)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
            }
        }
        // Shared JVM source set spanning `androidMain` + `desktopMain`. The
        // default hierarchy template doesn't materialise this parent on its
        // own when the Android KMP plugin is in play, so we create it
        // manually and re-parent both JVM children. Houses code identical
        // on both targets — `BouncyCastleEd25519Backend` and
        // `SqliteObjectStore`. The BouncyCastle + androidx.sqlite-bundled
        // dependencies are declared once here and inherited by both
        // children.
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.bouncycastle)
                // SqliteObjectStore (durable blob-KV ObjectStore). Bundled
                // driver ships its own SQLite, so it works identically on
                // Android + desktop JVM (and inside desktopTest). JS keeps
                // IndexedDbObjectStore; iOS/common keep InMemoryObjectStore.
                implementation(libs.androidx.sqlite.bundled)
                // SqliteObjectStore's trace beside the store file (`objects.db.log`): the
                // writer's retry/drop/contention lines persist there because a proxy
                // daemon's stdout goes nowhere. Pure, no deps.
                implementation(project(":diagnostics"))
            }
        }
        getByName("androidHostTest") {
            dependencies {
                // AssetLinksTest parses web/apex/public/.well-known/assetlinks.json as JSON.
                implementation(libs.kotlinx.serialization.json)
            }
        }
        getByName("androidMain") {
            dependsOn(jvmMain)
            dependencies {
                api(project(":core-aidl"))
                implementation(libs.core.ktx)
            }
        }
        getByName("desktopMain") {
            dependsOn(jvmMain)
            dependencies {
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.savedstate)
                // NativeBridgeServer's JSON-over-UDS envelope (desktop-only;
                // docs/proposals/desktop-macos-integration.md §2.2). Runtime
                // only — we build/parse via the JsonElement API, so no
                // serialization compiler plugin is needed module-wide.
                implementation(libs.kotlinx.serialization.json)
            }
        }
        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // SchemaLazyFieldsCompletenessTest enumerates declared
                // member properties on each *Schema interface via JVM
                // reflection — KMP common code has no equivalent, so the
                // test ships as desktop-only.
                implementation(libs.kotlin.reflect)
            }
        }
        getByName("jsMain") {
            dependencies {
                // SHA-256 and PBKDF2-HMAC-SHA512 sync impls. WebCrypto's digest is async,
                // and our common API is sync; @noble/hashes is small (~5KB compressed
                // tree-shaken), vetted, and matches the sync contract.
                implementation(npm("@noble/hashes", "1.7.1"))
                // Ed25519 + X25519 backend on JS. Same author as
                // @noble/hashes; ~30KB minified for the curves bundle when
                // tree-shaken to ed25519+x25519. Sync API.
                implementation(npm("@noble/curves", "1.8.1"))
                // XChaCha20-Poly1305 AEAD on JS. Same author as
                // @noble/curves + @noble/hashes; ~10KB minified. Sync API.
                implementation(npm("@noble/ciphers", "1.2.1"))
            }
        }

        val iosMain = getByName("iosMain")

        // The iOS shared store's source set (docs/proposals/ios-shared-store-sqlite.md §2.1, D1
        // option (b)): between `iosMain` and the two targets that have the SQLite cinterop above.
        // `iosX64` does not depend on it, so it compiles without the store (and never runs: its
        // tests are disabled in the root build).
        val iosSqliteMain = create("iosSqliteMain") { dependsOn(iosMain) }
        getByName("iosArm64Main").dependsOn(iosSqliteMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosSqliteMain)
        val iosSqliteTest = create("iosSqliteTest") { dependsOn(getByName("iosTest")) }
        getByName("iosArm64Test").dependsOn(iosSqliteTest)
        getByName("iosSimulatorArm64Test").dependsOn(iosSqliteTest)
    }
}
