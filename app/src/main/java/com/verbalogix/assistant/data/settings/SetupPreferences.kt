package com.verbalogix.assistant.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the user has been past the setup surface once.
 *
 * SHAREDPREFERENCES, NOT ROOM, AND THAT IS THE WHOLE POINT OF THIS FILE.
 *
 * Adding one boolean to the database would mean a fifth schema version, a migration,
 * and a committed schema JSON -- and the persisted schema is migration-sensitive: it
 * can only change forwards, by moving data that already sits on devices, so a wrong
 * migration destroys a user's conversation rather than merely failing. Paying that
 * risk to record "this person has seen a screen" is a bad trade, and `ChatViewModel`
 * already declines the same trade for the `think` toggle for the same reason.
 *
 * What belongs here is exactly what this holds: disposable UI state that can be lost
 * without consequence. If this file were deleted from a device the user would see the
 * setup screen once more, and nothing else would happen. Anything for which that is
 * not true does not belong in SharedPreferences.
 */
@Singleton
class SetupPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * Observed rather than read once, so that completing setup moves the shell forward
     * without the caller having to also remember to re-read. The listener is
     * unregistered when the collector goes away -- `SharedPreferences` holds its
     * listeners weakly, which is a documented way to have them collected early, so the
     * strong reference is kept here for as long as the flow is alive.
     */
    fun setupCompleted(): Flow<Boolean> = callbackFlow {
        trySend(prefs.getBoolean(KEY_SETUP_COMPLETED, false))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SETUP_COMPLETED || key == null) {
                trySend(prefs.getBoolean(KEY_SETUP_COMPLETED, false))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Synchronous read, for deciding the start destination before the first frame. */
    fun isSetupCompleted(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETED, false)

    /**
     * Recorded when the user chooses to continue, whichever way they continue.
     *
     * "Continue with direct chat" and "everything is ready" both complete setup,
     * because the flag records that the user has made a choice -- not that the system
     * reached some state. Gating it on readiness would trap someone with no Foundry on
     * the setup screen forever, and direct chat is the proven path that must keep
     * working with no Foundry present.
     */
    fun markSetupCompleted() {
        // androidx.core's `edit {}` rather than edit().putBoolean().apply(). It applies
        // by default and cannot leak an un-committed editor, which is the failure the
        // manual form invites -- a forgotten apply() writes nothing and reports no
        // error at all.
        prefs.edit { putBoolean(KEY_SETUP_COMPLETED, true) }
    }

    private companion object {
        const val NAME = "localmind.setup"
        const val KEY_SETUP_COMPLETED = "setup_completed"
    }
}
