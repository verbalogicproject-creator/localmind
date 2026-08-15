package com.verbalogix.assistant

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the llama.cpp engine actually load into this process?
 *
 * Nothing cheaper can answer that. The libraries are downloaded, verified and packaged
 * by the build, and every one of those steps can succeed while the result refuses to
 * load on a real Android: a wrong ABI, a missing transitive .so, an unresolved symbol,
 * or a linker that finds none of it because the packaging path was subtly wrong. All of
 * those are runtime failures with a build that says SUCCESS.
 *
 * This is why the native build produces x86_64 as well as arm64-v8a. The emulator rung
 * is x86_64 only; an arm64-only engine would leave this test unable to load anything,
 * and its absence would look exactly like coverage.
 */
@RunWith(AndroidJUnit4::class)
class NativeEngineTest {

    private val nativeLibDir: File
        get() = File(
            InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationInfo.nativeLibraryDir,
        )

    /**
     * The engine is PACKAGED. Separate from whether it loads, because the two fail for
     * unrelated reasons and a single test would report the wrong one.
     */
    @Test
    fun theEngineIsPackagedIntoTheApk() {
        val names = nativeLibDir.listFiles()?.map { it.name }.orEmpty()
        assertTrue(
            "no .so files in $nativeLibDir -- the fetch or the packaging did not reach the APK",
            names.any { it.endsWith(".so") },
        )
        assertTrue(
            "libai-chat.so missing; found $names",
            names.contains("libai-chat.so"),
        )
        assertTrue(
            "libllama.so missing; libai-chat.so cannot resolve its dependencies. found $names",
            names.contains("libllama.so"),
        )
        // GGML_CPU_ALL_VARIANTS emits one backend per ISA, dlopen'd at runtime from
        // this directory. Shipping libai-chat.so without them yields a process that
        // loads and then has no CPU backend to run on.
        assertTrue(
            "no libggml-cpu-* backends packaged; found $names",
            names.any { it.startsWith("libggml-cpu-") },
        )
    }

    /**
     * The engine LOADS, which is the question the build cannot answer.
     *
     * Skipped below API 30 on purpose, and the skip is itself the assertion: upstream's
     * JNI layer calls __android_log_is_loggable, introduced in Android 30, so the app
     * keeps minSdk 28 and gates the FEATURE instead. If this ever stops skipping on the
     * API 28 emulator, the gate has been removed and Android 9 users get a crash.
     */
    @Test
    fun theEngineLoadsOnApi30AndAbove() {
        assumeTrue(
            "embedded mode requires API 30; this device is ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= 30,
        )
        // Throws UnsatisfiedLinkError with the linker's own message on failure, which
        // names the missing symbol or library -- more useful than anything asserted here.
        System.loadLibrary("ai-chat")
    }
}
