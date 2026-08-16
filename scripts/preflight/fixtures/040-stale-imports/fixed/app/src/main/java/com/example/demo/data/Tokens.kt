package com.example.demo.data

// Top-level declarations that are ordinary Kotlin, importable, and invisible to a
// pattern that only knows `class|interface|object|enum class|typealias|fun <name>`.
//
// The extension function is the subtler of the two: it is declared as
// `fun Modifier.minimumTouchTarget`, so a pattern anchored at `fun +<name>` never
// matches it however correct the code is.
const val TAG_EVIDENCE_CLOSE = "evidence-close"

val DEFAULT_LABEL = "label"

fun String.minimumTouchTarget(): String = this
