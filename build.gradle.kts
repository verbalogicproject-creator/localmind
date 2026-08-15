// Deliberately thin. Every plugin is declared here with `apply false` so the version
// catalog stays the single source of truth, and applied in the module that needs it.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
