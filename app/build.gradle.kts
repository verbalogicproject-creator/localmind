import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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

android {
    namespace = "com.verbalogix.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.verbalogix.assistant"
        // Android 9. Adaptive icons need 26, so mipmap-anydpi-v26 covers every
        // supported device with no PNG fallbacks required.
        minSdk = 28
        targetSdk = 34

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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
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
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

// Room's exported schema JSON is what makes a migration test possible at all --
// MigrationTestHelper reads it. Without it the migration is untestable, and the
// persisted schema version is the third of the three irreversible decisions.
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

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.serialization.json)

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
