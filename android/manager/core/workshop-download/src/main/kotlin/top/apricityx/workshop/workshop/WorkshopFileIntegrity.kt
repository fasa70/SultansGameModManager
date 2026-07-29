package top.apricityx.workshop.workshop

import java.io.File
import java.security.MessageDigest

internal sealed interface AssembledFileValidation {
    data object Verified : AssembledFileValidation

    data class Invalid(
        val expectedShaHex: String,
        val actualShaHex: String,
    ) : AssembledFileValidation
}

internal object WorkshopFileIntegrityVerifier {
    fun assess(file: File, manifestFile: ManifestFile): AssembledFileValidation {
        if (manifestFile.shaContent.isEmpty()) {
            return AssembledFileValidation.Verified
        }
        val actualSha = sha1(file)
        return if (actualSha.contentEquals(manifestFile.shaContent)) {
            AssembledFileValidation.Verified
        } else {
            AssembledFileValidation.Invalid(
                expectedShaHex = manifestFile.shaContent.toHexString(),
                actualShaHex = actualSha.toHexString(),
            )
        }
    }

    private fun sha1(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }
