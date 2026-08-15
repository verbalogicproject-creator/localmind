package com.verbalogix.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verbalogix.assistant.data.ChatMessage
import com.verbalogix.assistant.data.LlamaClient
import com.verbalogix.assistant.data.Message
import com.verbalogix.assistant.data.MessageDao
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
) : ViewModel() {

    /** Straight from Room, so a process death loses nothing. */
    val messages: StateFlow<List<Message>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _status = MutableStateFlow(ServerStatus(reachable = false))
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch { _status.value = llama.status() }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _sending.value) return

        viewModelScope.launch {
            _sending.value = true
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
            runCatching { llama.complete(history) }
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
            _sending.value = false
        }
    }

    fun clear() {
        viewModelScope.launch { dao.clear() }
    }

    private fun now() = System.currentTimeMillis()
}
