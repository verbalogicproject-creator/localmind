// An app that builds, signs, installs and runs -- and cannot be uploaded.
// targetSdk 34 has been below Play's requirement since 31 August 2026.
android {
    compileSdk = 34
    defaultConfig {
        minSdk = 28
        targetSdk = 34
    }
}
