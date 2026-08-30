package com.sultansgame.modmanager.platform.saveeditor

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One manager-side snapshot of a save file, taken before the manager overwrote
 * it. [path] is the absolute location inside app-private storage.
 */
data class SaveBackupEntry(
    val uid: String,
    val fileName: String,
    val createdAt: Long,
    val createdAtText: String,
    val sizeBytes: Long,
    val path: String,
)

/**
 * Keeps recoverable copies of the game's save files in app-private storage.
 *
 * The patched game already renames the previous revision to `<file>.sgmm-bak`
 * on every write, but that only ever holds one generation and lives in the
 * game's own directory. This store keeps the last [maxPerFile] revisions the
 * manager itself was about to overwrite, so a bad edit can always be rolled
 * back from the manager UI.
 *
 * Layout: `<root>/<uid>/<encoded save file name>/<epoch millis>.json`. The
 * directory name is escaped so nested names such as `USERARCHIVE/003.json`
 * cannot escape the root or collide with each other.
 */
class SaveBackupStore(
    private val root: File,
    private val maxPerFile: Int = MAX_BACKUPS_PER_FILE,
) {
    /**
     * Stores [content] as the newest backup of `uid/fileName`. Returns the
     * existing newest entry unchanged when it already holds exactly this
     * content, so repeated saves do not spend the quota on duplicates.
     */
    fun create(uid: String, fileName: String, content: String): SaveBackupEntry {
        val dir = directoryFor(uid, fileName)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("无法创建备份目录：${dir.absolutePath}")
        }
        list(uid, fileName).firstOrNull()?.let { latest ->
            if (runCatching { File(latest.path).readText() }.getOrNull() == content) return latest
        }
        var stamp = System.currentTimeMillis()
        while (File(dir, "$stamp$SUFFIX").exists()) stamp++
        val target = File(dir, "$stamp$SUFFIX")
        val temp = File(dir, "$stamp$SUFFIX$TEMP_SUFFIX")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("无法写入备份文件：${target.absolutePath}")
        }
        prune(dir)
        return entryOf(uid, fileName, target)
            ?: throw IOException("备份写入后无法读取：${target.absolutePath}")
    }

    /** Newest first. Unparsable or partially written files are ignored. */
    fun list(uid: String, fileName: String): List<SaveBackupEntry> {
        val files = directoryFor(uid, fileName).listFiles() ?: return emptyList()
        return files.mapNotNull { entryOf(uid, fileName, it) }.sortedByDescending { it.createdAt }
    }

    fun read(entry: SaveBackupEntry): String {
        val file = File(entry.path)
        if (!file.isFile) throw IOException("备份文件已不存在：${entry.fileName}")
        return file.readText()
    }

    fun delete(entry: SaveBackupEntry): Boolean = File(entry.path).delete()

    private fun prune(dir: File) {
        val files = dir.listFiles() ?: return
        files.filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }
        files.filter { it.isFile && it.name.endsWith(SUFFIX) }
            .sortedByDescending { it.name.removeSuffix(SUFFIX).toLongOrNull() ?: 0L }
            .drop(maxPerFile)
            .forEach { it.delete() }
    }

    private fun entryOf(uid: String, fileName: String, file: File): SaveBackupEntry? {
        if (!file.isFile || !file.name.endsWith(SUFFIX)) return null
        val createdAt = file.name.removeSuffix(SUFFIX).toLongOrNull() ?: return null
        return SaveBackupEntry(
            uid = uid,
            fileName = fileName,
            createdAt = createdAt,
            createdAtText = formatTime(createdAt),
            sizeBytes = file.length(),
            path = file.absolutePath,
        )
    }

    private fun directoryFor(uid: String, fileName: String): File =
        File(File(root, escape(uid)), escape(fileName))

    private companion object {
        const val MAX_BACKUPS_PER_FILE = 10
        const val SUFFIX = ".json"
        const val TEMP_SUFFIX = ".tmp"

        /**
         * Fixed-width escaping: every character outside `[A-Za-z0-9._-]` becomes
         * `%XXXX`. The constant width keeps the mapping injective, so two save
         * files can never share a backup directory.
         */
        fun escape(value: String): String = buildString {
            value.forEach { ch ->
                val plain = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') ||
                    ch == '.' || ch == '_' || ch == '-'
                if (plain) append(ch) else append('%').append("%04X".format(ch.code))
            }
        }

        fun formatTime(millis: Long): String =
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date(millis))
    }
}
