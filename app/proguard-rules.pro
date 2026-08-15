# R8 runs on every release build (isMinifyEnabled = true).
#
# This file must EXIST even when empty: `proguardFiles` naming a file that was never
# created fails the build, and that exact omission cost a full CI round trip once.

# Keep source file and line numbers so a crash from a shipped build can be retraced
# against the published mapping.txt. Without these, a release stack trace has no line
# information and mapping.txt cannot recover it.
-keepattributes SourceFile,LineNumberTable
# Then hide the original file name, which would otherwise leak through.
-renamesourcefileattribute SourceFile

# --- kotlinx.serialization -------------------------------------------------------
#
# S6 RECONCILIATION, 2026-08-15.
# Authority: github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
#
# The library SHIPS THESE RULES ITSELF as consumer rules. An Android app applying the
# kotlin-serialization plugin gets them automatically and must not restate them.
#
# What was here before was hand-written from memory. It was valid, it compiled, and
# it passed a test that used a simple @Serializable data class -- while omitting
# -keepattributes RuntimeVisibleAnnotations,AnnotationDefault, which polymorphic
# serialization needs at runtime, and the descriptor-field optimization guard that
# works around a ProGuard bug producing bytecode which fails verification
# (Kotlin/kotlinx.serialization#2719).
#
# That is a failure class of its own, and it is worse than a fabricated file. A
# fabricated certificate is INVALID and fails loudly. Config invented from memory is
# VALID BUT INCOMPLETE: it works for the case you tested and fails on the one you did
# not. Wrong code will not compile; wrong config ships.
#
# It also made the conformance suite's negative test meaningless: deleting these
# rules broke nothing, because the library's own rules were still in force, so a
# passing test proved nothing about R8 at all.
#
# The upstream rules deliberately do NOT cover @Serializable classes with NAMED
# companion objects. If this app adds one, the delta goes below naming the class --
# never a blanket re-copy of upstream, which would rot the moment upstream changes.
#
# LOCAL DELTA: none. The only @Serializable type here (ItemDto) has a default
# companion and is fully covered by the library's own rules.

# --- Ktor client -----------------------------------------------------------------
#
# S6 RECONCILIATION, 2026-08-15.
# Authority: https://youtrack.jetbrains.com/issue/KTOR-5528
#
# R8 fails outright with:
#   Missing class org.slf4j.impl.StaticLoggerBinder
#   (referenced from: void org.slf4j.LoggerFactory.bind() and 3 other contexts)
#
# Ktor depends on the slf4j API, which looks up a binding implementation by name at
# runtime. On Android no binding is present -- and none is wanted, since logging goes
# through Logcat -- but R8 treats the missing class as fatal rather than as the
# optional dependency it is.
#
# Unlike kotlinx.serialization, Ktor does NOT ship consumer rules covering this, so
# this rule is genuinely required rather than a redundant restatement. That
# distinction is the reason S6 exists: check whether the library already supplies
# what you are about to write, and record which case you found.
-dontwarn org.slf4j.**
