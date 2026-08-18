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
# kotlinx.** rather than kotlinx.coroutines.**, to match the -dontwarn below it. The keep
# list being narrower than the warn suppression was an inconsistency, not a decision:
# MigrationTest parses the exported Room schema with kotlinx.serialization.json, and the
# app -- which serializes through generated serializers, never through JsonKt's top-level
# helpers -- gives R8 no reason to keep them.
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ---------------------------------------------------------------------------------------
# Found by the first run of this suite that reached the reporting stage. Every entry below
# was a class the TEST APK names and the APP APK no longer contains, and each one killed a
# whole test class before a single test in it ran -- 110 of 135 tests were never even
# enumerated, which reads as "25 tests" rather than as an error.
#
# JUnit resolves a test class's method SIGNATURES by reflection before running anything,
# so a missing parameter type fails the class, not the test. That is why the symptom is
# `initializationError` and never names the test that would have caught it.

# WHY THESE ARE PACKAGES AND NOT CLASSES. The first attempt named the three classes the
# run actually failed on -- Composer, FrameworkSQLiteOpenHelperFactory, NavHostController.
# The next run got further and died on four more: InfiniteAnimationPolicy,
# SupportSQLiteOpenHelper$Factory, NavArgumentBuilder, ToolProposal. That is not a list
# converging, it is a list being enumerated one round of eight minutes at a time, because
# the test harness reaches into these libraries in ways the app never does and nothing
# declares which parts up front.
#
# So the boundary is drawn at the library, not the class. Everything the Compose test rule,
# the Room migration harness and the navigation test controller resolve against is kept
# whole. That is a real widening of the gap between this APK and the shipped one, and it
# should be read together with the note above about the Kotlin runtime: what remains
# verified here is R8's treatment of LOCALMIND'S OWN classes, which is where a minification
# bug would actually reach a user. What is not verified is anything that depends on these
# libraries themselves being shrunk.
-keep class androidx.compose.** { *; }
-keep class androidx.sqlite.** { *; }
# androidx.room.util.KClassUtil, specifically: MigrationTestHelper reaches for it and the
# app's own generated Room code does not, so R8 deletes it. Missed in the first pass
# because sqlite and room look like one dependency from the outside and are two.
-keep class androidx.room.** { *; }
-keep class androidx.navigation.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-dontwarn androidx.compose.**

# TestNavHostController subclasses NavHostController, and the failure there is verification
# rather than class loading -- worth naming because it looks like a different problem:
#
#   java.lang.VerifyError: Superclass z3.z of androidx.navigation.testing.
#   TestNavHostController is declared final
#
# With no subclass inside the app, R8 marks the superclass final. Correct for the shipped
# APK; fatal for a test APK that extends it. The package keep above covers it.

# ToolProposal is Localmind's own, and unlike MemoryStore below it IS wired in --
# AppNavHost routes through NoToolProposalSource and ToolApprovalSheet takes one. What R8
# removed is the data class itself, because its constructor is `internal` and, as its own
# doc states, no production code can construct one. Previews and tests are the only
# callers by design. R8 is right; the tests still need the type to exist.
-keep class com.verbalogix.assistant.ui.tools.** { *; }

# THIS ONE IS NOT LIKE THE OTHERS, and it should not be quietly normalised by sitting in
# the same list. MemoryStore is Localmind's OWN code, and R8 deleted it because NOTHING IN
# THE APP CALLS IT: `data/memory/` (MemoryStore, Episode, Fact, DocPointer) is referenced
# from no other file in app/src/main. The 4 instrumented tests over it are the only callers
# that exist, so on the release variant they test code that does not ship.
#
# Keeping it here buys a green suite and buys nothing else. The real choice -- wire the
# memory store into the app, or delete it and its tests -- is a product decision, not a
# minification one, and it is deliberately left visible rather than resolved by a rule.
-keep class com.verbalogix.assistant.data.memory.** { *; }
