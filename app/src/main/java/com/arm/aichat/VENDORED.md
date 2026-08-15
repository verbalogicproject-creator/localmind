# Vendored from llama.cpp — do not edit

Every `.kt` file in this directory and below is copied **unmodified** from
`ggml-org/llama.cpp`, MIT licensed (see `LICENSE`), at the commit pinned in
`native/llama.cpp.pin`:

```
examples/llama.android/lib/src/main/java/com/arm/aichat/**
    ->  app/src/main/java/com/arm/aichat/**

tag     b10435
commit  9e40df63ba151d771d8b247ac4011cf203337e99
```

## Why the package name is `com.arm.aichat` and cannot be ours

JNI resolves native methods by **symbol name**, and the symbol names are baked into
`libai-chat.so` by the C++ this Kotlin is the counterpart to:

```
Java_com_arm_aichat_internal_InferenceEngineImpl_load
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken
...
```

Moving `InferenceEngineImpl` into `com.verbalogix.assistant` renames every one of
those symbols and the library stops resolving — as an `UnsatisfiedLinkError` at
runtime, not a compile error. So the package is a contract with the binary, not a
naming choice.

## Why unmodified, and what that costs

This project has repeatedly paid for editing what it should have adopted: a version
lattice derived rather than taken, a CMake flag set assembled rather than copied. Both
were plausible, correctly shaped, and wrong. Vendored code that is byte-identical to
upstream can be re-copied when the pin moves; vendored code with local edits has to be
re-merged, and the edits are silently lost by anyone who re-copies without knowing.

The cost is real and worth stating: this is 1,287 lines of code this project does not
own, whose style does not match the rest of the app, and which cannot be fixed in place
when something in it is wrong. **The rule for a needed change is: raise it upstream, or
wrap it from `com.verbalogix.assistant`.** Never edit in place.

## Re-vendoring

When `native/llama.cpp.pin` moves, re-copy this tree from the same commit and re-run
the instrumented tests. The JNI signatures and the Kotlin must come from the *same*
commit — a mismatch between them is exactly the `UnsatisfiedLinkError` above, and
nothing in the build can detect it.

## What is deliberately not vendored

Upstream's `app/` module — its demo UI — and its `build.gradle.kts`, which declares
`minSdk 33` and a dependency on `androidx.datastore`. This app keeps `minSdk 28`, gates
the embedded provider at runtime on API 30, and needs no datastore: the vendored Kotlin
imports only `android.*`, `kotlinx.coroutines`, `java.io` and one `dalvik` annotation.
