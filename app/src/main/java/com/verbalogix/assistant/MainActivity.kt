package com.verbalogix.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.verbalogix.assistant.ui.nav.AppNavHost
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
 *
 * THE ACTIVITY NO LONGER KNOWS WHAT A CHAT IS. It used to build `ChatViewModel` and
 * hand eight flows to `ChatScreen` directly, which made the activity the de-facto
 * router for an app with exactly one screen. Adding a second screen there would have
 * meant a boolean, and a third a `when` -- so the graph moved into [AppNavHost] and the
 * activity kept the two jobs that are genuinely its own: install the theme, and host
 * the shell.
 *
 * Everything below the theme is now restored by the navigation library on process
 * recreation, rather than rebuilt from whatever the activity happened to hold.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LocalmindTheme {
                AppNavHost()
            }
        }
    }
}
