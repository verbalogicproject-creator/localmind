# Keep rules that apply ONLY when running the instrumented suite against the minified
# build (`-PinstrumentRelease`). Never applied to a shipped release.
#
# WHY THIS FILE IS SEPARATE FROM proguard-rules.pro. The point of running the suite on
# the release variant is to verify what R8 actually did to the shipping APK. Adding a
# keep rule to the app's own rules to satisfy the test harness would change the artifact
# under test into one nobody ships -- the suite would then be verifying a build that
# exists only to be verified. So the shipped rules stay exactly as they are, and the
# difference is confined here, opt-in, and small enough to state in one line.
#
# WHAT GOES WRONG WITHOUT IT, because the symptom does not name the cause:
#
#   java.lang.NoClassDefFoundError: Failed resolution of: Landroidx/tracing/Trace;
#     at androidx.test.runner.AndroidJUnitRunner.onCreate(...)
#
# AndroidJUnitRunner runs INSIDE the app's process, so it resolves against the app APK's
# classes, not the test APK's. R8 is right to delete androidx.tracing.Trace -- the app
# never calls it -- but the runner does, in onCreate, before it reports anything. The
# process dies before printing "Starting N tests", which upstream looks like an
# instrumentation that produced no output and never finished: a HANG, not a crash. That
# is what the note above `testBuildType` in app/build.gradle.kts recorded, and why it
# read as unfixable.
#
# EVERY ENTRY IS A WAY THE TESTED APK DIFFERS FROM THE SHIPPED ONE, and the list did not
# stay as small as the first version of this comment hoped. After androidx.tracing.Trace
# the runner died on kotlin.LazyKt, and that is not a coincidence: the test APK does not
# bundle its own copy of the Kotlin runtime, it resolves against the app's. So every
# stdlib member the harness touches but the app never calls has to be kept.
#
# WHAT THAT COSTS, stated rather than buried. Keeping the Kotlin runtime whole means this
# APK is NOT the shipped APK, and the difference is not a rounding error. What survives
# the compromise is still worth having: R8's treatment of the app's OWN classes -- the
# serializers, the Harness decoders, the Compose surfaces -- is unchanged by these rules,
# and that is where a minification bug would actually bite. What is no longer covered is
# anything that depends on the stdlib itself being shrunk.
#
# Read a green run here as "R8 did not break Localmind's code", not as "the shipped APK
# is verified". The launch smoke and the mapping-file assertions remain the checks that
# speak about the artifact users install.
-keep class androidx.tracing.Trace { *; }
-dontwarn androidx.tracing.**

-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**
