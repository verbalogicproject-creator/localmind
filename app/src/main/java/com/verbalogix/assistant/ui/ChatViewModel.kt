package com.verbalogix.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verbalogix.assistant.data.ChatMessage
import com.verbalogix.assistant.data.LlamaClient
import com.verbalogix.assistant.data.Message
import com.verbalogix.assistant.data.MessageDao
import com.verbalogix.assistant.data.Provider
import com.verbalogix.assistant.data.ProviderRepository
import com.verbalogix.assistant.data.ServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val dao: MessageDao,
    private val llama: LlamaClient,
    private val providers: ProviderRepository,
) : ViewModel() {

    /** Straight from Room, so a process death loses nothing. */
    val messages: StateFlow<List<Message>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val providerList: StateFlow<List<Provider>> = providers.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _provider = MutableStateFlow<Provider?>(null)
    val provider: StateFlow<Provider?> = _provider.asStateFlow()

    private val _status = MutableStateFlow(ServerStatus(reachable = false))
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /**
     * Seconds the current request has been outstanding, or null when idle.
     *
     * A swap endpoint starts a model process on first use, and that measures ~35s
     * before a single token is generated -- OpenCL kernel compilation plus weight
     * upload. A bare spinner held for 45 seconds is indistinguishable from a hung app,
     * and the user's correct response to a hung app is to kill it, which wastes the
     * load that was nearly finished. A ticking number is the difference between
     * "working" and "broken", and it costs one coroutine.
     */
    private val _elapsed = MutableStateFlow<Int?>(null)
    val elapsed: StateFlow<Int?> = _elapsed.asStateFlow()

    init {
        viewModelScope.launch {
            providers.ensureDefaults()
            _provider.value = providers.active()
            refreshStatus()
        }
    }

    /**
     * Switching endpoint deliberately does NOT clear the transcript. The conversation
     * belongs to the user, not to whichever model answered a given turn, and handing
     * one model's history to another is the point of having specialists: ask the
     * reasoning model to think, then switch and ask the coding model to implement it.
     */
    fun selectProvider(id: Long) {
        viewModelScope.launch {
            providers.select(id)
            _provider.value = providers.active()
            // Clear stale readings first. Carrying the previous endpoint's model name
            // and tok/s across a switch would show one server's numbers under
            // another's name until the probe returns.
            _status.value = ServerStatus(reachable = false)
            refreshStatus()
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val target = _provider.value ?: providers.active().also { _provider.value = it }
            _status.value = llama.status(target.baseUrl, target.model)
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _sending.value) return

        viewModelScope.launch {
            _sending.value = true
            // Read once, up front. If the user switches provider mid-generation the
            // reply still belongs to the endpoint that was asked, and the error
            // message on failure names the right one.
            val target = _provider.value ?: providers.active().also { _provider.value = it }
            // Persist the user's turn BEFORE the network call. If the process dies
            // mid-generation, the question survives and only the answer is lost --
            // which is recoverable by asking again, unlike losing what you typed.
            dao.insert(Message(role = "user", content = prompt, createdAt = now()))

            // Only real conversation turns go back to the server. The transcript also
            // holds "thinking" and "error" rows, which are display artefacts -- the
            // wire protocol knows "user" and "assistant" and nothing else, and sending
            // an invented role is a malformed request.
            //
            // Reasoning is excluded on purpose beyond that: replaying a model's own
            // chain of thought as conversation history compounds it turn over turn and
            // burns the context window on text the user never wrote.
            val history = dao.all()
                .filter { it.role == "user" || it.role == "assistant" }
                .map { ChatMessage(it.role, it.content) }
            val ticker = launch {
                var n = 0
                while (true) {
                    _elapsed.value = n
                    kotlinx.coroutines.delay(1_000)
                    n++
                }
            }
            runCatching { llama.complete(target.baseUrl, target.model, history) }
                .onSuccess { result ->
                    // Reasoning is kept, not discarded. This app's whole stance is
                    // that the machinery is the product -- on a device where you own
                    // the model, hiding how it reached an answer would be coy about
                    // the one thing you can actually inspect.
                    if (result.reasoning.isNotEmpty()) {
                        dao.insert(
                            Message(role = "thinking", content = result.reasoning, createdAt = now()),
                        )
                    }
                    dao.insert(Message(role = "assistant", content = result.answer, createdAt = now()))
                    _status.value = _status.value.copy(
                        reachable = true,
                        tokensPerSecond = result.tokensPerSecond ?: _status.value.tokensPerSecond,
                    )
                }
                .onFailure { e ->
                    // Surfaced in the transcript rather than a toast: an error you can
                    // scroll back to is diagnosable, one that vanishes is not.
                    dao.insert(
                        Message(
                            role = "error",
                            content = e.message ?: e::class.simpleName ?: "request failed",
                            createdAt = now(),
                        ),
                    )
                    _status.value = _status.value.copy(reachable = false)
                }
            ticker.cancel()
            _elapsed.value = null
            _sending.value = false
            // A swap endpoint has now loaded the model it was asked for, so the strip
            // should stop saying "idle". Cheap: /v1/models, no load triggered.
            if (target.isSwap) _status.value = llama.status(target.baseUrl, target.model)
                .copy(tokensPerSecond = _status.value.tokensPerSecond)
        }
    }

    fun clear() {
        viewModelScope.launch { dao.clear() }
    }

    private fun now() = System.currentTimeMillis()
}
