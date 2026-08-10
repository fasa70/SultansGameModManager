package com.sultansgame.modmanager.platform.patch

import android.content.Context
import android.content.ContextWrapper
import com.sultansgame.modmanager.model.PatchInstallMode
import com.sultansgame.modmanager.model.PatchStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PatchTransactionStoreTest {
    @Test
    fun selectsNewestWellFormedPreparedTransaction() {
        val context = TestContext()
        val store = PatchTransactionStore(context)
        store.write(transaction("older"))
        File(store.root("older"), "transaction.properties").setLastModified(1L)
        store.write(transaction("newer", stage = PatchStage.AwaitingInstallPermission))
        File(store.root("newer"), "transaction.properties").setLastModified(2L)

        assertEquals("newer", store.latestPreparedForRecovery()?.id)
    }

    @Test
    fun rejectsTransactionWithUnsafeArtifactName() {
        val store = PatchTransactionStore(TestContext())
        store.write(transaction("unsafe", artifactNames = listOf("../base.apk", "modloader.apk")))

        assertNull(store.latestPreparedForRecovery())
    }

    @Test
    fun rejectsTransactionWithMissingOrMismatchedDigests() {
        val store = PatchTransactionStore(TestContext())
        store.write(transaction("missing", digests = listOf(DIGEST)))

        assertNull(store.latestPreparedForRecovery())
    }

    @Test
    fun excludesSubmittedSystemInstallTransaction() {
        val store = PatchTransactionStore(TestContext())
        store.write(transaction("submitted", stage = PatchStage.AwaitingSystemInstall))

        assertNull(store.latestPreparedForRecovery())
    }

    @Test
    fun reportsAggregateSizeForAllCleanupWorkspaces() {
        val store = PatchTransactionStore(TestContext())
        createArtifacts(store, "completed", transaction("completed", stage = PatchStage.Completed), inputBytes = 4, signedBytes = 8)
        createArtifacts(store, "interrupted", transaction("interrupted", stage = PatchStage.PreparingArtifacts), inputBytes = 10, signedBytes = 0)
        File(store.root("orphan"), "template/modloader.apk").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(3))
        }

        val summary = requireNotNull(store.cleanupSummary(emptySet()))

        assertEquals(setOf("completed", "interrupted", "orphan"), summary.workspaceIds)
        assertTrue(summary.sizeBytes >= 33L)
    }

    @Test
    fun cleansInterruptedAndOrphanWorkspacesWithoutTouchingOtherPrivateStorage() {
        val context = TestContext()
        val store = PatchTransactionStore(context)
        createArtifacts(store, "prepared", transaction("prepared"), inputBytes = 4, signedBytes = 8)
        File(store.root("prepared"), "template/modloader.apk").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(3))
        }
        File(store.root("orphan"), "input/selected.apk").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(5))
        }
        File(context.filesDir, "workshop-staging/task/content.zip").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(7))
        }
        File(context.filesDir, "mod-cache/mod/info.json").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(9))
        }

        val result = store.deleteCleanupWorkspaces(emptySet())

        assertTrue(result is PatchWorkspaceCleanupResult.Deleted)
        assertFalse(store.root("prepared").exists())
        assertFalse(store.root("orphan").exists())
        assertTrue(File(context.filesDir, "workshop-staging/task/content.zip").exists())
        assertTrue(File(context.filesDir, "mod-cache/mod/info.json").exists())
        assertNull(store.cleanupSummary(emptySet()))
    }

    @Test
    fun excludesSubmittedSystemInstallAndReservedWorkspacesFromCleanup() {
        val store = PatchTransactionStore(TestContext())
        createArtifacts(store, "submitted", transaction("submitted", stage = PatchStage.AwaitingSystemInstall), inputBytes = 4, signedBytes = 8)
        createArtifacts(store, "reserved", transaction("reserved"), inputBytes = 4, signedBytes = 8)
        createArtifacts(store, "cleanup", transaction("cleanup"), inputBytes = 4, signedBytes = 8)

        val summary = requireNotNull(store.cleanupSummary(setOf("reserved")))
        val result = store.deleteCleanupWorkspaces(setOf("reserved"))

        assertEquals(setOf("cleanup"), summary.workspaceIds)
        assertTrue(result is PatchWorkspaceCleanupResult.Deleted)
        assertTrue(store.root("submitted").exists())
        assertTrue(store.root("reserved").exists())
        assertFalse(store.root("cleanup").exists())
    }

    @Test
    fun deletesExactlyTheWorkspacesReportedByCleanupSummary() {
        val store = PatchTransactionStore(TestContext())
        createArtifacts(store, "submitted", transaction("submitted", stage = PatchStage.AwaitingSystemInstall), inputBytes = 4, signedBytes = 8)
        createArtifacts(store, "reserved", transaction("reserved"), inputBytes = 4, signedBytes = 8)
        createArtifacts(store, "cleanup", transaction("cleanup"), inputBytes = 4, signedBytes = 8)

        val summary = requireNotNull(store.cleanupSummary(setOf("reserved")))
        val result = store.deleteCleanupWorkspaces(setOf("reserved")) as PatchWorkspaceCleanupResult.Deleted

        assertEquals(summary.workspaceIds, result.workspaceIds)
        assertTrue(store.root("submitted").exists())
        assertTrue(store.root("reserved").exists())
        assertFalse(store.root("cleanup").exists())
    }

    @Test
    fun ignoresEmptyWorkspace() {
        val store = PatchTransactionStore(TestContext())
        store.root("empty").mkdirs()

        assertNull(store.cleanupSummary(emptySet()))
        assertEquals(PatchWorkspaceCleanupResult.NothingToDelete, store.deleteCleanupWorkspaces(emptySet()))
    }

    private fun createArtifacts(
        store: PatchTransactionStore,
        id: String,
        transaction: PatchTransaction,
        inputBytes: Int,
        signedBytes: Int,
    ) {
        store.write(transaction)
        File(store.root(id), "input/source.apk").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(inputBytes))
        }
        transaction.signedArtifactNames.forEach { name ->
            File(store.root(id), "signed/$name").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(signedBytes))
            }
        }
    }

    private fun transaction(
        id: String,
        stage: PatchStage = PatchStage.AwaitingGameUninstall,
        artifactNames: List<String> = listOf("base.apk", "modloader.apk"),
        digests: List<String> = listOf(DIGEST, DIGEST),
    ) = PatchTransaction(
        id = id,
        stage = stage,
        sessionId = null,
        artifactDigests = digests,
        expectedCertificateSha256 = CERTIFICATE,
        expectedVersionCode = 10005L,
        expectedSplitNames = listOf("modloader"),
        sourceSplitNames = listOf("modloader"),
        installMode = PatchInstallMode.FreshInstall,
        signedArtifactNames = artifactNames,
    )

    private class TestContext : ContextWrapper(null) {
        private val directory = Files.createTempDirectory("patch-transactions-").toFile()
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
    }

    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val CERTIFICATE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
