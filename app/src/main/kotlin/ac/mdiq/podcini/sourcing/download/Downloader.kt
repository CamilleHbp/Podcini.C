package ac.mdiq.podcini.sourcing.download

import ac.mdiq.podcini.R
import ac.mdiq.podcini.sourcing.download.DownloadRequest.Companion.CredentialsKey
import ac.mdiq.podcini.utils.NetworkUtils.getURIFromRequestUrl
import ac.mdiq.podcini.utils.NetworkUtils.isNetworkUrl
import ac.mdiq.podcini.utils.NetworkUtils.wasDownloadBlocked
import ac.mdiq.podcini.shared.PodciniHttpClient.getKtorClient
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.model.DownloadResult
import ac.mdiq.podcini.storage.utils.freeSpaceAvailable
import ac.mdiq.podcini.storage.utils.parseDate
import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.startTiming
import ac.mdiq.podcini.utils.timeIt
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.util.network.UnresolvedAddressException
import io.ktor.utils.io.asSource
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.buffered
import okio.buffer
import kotlin.use

abstract class Downloader(val request: DownloadRequest) {

    var cancelled: Boolean
    
    var permanentRedirectUrl: String? = null

    
    val result: DownloadResult

    protected val downloadDispatcher = Dispatchers.IO.limitedParallelism(3)

    init {
        this.request.statusMsg = (R.string.download_pending)
        this.cancelled = false
        this.result = DownloadResult(this.request.feedfileId, this.request.title?:"", null, false, "", this.request.feedfileType, nowInMillis())
    }

    open suspend fun download() { Loge(TAG, "download() method is not implemented") }

    open suspend fun download(cb: suspend (Source)->Unit) { Loge(TAG, "download(cb) method is not implemented") }

    fun cancel() {
        cancelled = true
    }

    protected fun callOnFailByResponseCode(response: HttpResponse) {
        val statusCodeInt = response.status.value
        val details = statusCodeInt.toString()
        val error: DownloadError = when (response.status) {
            HttpStatusCode.Unauthorized -> DownloadError.ERROR_UNAUTHORIZED
            HttpStatusCode.Forbidden -> DownloadError.ERROR_FORBIDDEN
            HttpStatusCode.NotFound, HttpStatusCode.Gone -> DownloadError.ERROR_NOT_FOUND
            else -> DownloadError.ERROR_HTTP_DATA_ERROR
        }
        onFail(error, details)
    }

    protected fun checkIfRedirect(response: HttpResponse) {
        val isRedirect = response.status.value in 300..399
        if (!isRedirect) return

        val location = response.headers["Location"] ?: return
        val originalUrl = response.request.url.toString()
        when {
            response.status == HttpStatusCode.MovedPermanently -> { // 301
                Logd(TAG) { "Detected permanent redirect from $originalUrl to $location" }
                permanentRedirectUrl = location
            }
            location == originalUrl.replace("http://", "https://") -> {
                Logd(TAG) { "Treating http->https redirect as permanent: $originalUrl" }
                permanentRedirectUrl = location
            }
        }
    }

    protected fun onFail(reason: DownloadError, reasonDetailed: String?) {
        android.util.Log.e(TAG, "Download failed: $reason; $reasonDetailed")
        result.isSuccessful = false
        result.reason = reason
        result.addDetail(reasonDetailed?:"")
    }

    protected fun onCancelled() {
        Logd(TAG) { "Download was cancelled" }
        result.isSuccessful = false
        result.reason = DownloadError.ERROR_DOWNLOAD_CANCELLED
        cancelled = true
    }

    companion object {
        private val TAG: String = Downloader::class.simpleName ?: "Anonymous"

        val downloadStatesFlow = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

        protected const val BUFFER_SIZE = 8 * 1024

        fun downloaderFor(request: DownloadRequest): Downloader? {
            if (!isNetworkUrl(request.source)) {
                Loge(TAG, "Could not find appropriate downloader for " + request.source)
                return null
            }
            return if (request.feedfileType == RequestType.FEED.code) FeedDownloader(request) else EpisodeDownloader(request)
        }
    }
}

class FeedDownloader(request: DownloadRequest): Downloader(request) {
    override suspend fun download(cb: suspend (Source)->Unit) {
        Logd(TAG) { "starting downloadFeed() source: ${request.source} dest: ${request.destination}" }
        if (request.source == null) return

        val destFile = request.destination.toUF()
        val fileExists = destFile.exists()
        Logd(TAG) { "destination: ${request.destination} fileExists: $fileExists" }

        try {
            val uri = getURIFromRequestUrl(request.source)
            getKtorClient().prepareGet(uri.toString()) {
                attributes.put(CredentialsKey, request)
                header(HttpHeaders.CacheControl, "no-store")
                if (uri.scheme == "http") header("Upgrade-Insecure-Requests", "1")
                Logd(TAG) { "starting download: ${request.feedfileType} ${uri.scheme}" }

                if (!request.lastModified.isNullOrEmpty()) {
                    val lastModified = request.lastModified
                    val lastModifiedDate = parseDate(lastModified)
                    if (lastModifiedDate != null) {
                        val threeDaysAgo = nowInMillis() - 1000 * 60 * 60 * 24 * 3
                        if (lastModifiedDate.toEpochMilliseconds() > threeDaysAgo) {
                            Logd(TAG) { "addHeader(\"If-Modified-Since\", \"$lastModified\")" }
                            header(HttpHeaders.IfModifiedSince, lastModified)
                        }
                    } else {
                        Logd(TAG) { "addHeader(\"If-None-Match\", \"$lastModified\")" }
                        header(HttpHeaders.IfNoneMatch, lastModified?:"")
                    }
                }
                val size = destFile.size()?:0
                if (fileExists && size > 0) {
                    request.soFar = size
                    header(HttpHeaders.Range, "bytes=${request.soFar}-")
                    Logd(TAG) { "Adding range header: " + request.soFar }
                }
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
                val contentEncodingHeader = response.headers[HttpHeaders.ContentEncoding]
                Logd(TAG) { "response.status: ${response.status}" }

                when {
                    response.status == HttpStatusCode.NotModified -> {
                        Logd(TAG) { "Feed '" + request.source + "' not modified since last update, Download canceled" }
                        onCancelled()
                        return@execute
                    }
                    response.status == HttpStatusCode.RequestedRangeNotSatisfiable -> {
                        val lastModified = response.headers[HttpHeaders.LastModified]
                        if (lastModified != null) request.lastModified = lastModified
                        else request.lastModified = response.headers[HttpHeaders.ETag]
                        result.setSuccessful()
                        request.progressPercent = 100
                        return@execute
                    }
                    response.status == HttpStatusCode.PartialContent -> {
                        // TODO: this appears not needed
                        val contentRangeHeader = if (fileExists) response.headers[HttpHeaders.ContentRange] else null
                        if (fileExists && response.status == HttpStatusCode.PartialContent && !contentRangeHeader.isNullOrEmpty()) {
                            val start = contentRangeHeader.removePrefix("bytes ").substringBefore('-').toLong()
                            if (start != request.soFar) {
                                Logt(TAG, "Unexpected resume offset $start, restarting download")
                                destFile.delete()
                            } else Logd(TAG) { "Resuming download at $start" }
                            val remaining = response.contentLength()
                            request.size = if (remaining != null) remaining + request.soFar else DownloadResult.SIZE_UNKNOWN.toLong()
                        }
                    }
                    response.status == HttpStatusCode.OK -> {
                        destFile.delete()
                        request.soFar = 0
                        request.size = response.contentLength() ?: DownloadResult.SIZE_UNKNOWN.toLong()
                    }
                    response.status == HttpStatusCode.NoContent -> {
                        callOnFailByResponseCode(response)
                        return@execute
                    }
                    !response.status.isSuccess() -> {
                        callOnFailByResponseCode(response)
                        return@execute
                    }
                    else -> throw IOException("Unexpected HTTP status ${response.status}")
                }
                checkIfRedirect(response)

                request.statusMsg = (R.string.download_running)
                Logd(TAG) { "Getting size of download" }
                val contentLength = response.contentLength()
                request.size = if (contentLength != null)  contentLength + request.soFar else -1L
                Logd(TAG) { "downloadRequest size is " + request.size }
                if (request.size < 0) request.size = DownloadResult.SIZE_UNKNOWN.toLong()

                response.bodyAsChannel().asSource().buffered().use { source -> cb(source) }

                if (cancelled) onCancelled()
                else {
                    val lastModified = response.headers[HttpHeaders.LastModified]
                    if (lastModified != null) request.lastModified = lastModified
                    else request.lastModified = response.headers[HttpHeaders.ETag]
                    result.setSuccessful()
                }
            }
        } catch (e: IllegalArgumentException) { onFail(DownloadError.ERROR_MALFORMED_URL, e.message)
        } catch (e: SocketTimeoutException) { onFail(DownloadError.ERROR_CONNECTION_ERROR, e.message)
        } catch (e: UnresolvedAddressException) { onFail(DownloadError.ERROR_UNKNOWN_HOST, e.message)
        } catch (e: IOException) {
            if (wasDownloadBlocked(e)) {
                onFail(DownloadError.ERROR_IO_BLOCKED, e.message)
                return
            }
            val message = e.message
            if (message != null && message.contains("Trust anchor for certification path not found")) {
                onFail(DownloadError.ERROR_CERTIFICATE, e.message)
                return
            }
            onFail(DownloadError.ERROR_IO_ERROR, e.message)
        } catch (e: NullPointerException) { onFail(DownloadError.ERROR_CONNECTION_ERROR, request.source)
        } catch (e: Throwable) { onFail(DownloadError.ERROR_NOT_FOUND, e.message)
        } finally { }
    }

    companion object {
        private val TAG: String = FeedDownloader::class.simpleName ?: "Anonymous"
    }

}

class EpisodeDownloader(request: DownloadRequest): Downloader(request) {
    override suspend fun download() {
        withContext(downloadDispatcher) {
            val source = request.source ?: return@withContext
            val destFile = request.destination.toUF()
            request.soFar = destFile.size() ?: 0L
            request.size = DownloadResult.SIZE_UNKNOWN.toLong()
            downloadStatesFlow.update { it + (source to DownloadStatus(DownloadStatus.State.RUNNING.code, 0)) }
            try {
                val uri = getURIFromRequestUrl(source)
                getKtorClient().prepareGet(uri.toString()) {
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = 30_000
                    }
                    attributes.put(CredentialsKey, request)
                    header(HttpHeaders.CacheControl, "no-store")
                    header(HttpHeaders.AcceptEncoding, "identity")
                    if (uri.scheme == "http") header("Upgrade-Insecure-Requests", "1")
                    if (request.soFar > 0) header(HttpHeaders.Range, "bytes=${request.soFar}-")
                }.execute { response ->
                    // A rejected range is not proof that the local file is complete.
                    if (response.status != HttpStatusCode.OK && response.status != HttpStatusCode.PartialContent) {
                        callOnFailByResponseCode(response)
                        return@execute
                    }
                    val contentType = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
                    if (contentType?.startsWith("text/", ignoreCase = true) == true ||
                        contentType.equals("application/json", ignoreCase = true)) {
                        onFail(DownloadError.ERROR_FILE_TYPE, null)
                        return@execute
                    }
                    val encoded = response.headers[HttpHeaders.ContentEncoding]
                        ?.let { it.isNotBlank() && !it.equals("identity", ignoreCase = true) } ?: false
                    var append = false
                    var expectedResponseSize = if (encoded) null else response.contentLength()?.takeIf { it >= 0 }
                    if (response.status == HttpStatusCode.PartialContent) {
                        val range = episodeTransferRange(response.headers[HttpHeaders.ContentRange], request.soFar, response.contentLength())
                        if (range == null || encoded) {
                            // Discard this response; the worker retries with a fresh GET.
                            onFail(DownloadError.ERROR_HTTP_DATA_ERROR, "416")
                            return@execute
                        }
                        append = range.start > 0
                        request.size = range.total
                        expectedResponseSize = range.byteCount
                    } else {
                        // A server may ignore Range. Replace the old bytes rather than appending.
                        request.soFar = 0L
                        request.size = if (encoded) DownloadResult.SIZE_UNKNOWN.toLong()
                            else response.contentLength()?.takeIf { it >= 0 } ?: DownloadResult.SIZE_UNKNOWN.toLong()
                    }
                    checkIfRedirect(response)
                    request.ensureMediaFileExists()
                    request.statusMsg = R.string.download_running
                    if (appPrefsFlow!!.value.checkAvailableSpace && request.size > 0 &&
                        request.size - request.soFar > freeSpaceAvailable) {
                        onFail(DownloadError.ERROR_NOT_ENOUGH_SPACE, null)
                        return@execute
                    }
                    var lastProgress = -1
                    val initialSize = request.soFar
                    destFile.sink(append).buffer().use { out ->
                        copyEpisodeBody(response.bodyAsChannel(), out) { read ->
                            if (cancelled) throw kotlinx.coroutines.CancellationException("Download cancelled")
                            request.soFar += read
                            request.progressPercent = episodeDownloadProgress(request.soFar, request.size)
                            if (request.progressPercent != lastProgress) {
                                lastProgress = request.progressPercent
                                downloadStatesFlow.update { it + (source to DownloadStatus(DownloadStatus.State.RUNNING.code, lastProgress)) }
                            }
                        }
                    }
                    when {
                        cancelled -> onCancelled()
                        request.soFar == 0L -> onFail(DownloadError.ERROR_IO_ERROR, "The response contained no media bytes")
                        expectedResponseSize != null && request.soFar - initialSize != expectedResponseSize ->
                            onFail(DownloadError.ERROR_IO_WRONG_SIZE, "Received ${request.soFar - initialSize} response bytes; expected $expectedResponseSize")
                        request.size >= 0 && request.soFar != request.size ->
                            onFail(DownloadError.ERROR_IO_WRONG_SIZE, "Received ${request.soFar} bytes; expected ${request.size}")
                        else -> {
                            request.lastModified = response.headers[HttpHeaders.LastModified] ?: response.headers[HttpHeaders.ETag]
                            request.progressPercent = 100
                            result.setSuccessful()
                            downloadStatesFlow.update { it + (source to DownloadStatus(DownloadStatus.State.COMPLETED.code, 100)) }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                onCancelled()
                throw e
            } catch (e: IllegalArgumentException) {
                onFail(DownloadError.ERROR_MALFORMED_URL, e.message)
            } catch (e: SocketTimeoutException) {
                onFail(DownloadError.ERROR_CONNECTION_ERROR, e.message)
            } catch (e: UnresolvedAddressException) {
                onFail(DownloadError.ERROR_UNKNOWN_HOST, e.message)
            } catch (e: IOException) {
                val reason = when {
                    wasDownloadBlocked(e) -> DownloadError.ERROR_IO_BLOCKED
                    e.message?.contains("Trust anchor for certification path not found") == true -> DownloadError.ERROR_CERTIFICATE
                    else -> DownloadError.ERROR_IO_ERROR
                }
                onFail(reason, e.message)
            } catch (e: Exception) {
                // Ktor can report a truncated response as an IllegalStateException.
                val reason = if (e.message?.contains("Content-Length mismatch", ignoreCase = true) == true ||
                    e.message?.contains("invalid content-length", ignoreCase = true) == true) DownloadError.ERROR_IO_WRONG_SIZE
                    else DownloadError.ERROR_IO_ERROR
                onFail(reason, e.message)
            } finally {
                if (!result.isSuccessful) downloadStatesFlow.update {
                    it + (source to DownloadStatus(DownloadStatus.State.INCOMPLETE.code, request.progressPercent))
                }
            }
        }
    }

    companion object {
        private val TAG: String = EpisodeDownloader::class.simpleName ?: "Anonymous"
    }
}
