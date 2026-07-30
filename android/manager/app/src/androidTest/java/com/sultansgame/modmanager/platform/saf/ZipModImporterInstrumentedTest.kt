package com.sultansgame.modmanager.platform.saf

import android.content.Context
import android.net.Uri
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sultansgame.modmanager.platform.storage.AndroidPrivateModCache
import com.sultansgame.modmanager.storage.ImportValidationException
import org.junit.Assert.assertEquals
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
            val importer = ZipModImporter(context, AndroidPrivateModCache(cacheRoot))

            val imported = importer.importZip(Uri.fromFile(archive))

            assertEquals(1, imported.size)
            assertEquals("Private staging regression", imported.single().displayName)
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
    fun rejectsActualSymbolicLinkAsModRoot() {
        val id = UUID.randomUUID().toString()
        val testRoot = File(context.filesDir, "symbolic-link-mod-$id")
        val target = File(testRoot, "target")
        val link = File(testRoot, "link")
        val cache = AndroidPrivateModCache(File(context.filesDir, "symbolic-link-cache-$id"))
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
            output.putNextEntry(ZipEntry("Info.json"))
            output.write("{\"name\":\"Private staging regression\"}".toByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("config/cards.json"))
            output.write("{}".toByteArray())
            output.closeEntry()
        }
    }
}
