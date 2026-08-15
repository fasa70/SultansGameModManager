package com.sultansgame.modmanager.platform.storage

import android.content.Context
import com.sultansgame.modmanager.model.CachedMod
import com.sultansgame.modmanager.model.GameModSyncItem
import com.sultansgame.modmanager.model.GameModSyncOperationType
import com.sultansgame.modmanager.model.PendingGameModSyncOperation

class DeploymentPlanStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val legacyPreferences = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    fun entries(cachedMods: List<CachedMod>): List<GameModSyncItem> {
        migrateLegacyPlanIfNeeded(cachedMods)
        val states = syncedStates()
        return cachedMods.sortedBy(CachedMod::displayName).map { cached ->
            GameModSyncItem(
                cacheKey = cached.cacheKey,
                contentDigestSha256 = cached.contentDigestSha256,
                displayName = cached.displayName,
                syncedToGame = states[cached.cacheKey] ?: true,
            )
        }
    }

    fun ensureSynced(cachedMods: List<CachedMod>) {
        migrateLegacyPlanIfNeeded(cachedMods)
        val states = syncedStates().toMutableMap()
        cachedMods.forEach { cached -> states.putIfAbsent(cached.cacheKey, true) }
        saveSyncedStates(states)
    }

    fun setSyncedToGame(cacheKey: String, syncedToGame: Boolean, cachedMods: List<CachedMod>) {
        migrateLegacyPlanIfNeeded(cachedMods)
        val states = syncedStates().toMutableMap()
        states[cacheKey] = syncedToGame
        saveSyncedStates(states)
        enqueue(PendingGameModSyncOperation(
            cacheKey,
            if (syncedToGame) GameModSyncOperationType.Sync else GameModSyncOperationType.Remove,
        ))
    }

    fun remove(cacheKey: String, cachedMods: List<CachedMod>) {
        migrateLegacyPlanIfNeeded(cachedMods)
        val states = syncedStates().toMutableMap()
        states.remove(cacheKey)
        saveSyncedStates(states)
        enqueue(PendingGameModSyncOperation(cacheKey, GameModSyncOperationType.Remove))
    }

    fun reset() {
        preferences.edit().clear().commit()
        legacyPreferences.edit().clear().commit()
    }

    fun pendingOperations(): List<PendingGameModSyncOperation> = preferences
        .getString(KEY_PENDING, "")
        .orEmpty()
        .lineSequence()
        .mapNotNull(::decodePending)
        .toList()

    fun complete(operation: PendingGameModSyncOperation) {
        val remaining = pendingOperations().filterNot { it.cacheKey == operation.cacheKey && it.type == operation.type }
        savePending(remaining)
    }

    private fun enqueue(operation: PendingGameModSyncOperation) {
        val operations = pendingOperations().filterNot { it.cacheKey == operation.cacheKey } + operation
        savePending(operations)
    }

    private fun migrateLegacyPlanIfNeeded(cachedMods: List<CachedMod>) {
        if (preferences.getBoolean(KEY_MIGRATED, false)) return
        val legacy = legacyPreferences.getString(LEGACY_KEY_ENTRIES, null)
        val states = linkedMapOf<String, Boolean>()
        if (legacy != null) {
            val cachedKeys = cachedMods.mapTo(mutableSetOf(), CachedMod::cacheKey)
            legacy.lineSequence().mapNotNull(::decodeLegacy).forEach { entry ->
                if (entry.cacheKey in cachedKeys) states[entry.cacheKey] = entry.enabled
            }
            cachedMods.forEach { cached -> states.putIfAbsent(cached.cacheKey, false) }
        } else {
            cachedMods.forEach { cached -> states[cached.cacheKey] = true }
        }
        preferences.edit()
            .putBoolean(KEY_MIGRATED, true)
            .putString(KEY_SYNCED, encodeSynced(states))
            .apply()
    }

    private fun syncedStates(): Map<String, Boolean> = preferences
        .getString(KEY_SYNCED, "")
        .orEmpty()
        .lineSequence()
        .mapNotNull(::decodeSynced)
        .toMap()

    private fun saveSyncedStates(states: Map<String, Boolean>) {
        preferences.edit().putString(KEY_SYNCED, encodeSynced(states)).apply()
    }

    private fun encodeSynced(states: Map<String, Boolean>): String = states
        .toSortedMap()
        .entries
        .joinToString("\n") { (cacheKey, synced) -> "$cacheKey|$synced" }

    private fun savePending(operations: List<PendingGameModSyncOperation>) {
        preferences.edit().putString(
            KEY_PENDING,
            operations.joinToString("\n") { "${it.cacheKey}|${it.type.name}" },
        ).apply()
    }

    private fun decodeSynced(value: String): Pair<String, Boolean>? {
        val parts = value.split('|')
        if (parts.size != 2 || !parts[0].matches(CACHE_KEY_REGEX)) return null
        return parts[1].toBooleanStrictOrNull()?.let { parts[0] to it }
    }

    private fun decodePending(value: String): PendingGameModSyncOperation? {
        val parts = value.split('|')
        if (parts.size != 2 || !parts[0].matches(CACHE_KEY_REGEX)) return null
        val type = runCatching { GameModSyncOperationType.valueOf(parts[1]) }.getOrNull() ?: return null
        return PendingGameModSyncOperation(parts[0], type)
    }

    private fun decodeLegacy(value: String): LegacyEntry? {
        val parts = value.split('|')
        if (parts.size != 3 || !parts[0].matches(CACHE_KEY_REGEX)) return null
        val enabled = parts[1].toBooleanStrictOrNull() ?: return null
        return LegacyEntry(parts[0], enabled)
    }

    private data class LegacyEntry(val cacheKey: String, val enabled: Boolean)

    private companion object {
        const val PREFS = "game-mod-sync"
        const val LEGACY_PREFS = "mod-deployment-plan"
        const val LEGACY_KEY_ENTRIES = "entries"
        const val KEY_MIGRATED = "migrated"
        const val KEY_SYNCED = "synced"
        const val KEY_PENDING = "pending"
        val CACHE_KEY_REGEX = Regex("[0-9a-f]{64}")
    }
}
