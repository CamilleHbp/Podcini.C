package ac.mdiq.podcini.sourcing.searcher

import java.net.URI
import java.util.Locale

/** Compare URL aliases without conflating different shows from the same publisher. */
fun podcastUrlKey(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return runCatching {
        val uri = URI(url.trim())
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching url.trim()
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return@runCatching url.trim()
        val port = if (uri.port == -1 || (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        // Retain path case, credentials and query: these can identify different/private feeds.
        val user = uri.rawUserInfo?.let { "$it@" }.orEmpty()
        "$user$host$port$path" + (uri.rawQuery?.let { "?$it" } ?: "")
    }.getOrElse { url.trim() }
}

enum class SearchScope { All, Podcasts, Episodes, Playlists }

fun shouldSearchPodcasts(query: String, scope: SearchScope, libraryOnly: Boolean): Boolean =
    query.trim().length >= 2 && !libraryOnly && scope in setOf(SearchScope.All, SearchScope.Podcasts) &&
        !query.trim().startsWith("http://", true) && !query.trim().startsWith("https://", true)

/** Every word must match; ordinary spaces never turn into the legacy comma query syntax. */
fun searchWords(query: String): List<String> = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(12)

fun matchesSearch(query: String, vararg fields: String?): Boolean {
    val text = fields.filterNotNull().joinToString(" ")
    return searchWords(query).all { text.contains(it, ignoreCase = true) }
}
