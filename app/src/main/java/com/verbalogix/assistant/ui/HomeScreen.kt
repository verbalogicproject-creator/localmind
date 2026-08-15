package com.verbalogix.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.ui.theme.LocalmindTheme

/**
 * The local-first assistant's entire UI: build provenance, on screen.
 *
 * This is not a placeholder to delete later. "Is the APK on my phone the one I just
 * built?" is otherwise unanswerable, and a stale artifact looks exactly like a fresh
 * one — a mistake this pipeline's ancestor made, caught only because two uploads had
 * byte-identical sizes. With this, a screenshot is evidence.
 *
 * Every value is a PARAMETER. No literal from a design mockup ever lives inside a
 * composable: fabricated sample numbers shipping as though they were real is its own
 * failure class, so mockup values belong in @Preview providers and nowhere else.
 */
@Composable
fun HomeScreen(
    versionName: String,
    versionCode: Int,
    gitSha: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("LOCALMIND", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "local-first assistant",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("BUILD", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    KeyValue("version", versionName)
                    KeyValue("code", versionCode.toString())
                    KeyValue("commit", gitSha)
                    // Reaching this screen at all proves Hilt built its graph: the
                    // host Activity is @AndroidEntryPoint, so a missing
                    // @HiltAndroidApp would have thrown before any of this composed.
                    KeyValue("hilt", "graph built")
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

// Sample values live here and nowhere else.
@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    LocalmindTheme(darkTheme = true) {
        HomeScreen(versionName = "0.0.1", versionCode = 1, gitSha = "abc1234")
    }
}
