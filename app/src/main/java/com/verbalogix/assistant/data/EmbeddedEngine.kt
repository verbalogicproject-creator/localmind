package com.verbalogix.assistant.data

import android.content.Context
import android.os.Build
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * llama.cpp, in this process.
 *
 * The app's other two providers speak HTTP to a server someone else started. This one
 * owns the model itself, which changes three things that the seam has to absorb rather
 * than hide:
 *
 * 1. IT MAY NOT EXIST. Upstream's JNI calls __android_log_is_loggable, introduced in
 *    Android 30, so on API 28 and 29 the library cannot even load. The app keeps
 *    minSdk 28 and remains a working client there; this reports itself unavailable
 *    rather than crashing. Touching AiChat at all on those versions throws, because
 *    obtaining the engine is what calls System.loadLibrary -- so the version check
 *    guards construction, not just use.
 *
 * 2. IT IS STATEFUL. `sendUserPrompt` takes ONE message and keeps the conversation in
 *    a KV cache, where an HTTP provider receives the whole transcript every turn. So
 *    this sends the latest user turn only. The consequence is real and not papered
 *    over: switching to this provider part-way through a conversation gives a model
 *    that has not seen any of it. Binding a model per conversation is the fix, and it
 *    is a schema change rather than something to bodge here.
 *
 * 3. IT NEEDS A FILE. Several hundred megabytes of GGUF that no APK can carry. Until
 *    a model is downloaded, this is honestly unavailable and says which directory it
 *    is looking in.
 */
@Singleton
class EmbeddedEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * False below API 30. Read this BEFORE touching anything else here: obtaining the
     * engine calls System.loadLibrary, which is exactly what fails on 28 and 29.
     */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= MIN_API

    /** Where a downloaded model goes. App-private, so it needs no storage permission. */
    val modelDir: File get() = File(context.filesDir, MODEL_DIR)

    /**
     * The model to use, or null if none is installed.
     *
     * Deliberately "the first .gguf by name" rather than a configured filename: the
     * download step does not exist yet, and a hardcoded name would be a second place
     * to keep in step with it. When a model picker arrives this becomes a real
     * selection and the ambiguity goes away.
     */
    fun installedModel(): File? = modelDir
        .listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
        ?.minByOrNull { it.name }

    // The engine is a process-wide singleton with a single-threaded native dispatcher
    // behind it. The mutex is not about that -- it is about load-then-use being two
    // calls, which two concurrent sends would interleave.
    private val lock = Mutex()
    private var engine: InferenceEngine? = null
    private var loadedFrom: String? = null

    /**
     * Report what this provider can currently do, in the same shape the HTTP providers
     * use, so the status strip needs no new concepts.
     *
     * `reachable` means "could answer a question now". Unavailability carries a reason
     * in `error`, because "unreachable" with no explanation is the failure this app
     * spent an evening removing from its first-run state.
     */
    suspend fun status(): ServerStatus {
        if (!supported) {
            return ServerStatus(
                reachable = false,
                error = "on-device inference needs Android 11 or newer (this is ${Build.VERSION.SDK_INT})",
            )
        }
        val model = installedModel()
            ?: return ServerStatus(
                reachable = false,
                error = "no model installed — put a .gguf in ${modelDir.absolutePath}",
            )
        val loaded = lock.withLock { engine?.state?.value?.isModelLoaded == true }
        return ServerStatus(
            reachable = true,
            model = model.name.removeSuffix(".gguf"),
            modelLoaded = loaded,
        )
    }

    /**
     * Answer one turn.
     *
     * Signature deliberately mirrors LlamaClient.complete so ChatViewModel branches on
     * the provider and nothing else. `think` is accepted and IGNORED: upstream's
     * bindings expose no thinking toggle, and silently accepting a parameter that does
     * nothing is better than a signature that forces the caller to know which provider
     * it is talking to -- provided it is said out loud, which is what this comment is.
     */
    suspend fun complete(
        history: List<ChatMessage>,
        @Suppress("UNUSED_PARAMETER") think: Boolean,
    ): LlamaClient.Completion {
        check(supported) { "on-device inference needs Android 11 or newer" }
        val model = installedModel() ?: error("no model installed in ${modelDir.absolutePath}")

        val prompt = history.lastOrNull { it.role == "user" }?.content
            ?: error("nothing to answer: no user turn in the transcript")

        return lock.withLock {
            val engine = ensureLoaded(model)
            val startedAt = System.currentTimeMillis()
            val tokens = engine.sendUserPrompt(prompt).toList()
            val elapsedMs = System.currentTimeMillis() - startedAt

            LlamaClient.Completion(
                answer = tokens.joinToString("").trim(),
                // Upstream streams plain tokens with no reasoning channel, so there is
                // nothing to separate. Empty is the truth, not a placeholder.
                reasoning = "",
                truncated = false,
                tokensPerSecond = if (elapsedMs > 0 && tokens.isNotEmpty()) {
                    tokens.size * 1000.0 / elapsedMs
                } else {
                    null
                },
            )
        }
    }

    /** Loads the model if it is not already the one resident. Call under [lock]. */
    private suspend fun ensureLoaded(model: File): InferenceEngine {
        val current = engine ?: AiChat.getInferenceEngine(context).also { engine = it }
        if (loadedFrom != model.absolutePath || !current.state.value.isModelLoaded) {
            // Loading is minutes of work on a phone and holds gigabytes of RAM. Doing
            // it once and remembering which file it was is the whole reason this class
            // holds state at all.
            current.loadModel(model.absolutePath)
            loadedFrom = model.absolutePath
        }
        return current
    }

    /** Free the weights. The app is not the only thing that wants this phone's RAM. */
    suspend fun unload() {
        if (!supported) return
        lock.withLock {
            engine?.cleanUp()
            loadedFrom = null
        }
    }

    companion object {
        /** __android_log_is_loggable, called by upstream's JNI, arrived in Android 11. */
        const val MIN_API = 30
        private const val MODEL_DIR = "models"
    }
}
