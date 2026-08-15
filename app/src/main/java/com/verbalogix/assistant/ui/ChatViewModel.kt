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

            val history = dao.all().map { ChatMessage(it.role, it.content) }
            runCatching { llama.complete(history) }
                .onSuccess { (reply, tokensPerSecond) ->
                    dao.insert(Message(role = "assistant", content = reply, createdAt = now()))
                    _status.value = _status.value.copy(
                        reachable = true,
                        tokensPerSecond = tokensPerSecond ?: _status.value.tokensPerSecond,
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
