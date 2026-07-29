package com.sultansgame.modmanager.apk

import com.sultansgame.modmanager.model.Compatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReadOnlyApkInspectorTest {
    private val inspector = ReadOnlyApkInspector()

    @Test
    fun `inspects manifest and arm64 ABI without producing output`() {
        val archive = archiveOf(
            "AndroidManifest.xml" to byteArrayOf(1),
            "lib/arm64-v8a/libgame.so" to byteArrayOf(2),
        )

        val result = inspector.inspect("fixture.apk", archive.size.toLong()) { ByteArrayInputStream(archive) }

        assertTrue("arm64-v8a" in result.supportedAbis)
        assertTrue(result.warnings.isEmpty())
        assertEquals(Compatibility.Unverified, inspector.evaluate(result, null).compatibility)
    }

    @Test
    fun `warns on missing manifest and unsafe entry`() {
        val archive = archiveOf("../outside" to byteArrayOf(1))

        val result = inspector.inspect("unsafe.apk", archive.size.toLong()) { ByteArrayInputStream(archive) }

        assertTrue(result.warnings.any { "未找到" in it })
        assertTrue(result.warnings.any { "不安全" in it })
    }

    private fun archiveOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
