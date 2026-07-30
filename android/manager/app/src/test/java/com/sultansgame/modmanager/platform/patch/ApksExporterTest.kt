package com.sultansgame.modmanager.platform.patch

import com.sultansgame.modmanager.model.PatchStage
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ApksExporterTest {
    private val root = Files.createTempDirectory("apks-exporter-test").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun exportsVerifiedArtifactsAsStoredEntriesAndReportsProgress() {
        val transactionId = "transaction"
        val artifacts = linkedMapOf(
            "base.apk" to ByteArray(300_000) { (it % 251).toByte() },
            "modloader.apk" to ByteArray(32_000) { (it % 127).toByte() },
        )
        writeTransaction(transactionId, artifacts)
        val output = File(root, "patched.apks")
        val progress = mutableListOf<ApksExportProgress>()

        output.outputStream().use { stream ->
            ApksExporter(PatchTransactionStore(root)).export(transactionId, stream, progress::add)
        }

        ZipFile(output).use { zip ->
            assertEquals(artifacts.keys.toList(), zip.entries().asSequence().map { it.name }.toList())
            artifacts.forEach { (name, bytes) ->
                val entry = requireNotNull(zip.getEntry(name))
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(bytes.size.toLong(), entry.size)
                assertEquals(entry.size, entry.compressedSize)
                assertArrayEquals(bytes, zip.getInputStream(entry).readBytes())
            }
        }
        assertTrue(progress.first() is ApksExportProgress.Validating)
        val writes = progress.filterIsInstance<ApksExportProgress.Writing>()
        assertTrue(writes.isNotEmpty())
        assertTrue(writes.zipWithNext().all { (before, after) -> before.writtenBytes <= after.writtenBytes })
        assertTrue(writes.all { it.writtenBytes <= it.totalBytes })
        assertEquals(artifacts.values.sumOf { it.size.toLong() }, writes.last().writtenBytes)
        assertEquals(artifacts.size, writes.last().completedArtifacts())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsArtifactWhoseDigestChangedAfterPreparation() {
        val transactionId = "transaction"
        val artifacts = linkedMapOf("base.apk" to byteArrayOf(1, 2, 3))
        writeTransaction(transactionId, artifacts)
        File(PatchTransactionStore(root).root(transactionId), "signed/base.apk").writeBytes(byteArrayOf(4, 5, 6))

        ApksExporter(PatchTransactionStore(root)).export(transactionId, ByteArrayOutputStream())
    }

    private fun writeTransaction(transactionId: String, artifacts: Map<String, ByteArray>) {
        val store = PatchTransactionStore(root)
        val signed = File(store.root(transactionId), "signed").apply { mkdirs() }
        artifacts.forEach { (name, bytes) -> File(signed, name).writeBytes(bytes) }
        store.write(
            PatchTransaction(
                id = transactionId,
                stage = PatchStage.AwaitingGameUninstall,
                sessionId = null,
                artifactDigests = artifacts.values.map(::sha256),
                signedArtifactNames = artifacts.keys.toList(),
            ),
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun ApksExportProgress.Writing.completedArtifacts(): Int = artifactIndex + 1
}
