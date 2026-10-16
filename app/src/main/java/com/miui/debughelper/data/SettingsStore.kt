package com.miui.debughelper.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.debugDataStore by preferencesDataStore(
    name = "debug_helper",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal data class SavedDebugState(
    val settings: DebugSettings = DebugSettings(),
    val autoOwned: Boolean = false,
    val suppressedConnection: String? = null,
    val lastAction: String? = null,
    val lastActionAt: Long? = null,
    val error: String? = null,
)

internal class SettingsStore(context: Context) {
    private val dataStore = context.applicationContext.debugDataStore
    val data = dataStore.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }.map { preferences ->
        SavedDebugState(
            settings = DebugSettings(
                autoConnect = preferences[AUTO] ?: false,
                trustedSsids = preferences[TRUSTED] ?: emptySet(),
            ),
            autoOwned = preferences[OWNED] ?: false,
            suppressedConnection = preferences[SUPPRESSED_CONNECTION],
            lastAction = preferences[LAST_ACTION],
            lastActionAt = preferences[LAST_ACTION_AT],
            error = preferences[ERROR],
        )
    }

    suspend fun setAutoConnect(enabled: Boolean) {
        dataStore.edit { it[AUTO] = enabled; it.remove(SUPPRESSED_CONNECTION) }
    }
    suspend fun trust(ssid: String) { dataStore.edit { it[TRUSTED] = (it[TRUSTED] ?: emptySet()) + ssid } }
    suspend fun remove(ssid: String) { dataStore.edit { it[TRUSTED] = (it[TRUSTED] ?: emptySet()) - ssid } }
    suspend fun setOwnership(owned: Boolean) {
        dataStore.edit { it[OWNED] = owned }
    }
    suspend fun setSuppressedConnection(token: String?) {
        dataStore.edit { if (token == null) it.remove(SUPPRESSED_CONNECTION) else it[SUPPRESSED_CONNECTION] = token }
    }
    suspend fun record(action: String? = null, error: String? = null) {
        dataStore.edit {
            if (action != null) {
                it[LAST_ACTION] = action
                it[LAST_ACTION_AT] = System.currentTimeMillis()
            }
            if (error == null) it.remove(ERROR) else it[ERROR] = error
        }
    }

    companion object {
        private val AUTO = booleanPreferencesKey("auto_connect")
        private val TRUSTED = stringSetPreferencesKey("trusted_ssids")
        // Separate from the previous legacy-TCP marker: a TLS switch never owns a legacy session.
        private val OWNED = booleanPreferencesKey("native_wireless_auto_owned")
        private val SUPPRESSED_CONNECTION = stringPreferencesKey("suppressed_connection")
        private val LAST_ACTION = stringPreferencesKey("last_action")
        private val LAST_ACTION_AT = longPreferencesKey("last_action_at")
        private val ERROR = stringPreferencesKey("error")
    }
}
