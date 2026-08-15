# R8 rules for the ANDROID TEST APK only, applied via testProguardFiles.
#
# Needed because testBuildType = "release" makes AGP minify the androidTest APK as
# well as the app. The test libraries then drag in compile-time-only annotations that
# are not on the runtime classpath, and R8 treats a missing class as fatal:
#
#   Missing class com.google.errorprone.annotations.MustBeClosed
#   (referenced from: androidx.test.platform.tracing.Tracer$Span.beginChildSpan)
#
# These are test-APK concerns and are kept out of the app's proguard-rules.pro
# deliberately. Loosening the app's rules to fix a test-harness problem would weaken
# exactly the minification this suite exists to verify.

# Compile-time-only annotations referenced by androidx.test. They never exist at
# runtime, so a missing-class warning about them is noise, not a defect.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.jetbrains.annotations.**

# The instrumentation runner discovers test classes reflectively by name, so they
# must survive minification and keep their names.
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keep class org.hamcrest.** { *; }
-keep @org.junit.runner.RunWith class * { *; }
-keepclassmembers class * {
    @org.junit.Test <methods>;
    @org.junit.Before <methods>;
    @org.junit.After <methods>;
    @org.junit.Rule <fields>;
}

# Room's MigrationTestHelper is a JUnit rule that loads schemas reflectively.
-keep class androidx.room.testing.** { *; }
