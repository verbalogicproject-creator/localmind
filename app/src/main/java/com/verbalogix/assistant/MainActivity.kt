package com.verbalogix.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verbalogix.assistant.ui.ChatScreen
import com.verbalogix.assistant.ui.ChatViewModel
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * @AndroidEntryPoint is the half of the Hilt contract that fails LOUDLY. The other
 * half is @HiltAndroidApp on the Application class AND android:name in the manifest
 * pointing at it — miss either and this throws at onCreate:
 *
 *     IllegalStateException: Hilt Activity must be attached to an @HiltAndroidApp Application
 *
 * That compiles perfectly and no test can see it. Preflight check 060 exists solely
 * for this, because nine consecutive green builds once shipped it.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LocalmindTheme {
                val vm: ChatViewModel = hiltViewModel()
                val messages by vm.messages.collectAsStateWithLifecycle()
                val status by vm.status.collectAsStateWithLifecycle()
                val sending by vm.sending.collectAsStateWithLifecycle()
                val providers by vm.providerList.collectAsStateWithLifecycle()
                val provider by vm.provider.collectAsStateWithLifecycle()
                val elapsed by vm.elapsed.collectAsStateWithLifecycle()
                val think by vm.think.collectAsStateWithLifecycle()

                ChatScreen(
                    messages = messages,
                    status = status,
                    sending = sending,
                    onSend = vm::send,
                    onRetryStatus = vm::refreshStatus,
                    buildLabel = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                    providers = providers,
                    provider = provider,
                    onSelectProvider = vm::selectProvider,
                    elapsed = elapsed,
                    think = think,
                    onToggleThink = vm::toggleThink,
                )
            }
        }
    }
}
