package com.sultansgame.modmanager.platform.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sultansgame.modmanager.platform.storage.AndroidPrivateModCache
import com.sultansgame.modmanager.storage.ImportValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ZipModImporterInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun importsZipFromPrivateStagingDirectory() {
        val id = UUID.randomUUID().toString()
        val archive = File(context.cacheDir, "zip-mod-import-$id.zip")
        val cacheRoot = File(context.filesDir, "zip-mod-cache-$id")
        val stagingRoot = File(context.filesDir, "mod-cache")
        val partialsBefore = stagingRoot.listFiles()
            ?.filter { it.name.startsWith('.') && it.name.endsWith(".partial") }
            ?.map(File::getName)
            ?.toSet()
            .orEmpty()
        try {
            writeZip(archive)
            val importer = ZipModImporter(context, AndroidPrivateModCache(cacheRoot, context))

            val imported = importer.importZip(Uri.fromFile(archive))

            assertEquals(1, imported.size)
            assertEquals("zip-mod-import-$id", imported.single().displayName)
            val destination = File(cacheRoot, imported.single().cacheKey)
            assertTrue(destination.isDirectory)
            assertTrue(File(destination, "Info.json").isFile)
            assertTrue(File(destination, "config/cards.json").isFile)
            val partialsAfter = stagingRoot.listFiles()
                ?.filter { it.name.startsWith('.') && it.name.endsWith(".partial") }
                ?.map(File::getName)
                ?.toSet()
                .orEmpty()
            assertEquals(partialsBefore, partialsAfter)
        } finally {
            archive.delete()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun importsMultipleRootsUsingDirectoryNames() {
        val id = UUID.randomUUID().toString()
        val archive = File(context.cacheDir, "zip-mod-multiple-$id.zip")
        val cacheRoot = File(context.filesDir, "zip-mod-multiple-cache-$id")
        try {
            ZipOutputStream(archive.outputStream()).use { output ->
                writeEntry(output, "first/Info.json", "not-json")
                writeEntry(output, "second/Info.json", "{\"name\":")
            }

            val imported = ZipModImporter(context, AndroidPrivateModCache(cacheRoot, context)).importZip(archive)

            assertEquals(listOf("first", "second"), imported.map { it.displayName })
            assertEquals(2, cacheRoot.listFiles()?.count { it.isDirectory && !it.name.startsWith('.') })
        } finally {
            archive.delete()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun rejectsInvalidBatchBeforeCreatingAnyCacheEntry() {
        val id = UUID.randomUUID().toString()
        val root = File(context.filesDir, "batch-source-$id")
        val cacheRoot = File(context.filesDir, "batch-cache-$id")
        val valid = File(root, "valid")
        val invalid = File(root, "invalid")
        try {
            assertTrue(valid.mkdirs())
            assertTrue(invalid.mkdirs())
            File(valid, "Info.json").writeText("{\"name\":\"Valid\"}")
            File(invalid, "Info.json").writeText("not-json")

            val error = runCatching {
                AndroidPrivateModCache(cacheRoot, context).importDirectories(
                    listOf(valid, invalid),
                    com.sultansgame.modmanager.model.CacheSource.SafArchive,
                )
            }.exceptionOrNull()

            assertTrue(error is ImportValidationException)
            assertFalse(cacheRoot.listFiles()?.any { it.isDirectory && !it.name.startsWith('.') } == true)
        } finally {
            root.deleteRecursively()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun acceptsMalformedInfoJsonWithoutChangingPayload() {
        val id = UUID.randomUUID().toString()
        val archive = File(context.cacheDir, "zip-mod-malformed-$id.zip")
        val cacheRoot = File(context.filesDir, "zip-mod-malformed-cache-$id")
        val manifest = byteArrayOf('{'.code.toByte(), 0.toByte(), '}'.code.toByte())
        try {
            ZipOutputStream(archive.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("Info.json"))
                output.write(manifest)
                output.closeEntry()
            }

            val imported = ZipModImporter(context, AndroidPrivateModCache(cacheRoot, context)).importZip(archive)
            val cachedManifest = File(cacheRoot, "${imported.single().cacheKey}/Info.json")

            assertEquals(archive.nameWithoutExtension, imported.single().displayName)
            assertTrue(cachedManifest.readBytes().contentEquals(manifest))
        } finally {
            archive.delete()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun acceptsOnlySingleReadableContentUriFromExternalIntent() {
        val inbox = ExternalZipInbox(context)
        val content = Uri.parse("content://example.mod.provider/mod.zip")

        val accepted = inbox.inspect(Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, content)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        val missingGrant = inbox.inspect(Intent(Intent.ACTION_VIEW, content))
        val fileUri = inbox.inspect(Intent(Intent.ACTION_VIEW, Uri.fromFile(File(context.cacheDir, "mod.zip"))).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })

        assertTrue(accepted is ExternalZipIntentResult.Accepted)
        assertTrue(missingGrant is ExternalZipIntentResult.Rejected)
        assertTrue(fileUri is ExternalZipIntentResult.Rejected)
        assertEquals(
            ExternalZipIntentResult.MultipleFilesNotSupported,
            inbox.inspect(Intent(Intent.ACTION_SEND_MULTIPLE)),
        )
    }

    @Test
    fun rejectsActualSymbolicLinkAsModRoot() {
        val id = UUID.randomUUID().toString()
        val testRoot = File(context.filesDir, "symbolic-link-mod-$id")
        val target = File(testRoot, "target")
        val link = File(testRoot, "link")
        val cache = AndroidPrivateModCache(File(context.filesDir, "symbolic-link-cache-$id"), context)
        try {
            assertTrue(target.mkdirs())
            File(target, "Info.json").writeText("{\"name\":\"Link target\"}")
            Os.symlink(target.absolutePath, link.absolutePath)

            val error = runCatching { cache.validateDirectory(link) }.exceptionOrNull()

            assertTrue(error is ImportValidationException)
            assertEquals("Mod 根目录不可读", error?.message)
        } finally {
            link.delete()
            testRoot.deleteRecursively()
        }
    }

    private fun writeZip(archive: File) {
        ZipOutputStream(archive.outputStream()).use { output ->
            writeEntry(output, "Info.json", "{\"name\":\"Private staging regression\"}")
            writeEntry(output, "config/cards.json", "{}")
        }
    }

    private fun writeEntry(output: ZipOutputStream, name: String, content: String) {
        output.putNextEntry(ZipEntry(name))
        output.write(content.toByteArray())
        output.closeEntry()
    }
}
