package top.apricityx.workshop.workshop

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class WorkshopFileIntegrityTest {
    @Test
    fun `file sha mismatch is invalid even when content exists`() {
        val file = Files.createTempFile("workshop", ".bin").toFile()
        try {
            file.writeText("actual")
            val manifest = ManifestFile(
                path = "config/cards.json",
                size = file.length(),
                flags = 0u,
                shaContent = MessageDigest.getInstance("SHA-1").digest("expected".toByteArray()),
                linkTarget = null,
                chunks = emptyList(),
            )

            assertTrue(WorkshopFileIntegrityVerifier.assess(file, manifest) is AssembledFileValidation.Invalid)
        } finally {
            file.delete()
        }
    }
}
