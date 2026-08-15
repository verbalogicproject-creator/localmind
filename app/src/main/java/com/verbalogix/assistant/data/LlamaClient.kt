package com.verbalogix.assistant.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Talks to llama-server over loopback.
 *
 * The server runs in Termux, in a different UID, but Android shares one network
 * namespace across apps -- so 127.0.0.1 reaches it. That is the same mechanism a
 * predecessor project used between a Termux engine and its companion APK.
 *
 * Cleartext to 127.0.0.1 must be whitelisted in network_security_config.xml.
 * Android blocks cleartext by default from API 28, which is this app's minSdk, and
 * the failure is a connection error rather than anything naming the policy.
 *
 * The endpoint is OpenAI-compatible, which is what llama-server exposes.
 */
@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    @SerialName("n_predict") val nPredict: Int = 512,
)

@Serializable
private data class Choice(val message: ChatMessage)

@Serializable
private data class Timings(
    @SerialName("predicted_per_second") val predictedPerSecond: Double? = null,
    @SerialName("predicted_n") val predictedN: Int? = null,
)

@Serializable
private data class ChatResponse(
    val choices: List<Choice> = emptyList(),
    val model: String? = null,
    val timings: Timings? = null,
)

@Serializable
private data class Props(
    @SerialName("default_generation_settings") val settings: GenerationSettings? = null,
    val model_path: String? = null,
)

@Serializable
private data class GenerationSettings(@SerialName("n_ctx") val nCtx: Int? = null)

/** What the status strip shows. Null fields mean "not known yet", never zero. */
data class ServerStatus(
    val reachable: Boolean,
    val model: String? = null,
    val contextSize: Int? = null,
    val tokensPerSecond: Double? = null,
    val error: String? = null,
)

@Singleton
class LlamaClient @Inject constructor() {

    var baseUrl: String = DEFAULT_BASE_URL

    private val client = HttpClient(io.ktor.client.engine.cio.CIO) {
        install(ContentNegotiation) {
            // The server sends fields this app does not model, and adds more between
            // releases. Ignoring unknown keys is what keeps a llama.cpp update from
            // breaking the app.
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            // Generation on a 1.2B at ~22 tok/s can legitimately take a while; a
            // default 15s timeout would abort correct answers mid-sentence.
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 3_000
        }
    }

    /** Cheap reachability probe. Never throws: absence is a state, not an error. */
    suspend fun status(): ServerStatus = runCatching {
        val props: Props = client.get("$baseUrl/props").body()
        ServerStatus(
            reachable = true,
            model = props.model_path?.substringAfterLast('/')?.removeSuffix(".gguf"),
            contextSize = props.settings?.nCtx,
        )
    }.getOrElse { e ->
        ServerStatus(
            reachable = false,
            error = when (e) {
                is java.net.ConnectException -> "no server on $baseUrl"
                else -> e.message ?: e::class.simpleName ?: "unreachable"
            },
        )
    }

    /** Send the conversation, get one reply. Throws on failure so the caller decides. */
    suspend fun complete(history: List<ChatMessage>): Pair<String, Double?> {
        val response: ChatResponse = client.post("$baseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(messages = history))
        }.body()
        val text = response.choices.firstOrNull()?.message?.content
            ?: error("server returned no choices")
        return text.trim() to response.timings?.predictedPerSecond
    }

    companion object {
        /** Matches ~/llama.cpp/lfm2.5-1.2b.sh, which serves on 127.0.0.1:8080. */
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"
    }
}
