package top.apricityx.workshop.workshop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkshopOutputPathManagerTest {
    @Test
    fun `prepare keeps normal manifest file inside staging`() {
        val staging = Files.createTempDirectory("workshop-staging").toFile()
        try {
            val file = ManifestFile(
                path = "config/cards.json",
                size = 2,
                flags = 0u,
                shaContent = byteArrayOf(),
                linkTarget = null,
                chunks = emptyList(),
            )
            val entry = WorkshopOutputPathManager.prepare(staging, manifest(file), file)

            assertEquals(File(staging, "config/cards.json").canonicalPath, (entry as PreparedManifestEntry.FileEntry).target.canonicalPath)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test(expected = WorkshopDownloadException::class)
    fun `prepare rejects traversal path`() {
        val staging = Files.createTempDirectory("workshop-staging").toFile()
        try {
            val file = ManifestFile("../outside.json", 1, 0u, byteArrayOf(), null, emptyList())
            WorkshopOutputPathManager.prepare(staging, manifest(file), file)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun manifest(file: ManifestFile) = DepotManifest(
        depotId = 1u,
        manifestId = 1uL,
        createdAt = java.time.Instant.EPOCH,
        encryptedCrc = 0u,
        filenamesEncrypted = false,
        files = listOf(file),
    )
}
