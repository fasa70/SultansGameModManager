package com.sultansgame.modmanager.platform.storage

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidPrivateModCacheDeletionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun deletesOnlyRequestedCachedMod() {
        val cacheRoot = File(context.filesDir, "mod-delete-cache-${UUID.randomUUID()}")
        val cache = AndroidPrivateModCache(cacheRoot)
        val targetKey = "a".repeat(64)
        val otherKey = "b".repeat(64)
        try {
            assertTrue(File(cacheRoot, targetKey).mkdirs())
            assertTrue(File(cacheRoot, otherKey).mkdirs())

            val result = cache.deleteCached(targetKey)

            assertEquals(CachedModDeletionResult.Deleted, result)
            assertFalse(File(cacheRoot, targetKey).exists())
            assertTrue(File(cacheRoot, otherKey).isDirectory)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun returnsNotFoundForMissingCachedMod() {
        val cacheRoot = File(context.filesDir, "mod-delete-cache-${UUID.randomUUID()}")
        try {
            val result = AndroidPrivateModCache(cacheRoot).deleteCached("a".repeat(64))

            assertEquals(CachedModDeletionResult.NotFound, result)
            assertFalse(cacheRoot.exists())
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun rejectsInvalidCacheKeyWithoutDeletingCacheRoot() {
        val cacheRoot = File(context.filesDir, "mod-delete-cache-${UUID.randomUUID()}")
        val sentinel = File(cacheRoot, "sentinel")
        try {
            assertTrue(sentinel.mkdirs())

            val result = AndroidPrivateModCache(cacheRoot).deleteCached("../sentinel")

            assertTrue(result is CachedModDeletionResult.Rejected)
            assertTrue(sentinel.isDirectory)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun rejectsSymbolicLinkInsideCachedMod() {
        val cacheRoot = File(context.filesDir, "mod-delete-cache-${UUID.randomUUID()}")
        val cacheKey = "a".repeat(64)
        val target = File(context.filesDir, "mod-delete-target-${UUID.randomUUID()}")
        val link = File(cacheRoot, "$cacheKey/nested-link")
        try {
            assertTrue(target.mkdirs())
            File(target, "sentinel").writeText("keep")
            assertTrue(link.parentFile?.mkdirs() == true)
            Os.symlink(target.absolutePath, link.absolutePath)

            val result = AndroidPrivateModCache(cacheRoot).deleteCached(cacheKey)

            assertTrue(result is CachedModDeletionResult.Rejected)
            assertTrue(File(cacheRoot, cacheKey).isDirectory)
            assertTrue(File(target, "sentinel").isFile)
        } finally {
            link.delete()
            cacheRoot.deleteRecursively()
            target.deleteRecursively()
        }
    }

    @Test
    fun rejectsSymbolicLinkCachedMod() {
        val cacheRoot = File(context.filesDir, "mod-delete-cache-${UUID.randomUUID()}")
        val target = File(context.filesDir, "mod-delete-target-${UUID.randomUUID()}")
        val link = File(cacheRoot, "a".repeat(64))
        try {
            assertTrue(target.mkdirs())
            File(target, "sentinel").writeText("keep")
            assertTrue(cacheRoot.mkdirs())
            Os.symlink(target.absolutePath, link.absolutePath)

            val result = AndroidPrivateModCache(cacheRoot).deleteCached(link.name)

            assertTrue(result is CachedModDeletionResult.Rejected)
            assertTrue(File(target, "sentinel").isFile)
        } finally {
            link.delete()
            cacheRoot.deleteRecursively()
            target.deleteRecursively()
        }
    }
}
