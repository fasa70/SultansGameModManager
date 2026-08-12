package com.sultansgame.modmanager.merge

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Creates a validated, read-only working copy for one merge operation. */
class MergeWorkspace(private val root: File) : AutoCloseable {
    val directory: File = File(root, "merge-${System.nanoTime()}")

    fun copyInputs(orderedRoots: List<File>): List<File> {
        require(orderedRoots.size >= 2) { "至少选择两个 Mod" }
        require(directory.mkdirs() || directory.isDirectory) { "无法创建合并工作目录" }
        return orderedRoots.mapIndexed { index, source ->
            require(source.isDirectory) { "Mod 缓存目录不可读：${source.name}" }
            val destination = File(directory, "input-$index")
            copyTreeWithoutLinks(source, destination)
            destination
        }
    }

    fun outputDirectory(): File {
        require(directory.mkdirs() || directory.isDirectory) { "无法创建合并工作目录" }
        return File(directory, "output").apply { require(mkdirs() || isDirectory) { "无法创建合并输出目录" } }
    }

    override fun close() {
        directory.deleteRecursively()
    }

    private fun copyTreeWithoutLinks(source: File, destination: File) {
        require(destination.mkdirs() || destination.isDirectory) { "无法创建合并输入目录" }
        source.listFiles()?.sortedBy(File::getName)?.forEach { child ->
            require(!Files.isSymbolicLink(child.toPath())) { "Mod 包含不安全符号链接：${child.name}" }
            val target = File(destination, child.name)
            if (child.isDirectory) copyTreeWithoutLinks(child, target)
            else if (child.isFile) {
                require(target.parentFile.mkdirs() || target.parentFile.isDirectory) { "无法创建临时目录" }
                Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else error("Mod 包含非普通文件：${child.name}")
        } ?: error("无法读取 Mod 目录：${source.absolutePath}")
    }
}

fun File.writeUtf8(text: String) = writeText(text, Charsets.UTF_8)

fun File.readUtf8(): String = readText(Charsets.UTF_8)
