package com.sultansgame.modmanager.platform.storage

import android.content.Context
import com.sultansgame.modmanager.model.CachedMod
import com.sultansgame.modmanager.model.DeploymentEntry
import com.sultansgame.modmanager.model.DeploymentSnapshot
import com.sultansgame.modmanager.model.MOD_DEPLOYMENT_ORDER_STEP
import java.security.MessageDigest
import java.util.UUID

class DeploymentPlanStore(context: Context) {
    private val preferences = context.getSharedPreferences("mod-deployment-plan", Context.MODE_PRIVATE)

    fun entries(cachedMods: List<CachedMod>): List<DeploymentEntry> {
        val cacheByKey = cachedMods.associateBy(CachedMod::cacheKey)
        val saved = preferences.getString(KEY_ENTRIES, "").orEmpty()
            .lineSequence()
            .mapNotNull(::decode)
            .filter { it.cacheKey in cacheByKey }
            .sortedBy(StoredEntry::order)
            .toList()
        val known = saved.mapTo(mutableSetOf(), StoredEntry::cacheKey)
        val additions = cachedMods.filter { it.cacheKey !in known }.mapIndexed { index, cached ->
            StoredEntry(cached.cacheKey, false, (saved.size + index) * MOD_DEPLOYMENT_ORDER_STEP)
        }
        return (saved + additions).mapNotNull { stored ->
            cacheByKey[stored.cacheKey]?.let { cached ->
                DeploymentEntry(
                    cacheKey = cached.cacheKey,
                    contentDigestSha256 = cached.contentDigestSha256,
                    displayName = cached.displayName,
                    enabled = stored.enabled,
                    order = stored.order,
                )
            }
        }
    }

    fun setEnabled(cacheKey: String, enabled: Boolean, cachedMods: List<CachedMod>) {
        save(entries(cachedMods).map { entry ->
            if (entry.cacheKey == cacheKey) entry.copy(enabled = enabled) else entry
        })
    }

    fun move(cacheKey: String, delta: Int, cachedMods: List<CachedMod>) {
        val entries = entries(cachedMods).toMutableList()
        val currentIndex = entries.indexOfFirst { it.cacheKey == cacheKey }
        val targetIndex = (currentIndex + delta).coerceIn(0, entries.lastIndex)
        if (currentIndex < 0 || currentIndex == targetIndex) return
        val moved = entries.removeAt(currentIndex)
        entries.add(targetIndex, moved)
        save(entries.mapIndexed { index, entry -> entry.copy(order = index * MOD_DEPLOYMENT_ORDER_STEP) })
    }

    fun remove(cacheKey: String, cachedMods: List<CachedMod>) {
        save(entries(cachedMods).filterNot { it.cacheKey == cacheKey })
    }

    fun snapshot(cachedMods: List<CachedMod>, allowExternalReplacement: Boolean): DeploymentSnapshot {
        val entries = entries(cachedMods)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(entries.joinToString("\n") { entry ->
                "${entry.cacheKey}\t${entry.enabled}\t${entry.order}"
            }.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return DeploymentSnapshot(UUID.randomUUID().toString(), entries, digest, allowExternalReplacement)
    }

    private fun save(entries: List<DeploymentEntry>) {
        preferences.edit().putString(
            KEY_ENTRIES,
            entries.mapIndexed { index, entry ->
                "${entry.cacheKey}|${entry.enabled}|${index * MOD_DEPLOYMENT_ORDER_STEP}"
            }.joinToString("\n"),
        ).apply()
    }

    private fun decode(value: String): StoredEntry? {
        val parts = value.split('|')
        if (parts.size != 3 || !parts[0].matches(Regex("[0-9a-f]{64}"))) return null
        val enabled = parts[1].toBooleanStrictOrNull() ?: return null
        val order = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return StoredEntry(parts[0], enabled, order)
    }

    private data class StoredEntry(val cacheKey: String, val enabled: Boolean, val order: Int)

    private companion object {
        const val KEY_ENTRIES = "entries"
    }
}
