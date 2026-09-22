package ac.mdiq.podcini.sourcing.download

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EpisodeTransferTest {
    @Test fun `resume uses full resource size even when response is a smaller segment`() {
        assertEquals(EpisodeTransferRange(100, 1000, 100), episodeTransferRange("bytes 100-199/1000", 100, 100))
    }

    @Test fun `resume works without a content length header`() {
        assertEquals(EpisodeTransferRange(100, 1000, 900), episodeTransferRange("bytes 100-999/1000", 100, null))
    }

    @Test fun `unknown total stays unknown`() {
        assertEquals(EpisodeTransferRange(100, -1, 100), episodeTransferRange("bytes 100-199/*", 100, 100))
    }

    @Test fun `a wrong offset must never be appended`() {
        assertNull(episodeTransferRange("bytes 0-999/1000", 100, 1000))
        assertNull(episodeTransferRange("bytes 100-999/1000", 0, 900))
    }

    @Test fun `reject malformed or contradictory ranges without throwing`() {
        for (header in listOf(null, "", "bytes */1000", "bytes 100-50/1000", "bytes 100-999/999",
            "bytes 100-999/0", "bytes 100-9223372036854775808/999", "items 100-999/1000")) {
            assertNull(header, episodeTransferRange(header, 100, null))
        }
        assertNull(episodeTransferRange("bytes 100-999/1000", 100, 500))
    }

    @Test fun `progress is not completed until the file is validated and closed`() {
        assertEquals(0, episodeDownloadProgress(123, -1))
        assertEquals(0, episodeDownloadProgress(0, 0))
        assertEquals(50, episodeDownloadProgress(500, 1000))
        assertEquals(99, episodeDownloadProgress(1000, 1000))
        assertEquals(99, episodeDownloadProgress(1200, 1000))
    }

    @Test fun `copies all bytes across pauses and multiple buffers`() = runBlocking {
        val expected = ByteArray(40000) { (it % 251).toByte() }
        val channel = ByteChannel(autoFlush = true)
        val writer = launch {
            channel.writeFully(expected, 0, 15000)
            delay(20)
            channel.writeFully(expected, 15000, expected.size)
            channel.flushAndClose()
        }
        val sink = Buffer()
        var count = 0
        copyEpisodeBody(channel, sink) { count += it }
        writer.join()
        assertEquals(expected.size, count)
        assertArrayEquals(expected, sink.readByteArray())
    }

    @Test fun `interrupted transfer preserves its error even without an expected size`() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val prefixRead = CompletableDeferred<Unit>()
        val writer = launch {
            channel.writeFully(byteArrayOf(1, 2, 3))
            prefixRead.await()
            channel.cancel(IOException("Connection interrupted"))
        }
        val sink = Buffer()
        val failure = runCatching { copyEpisodeBody(channel, sink) { prefixRead.complete(Unit) } }.exceptionOrNull()
        writer.join()
        assertNotNull(failure)
        assertEquals("Connection interrupted", failure?.message)
        assertEquals(3L, sink.size)
    }

    @Test fun `storage failure is not swallowed`() = runBlocking {
        val sink = object : Sink {
            override fun write(source: Buffer, byteCount: Long) { throw IOException("Disk full") }
            override fun flush() {}
            override fun close() {}
            override fun timeout() = Timeout.NONE
        }.buffer()
        val failure = runCatching { sink.use { copyEpisodeBody(ByteReadChannel(ByteArray(20000)), it) {} } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("Disk full", failure?.message)
    }

    @Test fun `cancellation is propagated`() = runBlocking {
        val failure = runCatching {
            copyEpisodeBody(ByteReadChannel(byteArrayOf(1)), Buffer()) { throw CancellationException("Cancelled") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
