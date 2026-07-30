package com.sultansgame.modmanager.platform.patch

import android.content.Context
import android.content.ContextWrapper
import com.sultansgame.modmanager.model.PatchStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
