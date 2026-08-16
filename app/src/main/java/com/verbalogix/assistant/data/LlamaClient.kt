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
    // Null for a direct llama-server, which serves exactly one model and needs no
    // naming. Set for a swap proxy, where this field IS the routing decision: it
    // selects which llama-server to start, and to stop. Omitted from the JSON when
    // null (explicitNulls = false) rather than sent as `"model": null`, which some
    // OpenAI-compatible servers reject outright.
    val model: String? = null,
    val stream: Boolean = false,
    /**
     * Passed through to the chat template. `enable_thinking = false` is what actually
     * silences a reasoning model, measured against Qwen3.5-4B:
     *
     *   baseline                  reasoning 1192 chars -> 70 char answer
     *   enable_thinking = false   reasoning    0 chars -> 32 char answer
     *   reasoning_budget = 0      reasoning  680 chars -> still deliberating
     *
     * `reasoning_budget` only trims; the template kwarg switches it off. Templates
     * that do not define the variable ignore it, so sending it is safe for models
     * that never reason.
     *
     * AUTHORITY: this is Qwen's documented hard switch, not an inference. A maintainer
     * gives exactly this form for API deployments --
     * https://huggingface.co/Qwen/Qwen3.5-9B/discussions/13 and
     * https://qwen.readthedocs.io/en/latest/getting_started/quickstart.html
     *
     * DO NOT reach for the documented SOFT switch (`/think`, `/no_think` appended to
     * the prompt). It is real for Qwen3 and a trap for Qwen3.5-4B, measured here:
     *
     *   plain "hi"          reasoning 1199 chars -> 35 char answer
     *   "hi /no_think"      reasoning 2409 chars -> ZERO answer
     *   hard switch         reasoning    0 chars -> 32 char answer
     *
     * The soft switch DOUBLED the deliberation and returned no answer at all: the
     * template does not honour the token, so the model reads it as part of the
     * question and thinks about that instead. It would surface to the user as a
     * failed request.
     *
     * Worth keeping because it is the project thesis in miniature. The documentation
     * established which mechanism is canonical; only running it established that the
     * other documented mechanism is broken for this model. Neither method would have
     * found both.
     */
    @SerialName("chat_template_kwargs")
    val chatTemplateKwargs: Map<String, Boolean>? = null,
    // 512 was not enough. LFM2.5-8B-A1B is a REASONING model: it writes its thinking
    // into a separate reasoning_content field and only then answers. Measured against
    // the real server, it burned all 400 tokens of an earlier budget on reasoning
    // alone and returned finish_reason=length with content EMPTY.
    //
    // A reasoning model needs room for both halves. 2048 is not generosity, it is the
    // minimum that lets one finish a thought and then speak.
    @SerialName("n_predict") val nPredict: Int = 2048,
)

/**
 * `reasoning_content` carries a reasoning model's thinking, separately from the
 * answer. Models that do not reason simply omit it.
 *
 * Modelling it is not optional: reading only `content` renders an EMPTY message for
 * every reasoning model, which looks like a broken app rather than a truncated reply.
 */
@Serializable
private data class ResponseMessage(
    val role: String = "assistant",
    val content: String = "",
    @SerialName("reasoning_content") val reasoningContent: String = "",
)

@Serializable
private data class Choice(
    val message: ResponseMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

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
    // Additive Harness fields. A direct llama.cpp server omits both, which is why the
    // same parser serves both transports -- see contracts/harness-v0.md.
    val citations: List<Citation> = emptyList(),
    val receipt: Receipt? = null,
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
    /**
     * Whether the selected model is currently resident. Only a swap proxy can answer
     * this; null means "not applicable", which is the honest value for a direct
     * server whose model is loaded for as long as the process exists.
     */
    val modelLoaded: Boolean? = null,
    /**
     * The server's own label for the selected model, already sanitised, or null.
     *
     * EPHEMERAL. It lives in this status object for as long as the status does and is
     * never written to Room. A rename in llama-swap's config therefore shows up without
     * an app change and without editing the user's provider row -- see [ModelDisplayName].
     */
    val displayName: String? = null,
)

/**
 * One entry of a swap proxy's /v1/models.
 *
 * MODELS ONLY WHAT IT USES. The live server also sends `object`, `created`, `owned_by`,
 * `description` and `meta`; none is declared here, and `ignoreUnknownKeys = true` drops
 * them. `description` is deliberately absent rather than read-and-ignored: it carries
 * strings like "MEASURED AT 2.8 tok/s" and "~24 tok/s", which is CONFIGURATION TEXT
 * someone typed into a YAML file, not a measurement this app or that server took. A
 * throughput figure on screen must come from an observed inference, so the safest place
 * for that field is unreachable from the type system.
 */
@Serializable
internal data class SwapModel(
    val id: String = "",
    val status: SwapStatus? = null,
    /** The server's display label. Untrusted, and sanitised before it reaches a screen. */
    val name: String? = null,
)

@Serializable
internal data class SwapStatus(val value: String = "")

@Serializable
internal data class SwapModels(val data: List<SwapModel> = emptyList())

/**
 * LENIENT ON PURPOSE, AND THE OPPOSITE OF THE HARNESS DECODER.
 *
 * This app now parses two families of JSON under deliberately contradictory settings, and
 * the contradiction is the design rather than an inconsistency to reconcile:
 *
 *   llama.cpp / llama-swap  ignoreUnknownKeys = TRUE
 *     A third-party server on its own release cadence. The live `/v1/models` already
 *     sends five fields this app does not model, and upstream adds more between
 *     versions. Refusing them would mean a llama.cpp update breaks chat -- an
 *     availability failure caused by strictness that protects nothing, since none of
 *     these fields carries authority.
 *
 *   Knowledge Foundry       ignoreUnknownKeys = FALSE   (see HarnessDecoder.STRICT)
 *     A closed contract where every schema is additionalProperties: false, and where the
 *     payloads carry trust states and capability grants. An unrecognised field there
 *     means the document was written to an agreement this build does not share, and
 *     reading the rest would produce a confident screen from a half-understood reply.
 *
 * Leniency is a per-contract judgement about what a surprise MEANS, not a house style.
 * Applying either setting to the other surface would be a real defect.
 */
internal val LENIENT_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

@Singleton
class LlamaClient @Inject constructor() {

    // No mutable baseUrl field here on purpose. This is a @Singleton, so a field would
    // be shared state that a provider switch mutates while a request is in flight --
    // the reply would arrive attributed to whichever endpoint happened to be selected
    // when it landed, not the one that answered. The endpoint is a property of the
    // call, so it travels as an argument.
    private val client = HttpClient(io.ktor.client.engine.cio.CIO) {
        install(ContentNegotiation) { json(LENIENT_JSON) }
        install(HttpTimeout) {
            // Generation on a 1.2B at ~22 tok/s can legitimately take a while; a
            // default 15s timeout would abort correct answers mid-sentence.
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 3_000
        }
    }

    /**
     * Cheap reachability probe. Never throws: absence is a state, not an error.
     *
     * THE BRANCH HERE IS LOAD-BEARING, and the wrong path costs the user 41 seconds.
     *
     * A swap proxy does not serve /props at all -- it returns 404, because it proxies
     * the OpenAI-compatible surface and not llama.cpp-native paths. It DOES serve
     * /upstream/<model>/props, and that is the trap: measured, requesting it against
     * an unloaded model STARTED that model, evicted the other one, took six seconds
     * and returned an empty body. A status probe must never do that. Opening the app
     * would trigger a full model load.
     *
     * /v1/models is the safe answer: it reports every model and whether each is
     * resident, costs under a millisecond, and loads nothing.
     */
    suspend fun status(baseUrl: String, model: String = ""): ServerStatus =
        if (model.isEmpty()) statusDirect(baseUrl) else statusSwap(baseUrl, model)

    private suspend fun statusSwap(baseUrl: String, model: String): ServerStatus =
        runCatching {
            val models: SwapModels = client.get("$baseUrl/v1/models").body()
            val entry = models.data.firstOrNull { it.id == model }
                ?: return ServerStatus(
                    reachable = false,
                    error = "proxy is up but does not know the model \"$model\"",
                )
            // REACHABLE IS ABOUT THE PROXY, NOT THE WEIGHTS.
            //
            // llama-swap answers /v1/models instantly and loads a model on demand when a
            // completion arrives, so every model reads "unloaded" until something asks
            // for one -- which is the ordinary resting state, observed on the live
            // server with all three models unloaded. Reporting that as unreachable would
            // be false, and worse, it would gate the very request that loads the model:
            // the user would be told to fix a server that is working and waiting.
            //
            // `modelLoaded = false` is therefore residency, and nothing else reads it as
            // a reason to refuse a send.
            ServerStatus(
                reachable = true,
                model = model,
                modelLoaded = entry.status?.value == "loaded",
                displayName = ModelDisplayName.sanitize(entry.name),
            )
        }.getOrElse { e -> unreachable(baseUrl, e) }

    private suspend fun statusDirect(baseUrl: String): ServerStatus = runCatching {
        val props: Props = client.get("$baseUrl/props").body()
        ServerStatus(
            reachable = true,
            model = props.model_path?.substringAfterLast('/')?.removeSuffix(".gguf"),
            contextSize = props.settings?.nCtx,
        )
    }.getOrElse { e -> unreachable(baseUrl, e) }

    private fun unreachable(baseUrl: String, e: Throwable): ServerStatus =
        ServerStatus(
            reachable = false,
            error = when (e) {
                is java.net.ConnectException -> "no server on $baseUrl"
                else -> e.message ?: e::class.simpleName ?: "unreachable"
            },
        )

    /** What came back: the answer, the thinking that preceded it, and how fast. */
    data class Completion(
        val answer: String,
        val reasoning: String,
        val truncated: Boolean,
        val tokensPerSecond: Double?,
        val citations: List<Citation> = emptyList(),
        /** Null from a direct server: no retrieval ran, so there is nothing to report. */
        val receipt: Receipt? = null,
    )

    /** Send the conversation, get one reply. Throws on failure so the caller decides. */
    suspend fun complete(
        baseUrl: String,
        model: String,
        history: List<ChatMessage>,
        think: Boolean,
    ): Completion {
        val response: ChatResponse = client.post("$baseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(
                ChatRequest(
                    messages = history,
                    model = model.ifEmpty { null },
                    // Only sent when switching thinking OFF. Omitted otherwise so a
                    // model with no such template variable sees the request it
                    // would have seen before this feature existed.
                    chatTemplateKwargs = if (think) null else mapOf("enable_thinking" to false),
                ),
            )
        }.body()
        val choice = response.choices.firstOrNull() ?: error("server returned no choices")
        val answer = choice.message.content.trim()
        val reasoning = choice.message.reasoningContent.trim()
        val truncated = choice.finishReason == "length"

        // A reasoning model that ran out of budget mid-thought returns an empty
        // answer. Rendering that as a blank message is the worst option: it looks
        // like the app failed. Say what actually happened instead.
        if (answer.isEmpty() && reasoning.isNotEmpty()) {
            error(
                if (truncated) {
                    "the model was still thinking when it hit the token limit -- " +
                        "raise n_predict, or ask something narrower"
                } else {
                    "the model returned reasoning but no answer"
                },
            )
        }
        if (answer.isEmpty()) error("the model returned an empty answer")

        return Completion(
            answer = answer,
            reasoning = reasoning,
            truncated = truncated,
            tokensPerSecond = response.timings?.predictedPerSecond,
            citations = response.citations,
            receipt = response.receipt,
        )
    }

}
