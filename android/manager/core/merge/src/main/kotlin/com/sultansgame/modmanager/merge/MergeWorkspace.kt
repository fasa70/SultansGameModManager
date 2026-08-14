package com.sultansgame.modmanager.merge

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Creates a validated, read-only working copy for one merge operation. */
class MergeWorkspace(private val root: File) : AutoCloseable {
    val directory: File = File(root, "merge-${System.nanoTime()}-${java.util.UUID.randomUUID()}")

    fun copyInputs(orderedRoots: List<File>): List<File> {
        require(orderedRoots.size >= 2) { "至少选择两个 Mod" }
        ensureDirectory(root, "合并根目录")
        ensureDirectory(directory, "合并工作目录")
        return orderedRoots.mapIndexed { index, source ->
            require(source.isDirectory && !Files.isSymbolicLink(source.toPath())) {
                "Mod 缓存目录不可读：${source.name}"
            }
            val destination = File(directory, "input-$index")
            copyTreeWithoutLinks(source, destination)
            destination
        }
    }

    fun outputDirectory(): File {
        ensureDirectory(directory, "合并工作目录")
        val output = File(directory, "output")
        if (output.exists()) output.deleteRecursively()
        ensureDirectory(output, "合并输出目录")
        return output
    }

    fun pythonOutputDirectory(): File {
        ensureDirectory(directory, "合并工作目录")
        return File(directory, "python-remap")
    }

    override fun close() {
        directory.deleteRecursively()
    }

    private fun copyTreeWithoutLinks(source: File, destination: File) {
        ensureDirectory(destination, "合并输入目录")
        source.listFiles()?.sortedBy(File::getName)?.forEach { child ->
            require(!Files.isSymbolicLink(child.toPath())) { "Mod 包含不安全符号链接：${child.name}" }
            val target = File(destination, child.name)
            if (child.isDirectory) copyTreeWithoutLinks(child, target)
            else if (child.isFile) {
                ensureDirectory(target.parentFile, "合并临时目录")
                Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else error("Mod 包含非普通文件：${child.name}")
        } ?: error("无法读取 Mod 目录：${source.absolutePath}")
    }

    private fun ensureDirectory(path: File, label: String) {
        val nioPath = path.toPath()
        try {
            require(!Files.isSymbolicLink(nioPath)) {
                "${label}不可安全访问（符号链接）：${path.absolutePath}"
            }
            Files.createDirectories(nioPath)
            require(Files.isDirectory(nioPath)) {
                "${label}不是目录：${path.absolutePath}"
            }
        } catch (error: Exception) {
            if (error is IllegalArgumentException) throw error
            throw IllegalStateException(
                "${label}不可用：${path.absolutePath}（${error::class.java.simpleName}: " +
                    "${error.message ?: "未知文件系统错误"}）",
                error,
            )
        }
    }
}

fun File.writeUtf8(text: String) = writeText(text, Charsets.UTF_8)

fun File.readUtf8(): String = readText(Charsets.UTF_8)
