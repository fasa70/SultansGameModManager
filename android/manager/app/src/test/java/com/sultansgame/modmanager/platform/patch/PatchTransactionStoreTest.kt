package com.sultansgame.modmanager.platform.patch

import android.content.Context
import android.content.ContextWrapper
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
    fun reportsSizeForNewestCleanupCandidate() {
        val store = PatchTransactionStore(TestContext())
        createArtifacts(store, "older", transaction("older"), inputBytes = 4, signedBytes = 8)
        File(store.root("older"), "transaction.properties").setLastModified(1L)
        createArtifacts(store, "newer", transaction("newer", stage = PatchStage.Completed), inputBytes = 10, signedBytes = 20)
        File(store.root("newer"), "transaction.properties").setLastModified(2L)

        val candidate = requireNotNull(store.latestCleanupCandidate())

        assertEquals("newer", candidate.transactionId)
        assertEquals(PatchStage.Completed, candidate.stage)
        assertTrue(candidate.sizeBytes >= 50L)
    }

    @Test
    fun deletesWholePreparedTransactionDirectory() {
        val store = PatchTransactionStore(TestContext())
        createArtifacts(store, "prepared", transaction("prepared"), inputBytes = 4, signedBytes = 8)
        File(store.root("prepared"), "template/modloader.apk").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(3))
        }

        assertEquals(PatchArtifactCleanupResult.Deleted, store.deletePreparedArtifacts("prepared"))
        assertFalse(store.root("prepared").exists())
        assertNull(store.latestPreparedForRecovery())
    }

    @Test
    fun rejectsCleanupWhileSystemInstallIsPending() {
        val store = PatchTransactionStore(TestContext())
        createArtifacts(store, "submitted", transaction("submitted", stage = PatchStage.AwaitingSystemInstall), inputBytes = 4, signedBytes = 8)

        assertTrue(store.deletePreparedArtifacts("submitted") is PatchArtifactCleanupResult.Rejected)
        assertTrue(store.root("submitted").exists())
    }

    @Test
    fun rejectsUnsafeCleanupIdentifierWithoutTouchingOtherTransactions() {
        val store = PatchTransactionStore(TestContext())
        createArtifacts(store, "safe", transaction("safe"), inputBytes = 4, signedBytes = 8)

        assertTrue(store.deletePreparedArtifacts("../safe") is PatchArtifactCleanupResult.Rejected)
        assertTrue(store.root("safe").exists())
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
        signedArtifactNames = artifactNames,
    )

    private class TestContext : ContextWrapper(null) {
        private val directory = Files.createTempDirectory("patch-transactions-").toFile()
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
    }

    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
