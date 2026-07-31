package com.sultansgame.modmanager.platform.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.sultansgame.modmanager.storage.ImportValidationException
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ExternalZipImportRequest(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
)

sealed interface ExternalZipIntentResult {
    data object Ignored : ExternalZipIntentResult

    data object MultipleFilesNotSupported : ExternalZipIntentResult

    data class Accepted(val uri: Uri) : ExternalZipIntentResult

    data class Rejected(val reason: String) : ExternalZipIntentResult
}

class ExternalZipInbox(private val context: Context) {
    fun inspect(intent: Intent): ExternalZipIntentResult = when (intent.action) {
        Intent.ACTION_VIEW -> inspectUri(intent.data, intent.flags)
        Intent.ACTION_SEND -> inspectUri(intent.parcelableStream(), intent.flags)
        Intent.ACTION_SEND_MULTIPLE -> ExternalZipIntentResult.MultipleFilesNotSupported
        else -> ExternalZipIntentResult.Ignored
    }

    fun receive(uri: Uri): ExternalZipImportRequest {
        val root = inboxRoot()
        if (!root.mkdirs() && !root.isDirectory) throw ImportValidationException("无法创建外部 ZIP 收件箱")
        val id = UUID.randomUUID().toString()
        val partial = File(root, ".$id.partial")
        val destination = File(root, "$id.zip")
        try {
            val size = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(partial).use { output ->
                    copyBounded(input, output).also { output.fd.sync() }
                }
            } ?: throw ImportValidationException("无法读取外部 ZIP 文件")
            if (!partial.renameTo(destination)) throw ImportValidationException("无法保存外部 ZIP 文件")
            return ExternalZipImportRequest(id, displayNameFor(uri), size)
        } catch (error: Exception) {
            partial.delete()
            destination.delete()
            throw error
        }
    }

    fun fileFor(request: ExternalZipImportRequest): File {
        require(request.id.matches(REQUEST_ID_REGEX)) { "外部 ZIP 标识无效" }
        return File(inboxRoot(), "${request.id}.zip")
    }

    fun discard(request: ExternalZipImportRequest) {
        fileFor(request).delete()
    }

    fun recoverInterruptedReceipts() {
        inboxRoot().listFiles()?.forEach(File::delete)
    }

    private fun inspectUri(uri: Uri?, flags: Int): ExternalZipIntentResult {
        if (uri == null) return ExternalZipIntentResult.Rejected("未收到可导入的 ZIP 文件")
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return ExternalZipIntentResult.Rejected("仅支持由系统内容提供器分享的 ZIP 文件")
        }
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            return ExternalZipIntentResult.Rejected("外部 ZIP 文件未授予读取权限")
        }
        return ExternalZipIntentResult.Accepted(uri)
    }

    private fun displayNameFor(uri: Uri): String = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        cursor.takeIf { column >= 0 && it.moveToFirst() }?.getString(column)
    }?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "外部 ZIP 文件"

    private fun inboxRoot(): File = File(context.filesDir, INBOX_DIRECTORY)

    private fun copyBounded(input: java.io.InputStream, output: FileOutputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total = Math.addExact(total, count.toLong())
            if (total > MAXIMUM_ARCHIVE_SIZE_BYTES) throw ImportValidationException("ZIP 原始文件大小超出限制")
            output.write(buffer, 0, count)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableStream(): Uri? = getParcelableExtra(Intent.EXTRA_STREAM)

    private companion object {
        const val INBOX_DIRECTORY = "external-zip-inbox"
        const val MAXIMUM_ARCHIVE_SIZE_BYTES = 512L * 1024 * 1024
        val REQUEST_ID_REGEX = Regex("[0-9a-f-]{36}")
    }
}
