package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.specs.Rating
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.catch
import ac.mdiq.podcini.storage.tags.MediaTagRepository
import ac.mdiq.podcini.storage.tags.tagSnapshot
import ac.mdiq.podcini.storage.tags.recordTagUndo
import ac.mdiq.podcini.storage.tags.remapTagFilterReferences

const val UNKNOWN_LIBRARY_CREATOR = "__podcini_unknown_creator__"

data class LibraryCreator(val name: String, val roles: Set<String>, val mediaCount: Int, val podcastCount: Int)
data class LibraryAlbum(val key: String, val title: String, val artist: String, val image: String?, val itemIds: Set<Long>)
data class LibraryCatalogue(
    val media: List<Episode> = emptyList(), val feeds: List<Feed> = emptyList(), val playlists: List<PlayQueue> = emptyList(),
    val facets: Map<Long, LibraryMedia> = emptyMap(), val creators: List<LibraryCreator> = emptyList(),
    val albums: List<LibraryAlbum> = emptyList(), val tags: List<String> = emptyList(), val loaded: Boolean = false,
    val failed: Boolean = false, val tagCounts: Map<String, Int> = emptyMap(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun libraryCatalogueFlow() = combine(realm.query(Episode::class).asFlow(), realm.query(Feed::class, "id > 1000").asFlow(),
    realm.query(PlayQueue::class, "id != $0", LISTENING_QUEUE_ID).asFlow(), appPrefsFlow!!) { media, feeds, lists, prefs ->
    Triple(Triple(media.list.toList(), feeds.list.toList(), lists.list.toList()), prefs.completionPercent,
        decodeBrowsePreferences(prefs.libraryBrowsePreferences).emptyTags)
}.mapLatest { (data, completion, tags) -> withContext(Dispatchers.Default) {
    libraryCatalogue(data.first, data.second, data.third, completion, tags)
} }.catch { emit(LibraryCatalogue(loaded = true, failed = true)) }

fun Episode.browseMedia(completion: Int, source: Feed? = feed): LibraryMedia = LibraryMedia(
    id, title.orEmpty(), libraryKind, (listOf(artist, albumArtist, source?.author.orEmpty()).filter { it.isNotBlank() }.toSet())
        .ifEmpty { setOf(UNKNOWN_LIBRARY_CREATOR) },
    libraryAlbumKey(album, albumArtist, artist), tags.toSet() + source?.tags.orEmpty(), feedId ?: 0,
    !fileUrl.isNullOrBlank(), !isPlaybackFinished(completion), rating >= Rating.GOOD.code, duration, album,
)

fun libraryCatalogue(media: List<Episode>, feeds: List<Feed>, playlists: List<PlayQueue>, completion: Int,
                     emptyTags: List<String> = emptyList()): LibraryCatalogue {
    val sources = feeds.associateBy { it.id }
    val facets = media.associate { it.id to it.browseMedia(completion, sources[it.feedId]) }
    data class CreatorAccumulator(val name: String, val roles: MutableSet<String> = mutableSetOf(), var media: Int = 0, var podcasts: Int = 0)
    val byCreator = linkedMapOf<String, CreatorAccumulator>()
    feeds.filter { !it.isLocal }.forEach { feed -> feed.author?.takeIf { it.isNotBlank() }?.let { name ->
        val creator = byCreator.getOrPut(libraryKey(name)) { CreatorAccumulator(name) }
        creator.podcasts++; creator.roles.add("publisher")
    } }
    facets.values.forEach { item -> item.creators.distinctBy(::libraryKey).forEach { name ->
        val creator = byCreator.getOrPut(libraryKey(name)) { CreatorAccumulator(name) }
        creator.media++
        if (item.kind == "music") creator.roles.add("artist") else if (creator.podcasts == 0) creator.roles.add("author")
    } }
    val creators = byCreator.values.map { LibraryCreator(it.name, it.roles, it.media, it.podcasts) }.sortedBy { libraryKey(it.name) }
    val albums = media.filter { it.album.isNotBlank() }.groupBy { libraryAlbumKey(it.album, it.albumArtist, it.artist) }.map { (key, tracks) ->
        val item = tracks.first()
        LibraryAlbum(key, item.album, item.albumArtist.ifBlank { item.artist },
            tracks.firstNotNullOfOrNull { it.images.firstOrNull()?.href } ?: sources[item.feedId]?.images?.firstOrNull()?.href,
            tracks.map { it.id }.toSet())
    }.sortedBy { libraryKey(it.title) }
    return LibraryCatalogue(media, feeds, playlists, facets, creators, albums,
        (media.flatMap { it.tags } + feeds.flatMap { it.tags } + playlists.flatMap { it.tags } + emptyTags + TagKind.entries.map { it.root })
            .flatMap(::tagParents).distinctBy(::libraryKey).sortedWith(String.CASE_INSENSITIVE_ORDER), true, tagCounts = countLibraryTags(facets.values))
}

suspend fun updateLibraryPreferences(change: (LibraryBrowsePreferences) -> LibraryBrowsePreferences) {
    realm.write {
        val prefs = query(AppPrefs::class).first().find() ?: return@write
        prefs.libraryBrowsePreferences = libraryJson.encodeToString(change(decodeBrowsePreferences(prefs.libraryBrowsePreferences)))
    }
}

/** Rename/move/merge the entire branch atomically, retaining IDs, media and annotations. */
suspend fun editLibraryTag(from: String, to: String) {
    require(TagPath.parse(from) != null)
    require(to.isBlank() || (TagPath.parse(to) != null && validTagDestination(from, to)))
    fun mapped(values: Collection<String>) = values.mapNotNull { remapLibraryTag(it, from, to) }.toSet()
    fun filter(value: LibraryFilter): LibraryFilter = if (to.isBlank()) value else value.copy(
        scopeTag = remapLibraryTag(value.scopeTag, from, to).orEmpty(),
        tags = mapped(value.tags).toList(), excludedTags = mapped(value.excludedTags).toList())
    MediaTagRepository.serialized {
    realm.write {
        val before = tagSnapshot()
        query(Episode::class).find().forEach { item -> if (item.tags.any { tagMatches(it, from) }) { MediaTagRepository.queue(this, item, mapped(item.tags)) } }
        query(Feed::class).find().forEach { feed -> if (feed.tags.any { tagMatches(it, from) }) { val tags = mapped(feed.tags); feed.tags.clear(); feed.tags.addAll(tags) } }
        query(PlayQueue::class).find().forEach { playlist ->
            if (playlist.tags.any { tagMatches(it, from) }) { val tags = mapped(playlist.tags); playlist.tags.clear(); playlist.tags.addAll(tags) }
            if (to.isNotBlank()) {
                playlist.ruleTag = remapLibraryTag(playlist.ruleTag, from, to).orEmpty()
                decodeLibraryFilter(playlist.ruleBrowseFilter)?.let { playlist.ruleBrowseFilter = filter(it).encode() }
            }
        }
        query(AppPrefs::class).first().find()?.let { prefs ->
            val saved = decodeBrowsePreferences(prefs.libraryBrowsePreferences)
            prefs.libraryBrowsePreferences = libraryJson.encodeToString(saved.copy(
                emptyTags = mapped(saved.emptyTags).toList(),
                pins = saved.pins.map { pin -> if (to.isBlank()) pin else pin.copy(destination = pin.destination.copy(
                    tag = remapLibraryTag(pin.destination.tag, from, to).orEmpty(), filter = filter(pin.destination.filter))) }))
        }
        remapTagFilterReferences(from, to)
        recordTagUndo(before)
    }
    }
    MediaTagRepository.schedule()
}
