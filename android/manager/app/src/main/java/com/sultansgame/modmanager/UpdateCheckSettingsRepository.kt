package com.sultansgame.modmanager

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.updateCheckSettingsDataStore by preferencesDataStore(name = "update_check_settings")

class UpdateCheckSettingsRepository(private val context: Context) {
    private val autoCheckEnabled = booleanPreferencesKey("auto_check_enabled")

    val isAutoCheckEnabled: Flow<Boolean> = context.updateCheckSettingsDataStore.data.map { preferences ->
        preferences[autoCheckEnabled] ?: true
    }

    suspend fun setAutoCheckEnabled(enabled: Boolean) {
        context.updateCheckSettingsDataStore.edit { it[autoCheckEnabled] = enabled }
    }

    suspend fun reset() {
        context.updateCheckSettingsDataStore.edit { it.clear() }
    }
}
