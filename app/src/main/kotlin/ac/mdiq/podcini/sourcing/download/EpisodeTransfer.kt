package ac.mdiq.podcini.sourcing.download

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import okio.BufferedSink

internal data class EpisodeTransferRange(val start: Long, val total: Long, val byteCount: Long)

/** Only append a response that describes exactly the requested byte range. */
internal fun episodeTransferRange(header: String?, existingSize: Long, contentLength: Long?): EpisodeTransferRange? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).matchEntire(header?.trim() ?: "") ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = if (match.groupValues[3] == "*") -1L else match.groupValues[3].toLongOrNull() ?: return null
    if (start != existingSize || end < start || end == Long.MAX_VALUE || (total != -1L && end >= total)) return null
    if (contentLength != null && contentLength != end - start + 1) return null
    return EpisodeTransferRange(start, total, end - start + 1)
}

/** Read through EOF and let network/storage failures reach the retry policy. */
internal suspend fun copyEpisodeBody(channel: ByteReadChannel, sink: BufferedSink, onBytes: suspend (Int) -> Unit) {
    val buffer = ByteArray(8192)
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = channel.readAvailable(buffer)
        if (read == -1) break
        if (read == 0) {
            yield()
            continue
        }
        sink.write(buffer, 0, read)
        onBytes(read)
    }
}

internal fun episodeDownloadProgress(downloaded: Long, total: Long): Int =
    if (total > 0) (100.0 * downloaded / total).toInt().coerceIn(0, 99) else 0
