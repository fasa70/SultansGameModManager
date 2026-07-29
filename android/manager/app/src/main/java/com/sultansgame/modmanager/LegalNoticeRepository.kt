package com.sultansgame.modmanager

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val LEGAL_NOTICE_VERSION = 1
private val Context.legalNoticeDataStore by preferencesDataStore(name = "legal_notice")

class LegalNoticeRepository(private val context: Context) {
    private val acceptedVersion = intPreferencesKey("accepted_version")

    val isCurrentNoticeAccepted: Flow<Boolean> = context.legalNoticeDataStore.data.map { preferences ->
        preferences[acceptedVersion] == LEGAL_NOTICE_VERSION
    }

    suspend fun acceptCurrentNotice() {
        context.legalNoticeDataStore.edit { it[acceptedVersion] = LEGAL_NOTICE_VERSION }
    }

}
