package top.apricityx.workshop.workshop

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFailureMessagesTest {
    @Test
    fun `classifies transient HTTP responses as retryable`() {
        val failure = WorkshopDownloadException("Direct download failed: 503").toDownloadFailure()

        assertEquals(DownloadFailure.HttpFailure(503), failure)
        assertTrue(failure.retryable)
    }

    @Test
    fun `classifies invalid content as non retryable`() {
        val failure = WorkshopDownloadException("Chunk length mismatch").toDownloadFailure()

        assertEquals(DownloadFailure.SizeMismatch, failure)
        assertFalse(failure.retryable)
    }

    @Test
    fun `classifies IO errors as retryable network failures`() {
        val failure = IOException("connection timeout").toDownloadFailure()

        assertEquals(DownloadFailure.Network, failure)
        assertTrue(failure.retryable)
    }
}
