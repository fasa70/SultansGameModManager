package com.sultansgame.modmanager.platform.game

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 回归测试：游戏同步串行化的每个文件必须以单个输入流连续读取并恰好传输一次。
 *
 * 历史缺陷见 74ed889：带进度回调时 while 循环内每轮重新打开 FileInputStream，任何非空文件
 * 都会重复发送文件开头，导致游戏侧 ModStorageProvider 报“Mod 文件摘要不匹配”或管道中断。
 * [writeModPayload] 移除了该模式；这里用 [bounded] 限长输出流保证旧缺陷会快速失败而非挂起。
 */
class ModTransferPayloadTest {
    private val root = Files.createTempDirectory("mod-transfer-payload").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `payload streams each file exactly once and matches source bytes`() {
        val big = ByteArray(PROGRESS_REPORT_BUFFER_BYTES + 8192) { (it * 31).toByte() }
        File(root, "Info.json").writeBytes("""{"name":"传输测试"}""".toByteArray())
        File(root, "empty.json").writeBytes(ByteArray(0))
        File(root, "big.json").writeBytes(big)
        val expectedFiles = root.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        val expectedTotal = expectedFiles.sumOf(File::length)

        val progressProbes = mutableListOf<Pair<Long, Long>>()
        val withProgress = bounded(expectedTotal)
        writeModPayload(DataOutputStream(withProgress), root) { written, total -> progressProbes += written to total }
        val withoutProgress = bounded(expectedTotal)
        writeModPayload(DataOutputStream(withoutProgress), root, null)

        assertArrayEquals(withProgress.bytes, withoutProgress.bytes)

        DataInputStream(withoutProgress.bytes.inputStream()).use { input ->
            val count = input.readInt()
            assertEquals(expectedFiles.size, count)
            repeat(count) {
                val path = input.readUTF()
                val size = input.readLong()
                val digest = input.readUTF()
                val payload = ByteArray(size.toInt())
                input.readFully(payload)
                val file = expectedFiles.first { it.relativeTo(root).invariantSeparatorsPath == path }
                assertEquals(file.length(), size)
                assertEquals(sha256(file), digest)
                assertArrayEquals(file.readBytes(), payload)
            }
            assertEquals(-1, input.read())
        }

        assertTrue(progressProbes.isNotEmpty())
        assertTrue(progressProbes.zipWithNext().all { (a, b) -> a.first <= b.first })
        assertTrue(progressProbes.all { it.first in 0..it.second })
        assertEquals(expectedTotal, progressProbes.last().first)
        assertEquals(expectedTotal, progressProbes.last().second)
    }

    @Test
    fun `tiny mod is fully transferred with progress`() {
        File(root, "Info.json").writeBytes("{}".toByteArray())
        val probes = mutableListOf<Pair<Long, Long>>()
        val output = bounded(2)

        writeModPayload(DataOutputStream(output), root) { written, total -> probes += written to total }

        val (path, size) = DataInputStream(output.bytes.inputStream()).use { input ->
            assertEquals(1, input.readInt())
            input.readUTF() to input.readLong()
        }
        assertEquals("Info.json", path)
        assertEquals(2L, size)
        assertTrue(probes.isNotEmpty())
        assertEquals(2L, probes.last().first)
        assertEquals(2L, probes.last().second)
    }

    private fun bounded(totalBytes: Long): BoundedOutput = BoundedOutput(totalBytes + 256L * 1024)

    private class BoundedOutput(private val limit: Long) : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        val bytes: ByteArray get() = buffer.toByteArray()

        override fun write(b: Int) {
            require(buffer.size() + 1 <= limit) { "payload 超过预定上限，疑似重复发送文件开头" }
            buffer.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            require(buffer.size() + len <= limit) { "payload 超过预定上限，疑似重复发送文件开头" }
            buffer.write(b, off, len)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}