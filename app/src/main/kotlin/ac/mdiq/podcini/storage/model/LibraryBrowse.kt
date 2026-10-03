package ac.mdiq.podcini.storage.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

val libraryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun libraryKey(value: String): String = value.trim().lowercase(Locale.ROOT)

/** A metadata projection keeps browsing rules independent of Realm and Android. */
data class LibraryMedia(
    val id: Long, val title: String = "", val kind: String = "audio", val creators: Set<String> = emptySet(),
    val albumKey: String = "", val tags: Set<String> = emptySet(), val feedId: Long = 0,
    val downloaded: Boolean = false, val unfinished: Boolean = true, val favourite: Boolean = false,
    val durationMs: Int = 0, val albumTitle: String = "",
)

@Serializable
data class LibraryFilter(
    val text: String = "", val kind: String = "all", val unfinished: Boolean = false,
    val downloaded: Boolean = false, val favourite: Boolean = false, val maxMinutes: Int = 0,
    val tags: List<String> = emptyList(), val allTags: Boolean = false, val excludedTags: List<String> = emptyList(),
    val descendants: Boolean = true, val creators: List<String> = emptyList(),
    val scopeTag: String = "", val scopeCreator: String = "", val scopeAlbum: String = "", val scopeFeed: Long = 0,
) {
    fun matches(item: LibraryMedia): Boolean {
        fun hasTag(tag: String) = item.tags.any { tagMatches(it, tag, descendants) }
        fun hasCreator(name: String) = item.creators.any { libraryKey(it) == libraryKey(name) }
        return (kind == "all" || item.kind == kind) && (!unfinished || item.unfinished) &&
            (!downloaded || item.downloaded) && (!favourite || item.favourite) &&
            (maxMinutes <= 0 || (item.durationMs > 0 && item.durationMs.toLong() <= maxMinutes * 60_000L)) &&
            (scopeTag.isBlank() || hasTag(scopeTag)) && (scopeCreator.isBlank() || hasCreator(scopeCreator)) &&
            (scopeAlbum.isBlank() || item.albumKey == scopeAlbum) && (scopeFeed == 0L || item.feedId == scopeFeed) &&
            (tags.isEmpty() || if (allTags) tags.all(::hasTag) else tags.any(::hasTag)) &&
            excludedTags.none(::hasTag) && (creators.isEmpty() || creators.any(::hasCreator)) &&
            (text.isBlank() || (listOf(item.title, item.albumTitle) + item.creators + item.tags).any { it.contains(text.trim(), true) })
    }
    fun encode(): String = libraryJson.encodeToString(this)
    fun withoutScope() = copy(scopeTag = "", scopeCreator = "", scopeAlbum = "", scopeFeed = 0)
    val isActive: Boolean get() = this != LibraryFilter()
}

@Serializable
data class LibraryDestination(
    val section: String = "home", val tag: String = "", val creator: String = "", val album: String = "",
    val title: String = "", val feedId: Long = 0, val filter: LibraryFilter = LibraryFilter(),
    val sort: Int = 2, val grid: Boolean = false, val group: String = "items",
) {
    fun query() = filter.copy(scopeTag = tag, scopeCreator = creator, scopeAlbum = album, scopeFeed = feedId)
    fun encode(): String = libraryJson.encodeToString(this)
}

@Serializable
data class LibraryPin(val id: String, val name: String, val destination: LibraryDestination)

@Serializable
data class LibraryBrowsePreferences(
    val start: String = "home", val pins: List<LibraryPin> = emptyList(), val emptyTags: List<String> = emptyList(),
    val homeCards: Boolean = true,
)

fun decodeBrowsePreferences(value: String): LibraryBrowsePreferences =
    runCatching { libraryJson.decodeFromString<LibraryBrowsePreferences>(value) }.getOrDefault(LibraryBrowsePreferences())

fun decodeLibraryFilter(value: String): LibraryFilter? =
    runCatching { libraryJson.decodeFromString<LibraryFilter>(value) }.getOrNull()

fun libraryAlbumKey(title: String, albumArtist: String, artist: String): String =
    if (title.isBlank()) "" else libraryJson.encodeToString(listOf(libraryKey(title), libraryKey(albumArtist.ifBlank { artist })))

/** Only direct children are presented; implicit ancestors remain navigable. */
fun libraryTagChildren(tags: Collection<String>, parent: String): List<String> = tags.flatMap(::tagParents)
    .filter { path -> libraryKey(encodeTagSegments(decodeTagSegments(path).orEmpty().dropLast(1))) == libraryKey(parent) }
    .distinctBy(::libraryKey).sortedWith(String.CASE_INSENSITIVE_ORDER)

/** Count each item once per destination, with the same subtree rule as its query. */
fun countLibraryTags(items: Collection<LibraryMedia>, descendants: Boolean = true): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    items.forEach { item ->
        val paths = if (descendants) item.tags.flatMap(::tagParents) else item.tags
        paths.map(::libraryKey).toSet().forEach { key -> counts[key] = (counts[key] ?: 0) + 1 }
    }
    return counts
}

/** Rename/move keeps descendant suffixes and respects path boundaries. */
fun remapLibraryTag(value: String, from: String, to: String): String? =
    if (!tagMatches(value, from)) value else if (to.isBlank()) null else
        encodeTagSegments(decodeTagSegments(to).orEmpty() + decodeTagSegments(value).orEmpty().drop(decodeTagSegments(from).orEmpty().size))

fun validTagDestination(from: String, to: String): Boolean = to.isNotBlank() &&
    decodeTagSegments(to) != null && !tagMatches(to, from)
