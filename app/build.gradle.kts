// IMPORTED, NOT FULLY QUALIFIED, and that is not a style preference.
//
// Inside a Gradle Kotlin DSL script `java` resolves to the JavaPluginExtension
// accessor, so `java.net.URI` is parsed as a property access on that extension and
// fails with "Unresolved reference 'net'" -- an error that names the wrong thing
// entirely and sends you looking for a missing dependency. Cost one CI round trip.
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    // NO alias(libs.plugins.kotlin.android) -- AGP 9 provides Kotlin support
    // itself and applying the plugin separately is an error. The other Kotlin
    // plugins below are unaffected and stay versioned with Kotlin.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Signing credentials come from a git-ignored keystore.properties locally, or from
// environment variables in CI (release.yml decodes SIGNING_KEY_BASE64 to a file and
// exports SIGNING_KEYSTORE_PATH).
//
// When neither is present the release variant is left UNSIGNED on purpose.
//
// An earlier version of this file fell back to the debug signing config so a fresh
// clone would still produce an installable APK. That was a trap: CI has no signing
// secrets, so every "release" APK it published was signed with the runner's
// auto-generated debug keystore -- a DIFFERENT key each run. Two consecutive CI
// releases were not upgrade-compatible with each other, and nothing said so.
//
// Unsigned fails loudly at install time. Silently-differently-signed does not, and
// the signing certificate is one of exactly three things about an Android app that
// can never be changed after the first user installs it.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "SIGNING_KEYSTORE_PATH")
val hasReleaseSigning = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists()

// ── Prebuilt llama.cpp ──────────────────────────────────────────────────────────
//
// The native engine is FETCHED AND VERIFIED, not built here and not committed here.
//
// Built here: measured at 229s per ABI, and Gradle builds ABIs one after another, so
// roughly 7.5 minutes added to a 3-minute inner loop on every push -- to rebuild
// something that changes only when native/llama.cpp.pin changes. native.yml builds it
// once per pin instead.
//
// Committed here: 19 MB per ABI is tempting, but it is 19 MB PER PIN BUMP, forever, in
// a repo that is otherwise a few megabytes, and every CI clone pays for the whole
// history. What is committed is the DIGEST -- a few lines of text -- which is the same
// trade as pinning a certificate rather than storing a key.
//
// The verification is not ceremony. These libraries are downloaded over the network
// and loaded as native code into the app's process; an unverified download is arbitrary
// code execution with extra steps. Failing closed on a digest mismatch is the only
// acceptable behaviour, and it is why this task refuses rather than warns.
val llamaTag: String = rootProject.file("native/llama.cpp.pin").readLines()
    .firstOrNull { it.trimStart().startsWith("tag") }
    ?.substringAfter("=")?.trim()
    ?: error("native/llama.cpp.pin declares no tag")

abstract class FetchNativeLibs : DefaultTask() {
    @get:Input abstract val tag: Property<String>
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val repo: Property<String>
    @get:InputFile abstract val sums: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val expected = sums.get().asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) parts[1].trim().removePrefix("./") to parts[0].lowercase() else null
            }.toMap()
        if (expected.isEmpty()) {
            error("${sums.get().asFile} lists no digests -- refusing to download anything unverified")
        }

        val dest = outputDir.get().asFile
        dest.deleteRecursively()
        dest.mkdirs()

        for (abi in abis.get()) {
            val name = "$abi.zip"
            val want = expected[name]
                ?: error("native/SHA256SUMS.txt has no entry for $name -- refusing to trust an unlisted download")
            val url = "https://github.com/${repo.get()}/releases/download/native-${tag.get()}/$name"
            logger.lifecycle("fetching $url")

            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            val got = MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) }
            if (got != want) {
                error(
                    "SHA-256 mismatch for $name\n" +
                        "  expected $want\n" +
                        "  actual   $got\n" +
                        "These libraries are loaded as native code into the app process. Refusing.",
                )
            }

            ZipInputStream(bytes.inputStream()).use { zip ->
                var next = zip.nextEntry
                while (next != null) {
                    // Bound to a val each iteration. `next` is reassigned inside this
                    // lambda, so Kotlin will not smart-cast it to non-null however many
                    // times it has been compared against null.
                    val entry = next
                    // A zip entry name is attacker-controlled input in the general case,
                    // so containment is checked before anything is written. The digest
                    // check above is not a substitute: it proves the archive is the one
                    // we published, which says nothing about where its paths point.
                    //
                    // Path.startsWith, not String.startsWith -- the string form accepts
                    // /dest-evil as being inside /dest, because it compares characters
                    // rather than path components.
                    val out = dest.resolve(entry.name).normalize()
                    require(out.toPath().startsWith(dest.toPath().normalize())) {
                        "zip entry escapes the destination: ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    next = zip.nextEntry
                }
            }
        }

        val found = dest.walkTopDown().filter { it.name.endsWith(".so") }.count()
        if (found == 0) error("The downloaded archives contained no .so files.")
        logger.lifecycle("native libraries ready: $found files under $dest")
    }
}

val nativeLibsDir: Provider<Directory> = layout.buildDirectory.dir("nativeLibs")

val fetchNativeLibs = tasks.register<FetchNativeLibs>("fetchNativeLibs") {
    description = "Downloads and verifies the prebuilt llama.cpp libraries for the pinned commit."
    group = "build setup"
    tag.set(llamaTag)
    abis.set(listOf("arm64-v8a", "x86_64"))
    repo.set("verbalogicproject-creator/localmind")
    sums.set(rootProject.file("native/SHA256SUMS.txt"))
    outputDir.set(nativeLibsDir)
}

android {
    namespace = "com.verbalogix.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.verbalogix.assistant"
        // Android 9. Adaptive icons need 26, so mipmap-anydpi-v26 covers every
        // supported device with no PNG fallbacks required.
        minSdk = 28
        targetSdk = 36

        // release.yml derives these from the git tag and passes them as
        // ORG_GRADLE_PROJECT_versionCode / _versionName. The literals are the
        // local-development fallback only, so every published build carries its
        // tag's version and can be traced back to it.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.0.1-dev"

        // The APK states which commit produced it. Without this, "is the app on my
        // phone the one I just built?" is unanswerable, and a stale APK looks
        // exactly like a fresh one -- a mistake already made once here, caught only
        // because two artifacts had byte-identical sizes.
        buildConfigField(
            "String",
            "GIT_SHA",
            "\"${System.getenv("GITHUB_SHA")?.take(7) ?: "local"}\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // NOT testBuildType = "release", deliberately, and this is worth reading before
    // anyone sets it.
    //
    // Instrumented tests run against `debug`, where R8 does not run. That means an
    // instrumented test asserting anything about minification proves nothing --
    // verified on conformance/red-runtime, where the suite reported 5/5 with the
    // serialization keep rules deleted.
    //
    // The obvious fix, testBuildType = "release", was tried and abandoned. R8 then
    // minifies the androidTest APK too (fixable, see proguard-test-rules.pro), the
    // build completes and both APKs package -- and then connectedReleaseAndroidTest
    // HANGS with no output, no "Starting N tests", until the 45-minute job timeout.
    // Run 31875004036 on both API 28 and 34.
    //
    // R8 correctness is covered two other ways instead, and the coverage is stated
    // honestly rather than assumed:
    //   structure  -- a CI step asserts against the published mapping.txt that
    //                 classes which must survive were not removed or renamed
    //   behaviour  -- the app exercises the serialization path during startup, so
    //                 R8 breaking it becomes a launch crash, which the release
    //                 launch smoke already catches with crash.txt naming the cause
    //
    // NOT covered: arbitrary behavioural testing of the release variant. Saying so
    // is the point; a documented gap is safer than a rung that reports success.

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "SIGNING_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // So a debug build and a release build can coexist on one device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            // Kotlin DSL name. The Groovy form is `shrinkResources`, and using it
            // here compiles as an unresolved reference that preflight cannot see.
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Applies to the androidTest APK only. With testBuildType = "release",
            // AGP minifies the test APK too, and androidx.test references
            // compile-time-only annotations that R8 treats as fatal missing classes.
            // Kept separate on purpose: relaxing the app's own rules to fix a test
            // harness problem would weaken the very minification this suite verifies.
            testProguardFiles("proguard-test-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }

        // THE NATIVE LIBRARIES MUST BE EXTRACTED TO DISK. Not a size preference --
        // llama.cpp cannot find its CPU backends otherwise.
        //
        // AGP defaults useLegacyPackaging to false when minSdk >= 23: the .so files are
        // stored uncompressed inside the APK and mapped directly, never unpacked. That
        // is normally better -- no duplicate copy on disk, faster installs -- and
        // System.loadLibrary works either way.
        //
        // It breaks this app specifically. GGML_BACKEND_DL builds each CPU variant as
        // its own .so, and upstream's JNI locates them by SCANNING A DIRECTORY:
        //
        //     Java_..._InferenceEngineImpl_init(JNIEnv*, jobject, jstring nativeLibDir)
        //         ggml_backend_load_all_from_path(path_to_backend);
        //
        // With libraries unextracted, applicationInfo.nativeLibraryDir exists and is
        // EMPTY. The scan finds nothing, no CPU backend registers, and the engine has
        // nothing to run on -- after loadLibrary succeeded, which is what makes it hard
        // to diagnose. The instrumented test caught it on its first run by asserting on
        // that directory's contents rather than on the build's output.
        //
        // Cost, accepted: the .so are compressed in the APK (a smaller download) and
        // unpacked at install time (roughly 19 MB of duplicate on-device storage, and a
        // slower first install).
        jniLibs { useLegacyPackaging = true }
    }

    // ci.yml and release.yml both run `./gradlew ... lint`, and until now nothing
    // configured it. AGP 9 ships new checks and a fresh NewApi baseline against
    // API 36, so an unconfigured lint can fail the build on a rule nobody adopted --
    // and the reflex when that happens under deadline is to switch lint off entirely.
    //
    // checkDependencies is deliberately false: this is a single-module app, and
    // scanning dependencies turns a 20-second task into a multi-minute one for
    // findings we cannot act on.
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
    }

    // MigrationTestHelper reads the exported schemas at RUNTIME, from the test APK's
    // assets, so they have to be on the androidTest asset path.
    //
    // This alone is NOT enough, and the gap is easy to miss: the schema files must
    // also be COMMITTED. A clean CI checkout has no app/schemas/, and the androidTest
    // asset merge does not wait for KSP to generate them, so the test APK ships
    // without the schema and fails on device with "Cannot find the schema file in the
    // assets folder". It looks like a test bug and is a missing-file bug.
    //
    // Preflight check 9 enforces that every declared @Database version has a
    // committed schema.
    //
    // srcDir (singular, ADDS) not srcDirs (plural, REPLACES): the earlier form
    // silently dropped the default src/androidTest/assets/ from the source set,
    // so any test fixture put there was excluded from the test APK. Discovered
    // when MemoryStoreTest's memory-fixture.db was not found at runtime --
    // FileNotFoundException at nativeOpenAsset, and no build-time warning
    // because the sourceSet's own contract permits replacing defaults.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
        // The fetched engine is NOT wired here. AGP 9 refuses a Provider in the
        // SourceSet API -- see the androidComponents block below.
    }
}

// Replaces `android { kotlinOptions { jvmTarget = "17" } }`, which AGP 9 removed.
// This is the KGP-native form and it is an ASSIGNMENT, not .set() -- the shape used
// by android/nowinandroid's KotlinAndroid.kt, which builds against this AGP.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Room's exported schema JSON is what makes a migration test possible at all --
// MigrationTestHelper reads it. Without it the migration is untestable, and a
// persisted schema can only be changed forwards, by migrating data already on
// devices -- so a wrong migration destroys it rather than merely failing.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// The fetched engine enters the build HERE, through the Variant API, not through
// android.sourceSets.
//
// AGP 9 refuses a Provider in the SourceSet API outright:
//
//     You cannot add Provider instances to the Android SourceSet API.
//     It is not possible for Android Studio to determine if the Provider points
//     to a directory that contains generated (read-only) or static (read-write) files.
//
// Which is the right complaint. These files ARE generated -- a task downloads them --
// and saying so through addGeneratedSourceDirectory carries the task dependency
// automatically, for every variant.
//
// That replaced a hand-rolled `tasks.matching { merge*JniLibFolders }.dependsOn(...)`
// plus a preBuild hook. Both worked by naming internal tasks, which is exactly the
// kind of wiring that breaks silently on an AGP bump -- and a missing .so does not
// fail the build, it fails at runtime as UnsatisfiedLinkError on a device. The escape
// hatch AGP offers (android.sourceset.disallowProvider=false) restores the old
// behaviour AND warns that task dependencies are not carried, which would reintroduce
// precisely that failure.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(fetchNativeLibs) { it.outputDir }
    }
}
