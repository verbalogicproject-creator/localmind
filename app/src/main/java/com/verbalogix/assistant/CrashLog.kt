package com.verbalogix.assistant

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to a file the developer can actually read.
 *
 * logcat is a dead end on the target device: Termux has no READ_LOGS, so reading
 * another app's buffer returns *silence* -- not an error, not a permission denial,
 * just an empty result and exit 0. That is the most expensive kind of negative
 * result, because it looks like "no crash happened".
 *
 * Install from [android.app.Application.attachBaseContext], NOT onCreate: Hilt
 * builds its component inside super.onCreate(), so a handler installed there would
 * miss exactly the graph-construction failures most worth catching.
 */
object CrashLog {

    private const val FILE_NAME = "crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(context, thread, error) }
            // Always delegate. Swallowing this would leave the process alive in a
            // broken state instead of dying honestly.
            previous?.uncaughtException(thread, error)
        }
    }

    /** The crash file's location. Reachable from a shell by full path. */
    fun path(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    /**
     * Builds the provenance header.
     *
     * Pure and separate from file IO so it is unit-testable on the JVM. The exact
     * `key=value` shape is a CONTRACT, not a formatting preference: the pipeline's
     * device tooling parses `gitSha=` out of this and refuses to diagnose a crash
     * whose SHA does not match HEAD. Reordering or renaming these keys silently
     * breaks that check, so a test pins them.
     */
    fun header(
        versionName: String,
        versionCode: Int,
        gitSha: String,
        timestamp: String,
        device: String,
        threadName: String,
    ): String = buildString {
        appendLine("versionName=$versionName")
        appendLine("versionCode=$versionCode")
        appendLine("gitSha=$gitSha")
        appendLine("time=$timestamp")
        appendLine("device=$device")
        appendLine("thread=$threadName")
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val target = path(context) ?: return
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        target.writeText(
            header(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                gitSha = BuildConfig.GIT_SHA,
                timestamp = stamp,
                device = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})",
                threadName = thread.name,
            ) + "\n" + stack.toString(),
        )
    }
}
