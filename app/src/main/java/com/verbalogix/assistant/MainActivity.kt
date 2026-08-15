package com.verbalogix.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.hilt.navigation.compose.hiltViewModel
import com.verbalogix.assistant.ui.HomeScreen
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
                HomeScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    gitSha = BuildConfig.GIT_SHA,
                )
            }
        }
    }
}
