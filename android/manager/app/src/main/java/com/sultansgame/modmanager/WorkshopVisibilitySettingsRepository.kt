package com.sultansgame.modmanager

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.workshopVisibilitySettingsDataStore by preferencesDataStore(name = "workshop_visibility_settings")

class WorkshopVisibilitySettingsRepository(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.workshopVisibilitySettingsDataStore,
) {
    private val showWorkshop = booleanPreferencesKey("show_workshop")

    val isWorkshopEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[showWorkshop] ?: false
    }

    suspend fun setWorkshopEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[showWorkshop] = enabled }
    }
}
