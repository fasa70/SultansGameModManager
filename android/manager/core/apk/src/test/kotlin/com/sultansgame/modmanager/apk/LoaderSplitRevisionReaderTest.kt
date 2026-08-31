package com.sultansgame.modmanager.apk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteIfExists
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoaderSplitRevisionReaderTest {
    private lateinit var temporary: File
    private val reader = LoaderSplitRevisionReader()

    @Before
    fun setUp() {
        temporary = Files.createTempDirectory("loader-revision-test").toFile()
    }

    @After
    fun tearDown() {
        temporary.deleteRecursively()
    }

    private fun archiveOf(vararg entries: Pair<String, ByteArray>): File =
        File(temporary, "archive-${System.nanoTime()}.zip").apply {
            ZipOutputStream(outputStream().buffered()).use { stream ->
                entries.forEach { (name, bytes) ->
                    stream.putNextEntry(ZipEntry(name))
                    stream.write(bytes)
                    stream.closeEntry()
                }
            }
        }

    private fun storedArchive(vararg entries: Pair<String, ByteArray>): File =
        File(temporary, "stored-${System.nanoTime()}.zip").apply {
            ZipOutputStream(outputStream().buffered()).use { stream ->
                entries.forEach { (name, bytes) ->
                    val entry = ZipEntry(name).apply { method = ZipEntry.STORED }
                    entry.size = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    stream.putNextEntry(entry)
                    stream.write(bytes)
                    stream.closeEntry()
                }
            }
        }

    // ---- File 路径 ----

    @Test
    fun `read file returns known revision`() {
        val archive = archiveOf(LOADER_REVISION_ENTRY to "7\n".toByteArray())
        assertEquals(LoaderSplitRevision.Known(7), reader.read(archive))
    }

    @Test
    fun `read file tolerates trailing whitespace`() {
        val archive = archiveOf(LOADER_REVISION_ENTRY to " 12 \n".toByteArray())
        assertEquals(LoaderSplitRevision.Known(12), reader.read(archive))
    }

    @Test
    fun `read file without revision entry is absent`() {
        val archive = archiveOf("AndroidManifest.xml" to "<manifest/>".toByteArray())
        assertEquals(LoaderSplitRevision.Absent, reader.read(archive))
    }

    @Test
    fun `read file with empty archive is absent`() {
        val archive = archiveOf()
        assertEquals(LoaderSplitRevision.Absent, reader.read(archive))
    }

    @Test
    fun `read file rejects non numeric content`() {
        listOf("abc", "0", "007", "-1", "1 2", "", "1.0").forEach { content ->
            val archive = archiveOf(LOADER_REVISION_ENTRY to content.toByteArray())
            assertTrue(reader.read(archive) is LoaderSplitRevision.Unreadable)
        }
    }

    @Test
    fun `read file rejects oversized entry`() {
        val archive = archiveOf(LOADER_REVISION_ENTRY to "1".repeat(64).toByteArray())
        assertTrue(reader.read(archive) is LoaderSplitRevision.Unreadable)
    }

    @Test
    fun `read file rejects non zip bytes`() {
        val file = File(temporary, "garbage.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5)) }
        assertTrue(reader.read(file) is LoaderSplitRevision.Unreadable)
    }

    @Test
    fun `read file rejects missing file`() {
        val file = File(temporary, "missing.apk")
        assertTrue(reader.read(file) is LoaderSplitRevision.Unreadable)
    }

    @Test
    fun `read file accepts nine digit boundary`() {
        val archive = archiveOf(LOADER_REVISION_ENTRY to "999999999\n".toByteArray())
        assertEquals(LoaderSplitRevision.Known(999_999_999), reader.read(archive))
    }

    @Test
    fun `read file rejects ten digit boundary`() {
        val archive = archiveOf(LOADER_REVISION_ENTRY to "1000000000\n".toByteArray())
        assertTrue(reader.read(archive) is LoaderSplitRevision.Unreadable)
    }

    // ---- 流式路径 ----

    @Test
    fun `read source matches file result on the same archive`() {
        listOf(
            "6\n" to LoaderSplitRevision.Known(6),
        ).forEach { (content, expected) ->
            val archive = archiveOf(
                "AndroidManifest.xml" to "<manifest/>".toByteArray(),
                LOADER_REVISION_ENTRY to content.toByteArray(),
            )
            assertEquals(expected, reader.read(archive))
            assertEquals(expected, reader.read { archive.inputStream() })
        }
    }

    @Test
    fun `read source without revision entry is absent`() {
        val archive = archiveOf("AndroidManifest.xml" to "<manifest/>".toByteArray())
        assertEquals(LoaderSplitRevision.Absent, reader.read { archive.inputStream() })
    }

    @Test
    fun `read source of empty archive is absent`() {
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).close()
            output.toByteArray()
        }
        assertEquals(LoaderSplitRevision.Absent, reader.read { ByteArrayInputStream(bytes) })
    }

    @Test
    fun `read source stops before large stored entry`() {
        val large = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val archive = storedArchive(
            "AndroidManifest.xml" to "<manifest/>".toByteArray(),
            LOADER_REVISION_ENTRY to "3\n".toByteArray(),
            LOADER_NATIVE_ENTRY to large,
        )
        val fileBytes = archive.readBytes()
        var consumed = 0
        val counting = reader.read {
            object : FilterInputStream(ByteArrayInputStream(fileBytes)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    if (count > 0) consumed += count
                    return count
                }

                override fun read(): Int {
                    val value = super.read()
                    if (value >= 0) consumed++
                    return value
                }
            }
        }
        assertEquals(LoaderSplitRevision.Known(3), counting)
        assertTrue("扫描读取了 ${consumed}B，几乎等于整个归档 ${fileBytes.size}B", consumed < fileBytes.size / 2)
    }

    @Test
    fun `read source rejects revision beyond scan limit`() {
        val entries = (1..65).map { index ->
            "entry-$index" to "x".toByteArray()
        } + (LOADER_REVISION_ENTRY to "1\n".toByteArray())
        val archive = archiveOf(*entries.toTypedArray())
        assertTrue(reader.read { archive.inputStream() } is LoaderSplitRevision.Unreadable)
    }

    @Test
    fun `read source accepts revision at the scan limit`() {
        val entries = (1..63).map { index ->
            "entry-$index" to "x".toByteArray()
        } + (LOADER_REVISION_ENTRY to "1\n".toByteArray())
        val archive = archiveOf(*entries.toTypedArray())
        assertEquals(LoaderSplitRevision.Known(1), reader.read { archive.inputStream() })
    }

    // ---- isLoaderSplit ----

    @Test
    fun `isLoaderSplit detects native marker entry`() {
        val archive = archiveOf(LOADER_NATIVE_ENTRY to "native".toByteArray())
        assertTrue(reader.isLoaderSplit(archive))
    }

    @Test
    fun `isLoaderSplit rejects archive without native entry`() {
        val archive = archiveOf("AndroidManifest.xml" to "<manifest/>".toByteArray())
        assertFalse(reader.isLoaderSplit(archive))
    }

    @Test
    fun `isLoaderSplit returns false for corrupt archive`() {
        val file = File(temporary, "garbage.bin").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        assertFalse(reader.isLoaderSplit(file))
    }

    // ---- parseLoaderRevision ----

    @Test
    fun `parseLoaderRevision validates canonical decimal form`() {
        assertEquals(LoaderSplitRevision.Known(1), parseLoaderRevision("1".toByteArray()))
        assertEquals(LoaderSplitRevision.Known(42), parseLoaderRevision(" 42 ".toByteArray()))
        assertTrue(parseLoaderRevision("0".toByteArray()) is LoaderSplitRevision.Unreadable)
        assertTrue(parseLoaderRevision("".toByteArray()) is LoaderSplitRevision.Unreadable)
        assertTrue(parseLoaderRevision("+1".toByteArray()) is LoaderSplitRevision.Unreadable)
    }

    @Test
    fun `constants expose the frozen contract`() {
        assertEquals("assets/modloader/revision", LOADER_REVISION_ENTRY)
        assertEquals("assets/modloader/arm64-v8a/modloader.bin", LOADER_NATIVE_ENTRY)
        // ZipFile.getEntry 精确匹配 entry 名，目录条目以 "/" 结尾。
        val archive = archiveOf("assets/modloader/" to ByteArray(0))
        ZipFile(archive).use { file ->
            assertEquals(null, file.getEntry(LOADER_REVISION_ENTRY))
        }
    }
}
