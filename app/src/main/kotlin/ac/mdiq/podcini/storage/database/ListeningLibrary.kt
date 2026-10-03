package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.actQueueFlow
import ac.mdiq.podcini.playback.theatres
import ac.mdiq.podcini.shared.getEntityId
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.reorderWith
import ac.mdiq.podcini.storage.specs.MediaType
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.utils.localizedString
import io.github.xilinjia.krdb.MutableRealm
import io.github.xilinjia.krdb.Realm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// Retain table names and IDs for imports/backups. Only this row is ever a playback session.
const val LISTENING_QUEUE_ID = VIRTUAL_QUEUE_ID
const val FAVOURITES_PLAYLIST_ID = -11L

val Episode.libraryKind: String
    get() = contentKind.ifBlank { if (feed?.audioTypeSetting == Feed.AudioType.MUSIC) "music" else if (mediaType == MediaType.VIDEO) "video" else if (feed?.isLocal == true) "audio" else "podcast" }

val Episode.libraryTags: Set<String>
    get() = tags.toSet() + feed?.tags.orEmpty()

val Episode.playableLocations: List<String>
    get() = (listOfNotNull(fileUrl, downloadUrl) + additionalLocations).filter { it.isNotBlank() }.distinct()

private val browseRuleCache = java.util.concurrent.ConcurrentHashMap<String, LibraryFilter>()
private fun matchesBrowseRule(encoded: String, item: Episode, completion: Int): Boolean {
    if (encoded.isBlank()) return true
    val rule = browseRuleCache[encoded] ?: decodeLibraryFilter(encoded)?.also {
        if (browseRuleCache.size > 128) browseRuleCache.clear()
        browseRuleCache[encoded] = it
    } ?: return false
    return rule.matches(item.browseMedia(completion))
}

fun PlayQueue.matches(item: Episode, completionPercent: Int): Boolean =
    matchesBrowseRule(ruleBrowseFilter, item, completionPercent) &&
    (ruleKind == "all" || item.libraryKind == ruleKind) &&
        (!ruleUnfinished || !item.isPlaybackFinished(completionPercent)) &&
        (!ruleFavourite || item.rating >= Rating.GOOD.code) &&
        (!ruleDownloaded || !item.fileUrl.isNullOrBlank()) &&
        (ruleFeedIds.isEmpty() || item.feedId in ruleFeedIds) &&
        (ruleTag.isBlank() || item.libraryTags.any { tagMatches(it, ruleTag, ruleTagDescendants) }) &&
        (ruleArtist.isBlank() || item.artist == ruleArtist) &&
        (ruleText.isBlank() || listOf(item.title.orEmpty(), item.artist, item.album).any { it.contains(ruleText, true) })

fun playlistItems(playlist: PlayQueue): List<Episode> = if (!playlist.smart) playlist.episodesSorted else
    realm.query(Episode::class).find().filter { playlist.matches(it, appPrefsFlow?.value?.completionPercent ?: 97) }.toMutableList().apply { if (playlist.sortOrder == ac.mdiq.podcini.storage.specs.EpisodeSortOrder.TRACK_NUMBER_ASC && decodeLibraryFilter(playlist.ruleBrowseFilter)?.scopeAlbum?.isNotBlank() == true) sortWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title })) else reorderWith(playlist.sortOrder) }

fun playlistItemsFlow(id: Long) = combine(
    realm.query(PlayQueue::class, "id == $0", id).asFlow(),
    realm.query(QueueEntry::class, "queueId == $0 SORT(position ASC)", id).asFlow(),
    realm.query(Episode::class).asFlow(),
    realm.query(Feed::class).asFlow()
) { lists, entries, media, _ ->
    val playlist = lists.list.firstOrNull()
    when {
        playlist == null -> emptyList()
        playlist.smart -> media.list.filter { playlist.matches(it, appPrefsFlow?.value?.completionPercent ?: 97) }.toMutableList().apply { if (playlist.sortOrder == ac.mdiq.podcini.storage.specs.EpisodeSortOrder.TRACK_NUMBER_ASC && decodeLibraryFilter(playlist.ruleBrowseFilter)?.scopeAlbum?.isNotBlank() == true) sortWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title })) else reorderWith(playlist.sortOrder) }
        else -> { val indexed = media.list.associateBy { it.id }; entries.list.mapNotNull { indexed[it.episodeId] } }
    }
}

/** One atomic, repeatable migration. No files, media records, notes, or old connections are deleted. */
fun migrateListeningLibrary(database: Realm = realm) {
    database.writeBlocking {
        val existingSession = query(PlayQueue::class, "id == $0", LISTENING_QUEUE_ID).first().find()
        if (existingSession?.libraryModelVersion == 1) return@writeBlocking
        val queues = query(PlayQueue::class).find().toList()
        val currentId = query(CurrentState::class, "id == 0").first().find()?.curMediaId
        val originalEntries = query(QueueEntry::class, "queueId == $0 SORT(position ASC)", LISTENING_QUEUE_ID).find().toList()
        originalEntries.forEach { it.originPlaylistId = -1L }
        val sourceQueue = queues.firstOrNull { q -> q.id != LISTENING_QUEUE_ID && query(QueueEntry::class, "queueId == $0 AND episodeId == $1", q.id, currentId ?: -1L).count().find() > 0 }
            ?: queues.firstOrNull { it.id == 0L }
        val session = existingSession ?: copyToRealm(PlayQueue().apply { id = LISTENING_QUEUE_ID })
        session.name = "Listening queue"
        session.libraryModelVersion = 1
        session.isLocked = false
        session.repeatQueue = false
        session.playInSequence = true
        session.autoSort = false
        if ((originalEntries.isEmpty() || (currentId != null && currentId > 0L && originalEntries.none { it.episodeId == currentId })) && sourceQueue != null) {
            val entries = query(QueueEntry::class, "queueId == $0 SORT(position ASC)", sourceQueue.id).find().map { it.episodeId }
            if (entries.isNotEmpty()) {
                delete(query(QueueEntry::class, "queueId == $0", LISTENING_QUEUE_ID).find())
                writeSessionEntries(entries, entries.map { sourceQueue.id })
                session.originName = sourceQueue.name
            }
        }
        queues.filter { it.id != LISTENING_QUEUE_ID }.forEach { q ->
            q.removeWhenFinished = true
            q.autoSort = false
            q.isLocked = false
            q.enqueueLocation = ac.mdiq.podcini.storage.specs.EnqueueLocation.BACK.code
            // Empty factory queues are scaffolding, not saved user content.
            if (q.id in 1L..4L && q.name == "Queue ${q.id}" && query(QueueEntry::class, "queueId == $0", q.id).count().find() == 0L &&
                query(Feed::class, "queueId == $0", q.id).count().find() == 0L) delete(q)
        }
        if (query(PlayQueue::class, "id == 0").first().find() == null) copyToRealm(PlayQueue().apply { id = 0; name = "Listen later"; removeWhenFinished = true })
        if (query(PlayQueue::class, "id == $0", FAVOURITES_PLAYLIST_ID).first().find() == null) copyToRealm(PlayQueue().apply {
            id = FAVOURITES_PLAYLIST_ID; name = "Favourites"; smart = true; ruleFavourite = true
        })
        val volumes = query(Volume::class).find().associateBy { it.id }
        query(Feed::class).find().forEach { feed ->
            if (feed.queueId == LISTENING_QUEUE_ID) feed.queue = query(PlayQueue::class, "id == 0").first().find()
            val path = mutableListOf<String>()
            val visited = mutableSetOf<Long>()
            var volume = volumes[feed.volumeId]
            while (volume != null && volume.id >= 0 && visited.add(volume.id)) {
                if (volume.name.isNotBlank()) path.add(0, volume.name.replace('/', '-'))
                volume = volumes[volume.parentId]
            }
            if (path.isNotEmpty()) feed.tags.add(if (query(AppPrefs::class).first().find()?.tagPathsMigrated == true)
                TagPath(TagKind.CUSTOM, path).path else path.joinToString("/"))
            if (feed.id in 100L..1000L) {
                val playlist = copyToRealm(PlayQueue().apply {
                    id = getEntityId(); name = feed.title.orEmpty(); identity = "collection:${feed.id}"; tags.addAll(feed.tags)
                })
                val items = query(Episode::class, "feedId == $0", feed.id).find().toMutableList().apply { reorderWith(feed.episodeSortOrder) }
                items.forEachIndexed { i, item -> copyToRealm(QueueEntry().apply { id = getEntityId(); queueId = playlist.id; episodeId = item.id; position = (i + 1L) * QUEUE_POSITION_DELTA }) }
            }
        }
        query(AppPrefs::class).find().forEach { it.twoPlayers = false }
    }
}

internal fun MutableRealm.writeSessionEntries(ids: List<Long>, origins: List<Long> = emptyList()) {
    ids.distinct().forEachIndexed { index, itemId ->
        if (query(Episode::class, "id == $0", itemId).first().find() != null) copyToRealm(QueueEntry().apply {
            id = getEntityId(); queueId = LISTENING_QUEUE_ID; episodeId = itemId
            position = (index + 1L) * QUEUE_POSITION_DELTA; originPlaylistId = origins.getOrElse(index) { -1L }
        })
    }
}

/** Snapshot visible order; browsing and editing saved playlists cannot mutate this sequence. */
suspend fun replaceListeningQueue(items: List<Episode>, startId: Long, origin: String, playlistId: Long = -1L, continuous: Boolean = true) {
    val index = items.indexOfFirst { it.id == startId }
    if (index < 0) return
    val chosen = items.drop(index).distinctBy { it.id }
    // Player access must stay on the main thread, outside Realm's writer dispatcher.
    val (currentId, currentPosition) = withContext(Dispatchers.Main.immediate) {
        val player = theatres[0].mPlayerFlow.value
        (player?.curMediaFlow?.value?.id ?: -1L) to (player?.getPosition()?.coerceAtLeast(0) ?: 0)
    }
    realm.write { snapshotListeningSession(chosen, origin, playlistId, continuous, currentId, currentPosition) }
    curIndexInActQueue = -1
    refreshListeningQueue()
}

internal fun MutableRealm.snapshotListeningSession(chosen: List<Episode>, origin: String, playlistId: Long, continuous: Boolean, currentId: Long, currentPosition: Int) {
        val session = query(PlayQueue::class, "id == $0", LISTENING_QUEUE_ID).first().find() ?: return
        val entries = query(QueueEntry::class, "queueId == $0 SORT(position ASC)", LISTENING_QUEUE_ID).find()
        session.previousIds.clear(); session.previousIds.addAll(entries.map { it.episodeId })
        session.previousOrigins.clear(); session.previousOrigins.addAll(entries.map { it.originPlaylistId })
        session.previousName = session.originName
        session.previousCurrentId = currentId
        session.previousPosition = currentPosition
        session.previousRepeat = session.repeatQueue; session.previousContinuous = session.playInSequence
        session.undoAvailable = entries.isNotEmpty()
        delete(entries)
        // Remote previews join the same library identity without following their podcast.
        chosen.forEach { item -> if (query(Episode::class, "id == $0", item.id).first().find() == null) copyToRealm(item) }
        writeSessionEntries(chosen.map { it.id }, chosen.map { playlistId })
        session.originName = origin; session.identity = "session"; session.playInSequence = continuous
        session.repeatQueue = false; session.unshuffledIds.clear(); session.update()
    }

fun refreshListeningQueue() { realm.query(PlayQueue::class, "id == $0", LISTENING_QUEUE_ID).first().find()?.let { actQueueFlow.value = it } }

suspend fun undoListeningQueue(): Episode? {
    var restoreId = -1L
    realm.write {
        val session = query(PlayQueue::class, "id == $0", LISTENING_QUEUE_ID).first().find() ?: return@write
        if (!session.undoAvailable) return@write
        delete(query(QueueEntry::class, "queueId == $0", LISTENING_QUEUE_ID).find())
        writeSessionEntries(session.previousIds.toList(), session.previousOrigins.toList())
        restoreId = session.previousCurrentId.takeIf { it in session.previousIds } ?: session.previousIds.firstOrNull() ?: -1
        query(Episode::class, "id == $0", restoreId).first().find()?.let { if (restoreId == session.previousCurrentId) it.position = session.previousPosition }
        session.originName = session.previousName; session.repeatQueue = session.previousRepeat; session.playInSequence = session.previousContinuous
        session.undoAvailable = false; session.unshuffledIds.clear(); session.update()
    }
    refreshListeningQueue()
    return episodeById(restoreId)
}

suspend fun createPlaylist(name: String, items: List<Episode> = emptyList(), rules: PlayQueue? = null): PlayQueue {
    val playlist = upsert(PlayQueue().apply {
        id = getEntityId(); this.name = name.trim()
        rules?.let { smart = true; ruleKind = it.ruleKind; ruleUnfinished = it.ruleUnfinished; ruleFavourite = it.ruleFavourite
            ruleDownloaded = it.ruleDownloaded; ruleTag = it.ruleTag; ruleTagDescendants = it.ruleTagDescendants
            ruleText = it.ruleText; ruleBrowseFilter = it.ruleBrowseFilter; ruleArtist = it.ruleArtist; ruleFeedIds.addAll(it.ruleFeedIds); sortOrder = it.sortOrder }
    }) {}
    if (!playlist.smart) addToQueue(items, playlist)
    return playlist
}

suspend fun completePlaylistItem(itemId: Long, originPlaylistId: Long, database: Realm = realm) {
    if (originPlaylistId == -1L || originPlaylistId == LISTENING_QUEUE_ID) return
    database.write {
        val playlist = query(PlayQueue::class, "id == $0", originPlaylistId).first().find() ?: return@write
        if (!playlist.smart && playlist.removeWhenFinished) {
            delete(query(QueueEntry::class, "queueId == $0 AND episodeId == $1", playlist.id, itemId).find())
            playlist.update()
        }
    }
}

suspend fun shuffleListeningQueue() {
    val current = theatres[0].mPlayerFlow.value?.curMediaFlow?.value?.id
    realm.write {
        val session = query(PlayQueue::class, "id == $0", LISTENING_QUEUE_ID).first().find() ?: return@write
        val entries = query(QueueEntry::class, "queueId == $0 SORT(position ASC)", LISTENING_QUEUE_ID).find()
        val ids = entries.map { it.episodeId }
        val order = if (session.unshuffledIds.isEmpty()) {
            session.unshuffledIds.addAll(ids)
            val split = (ids.indexOf(current) + 1).coerceAtLeast(0)
            ids.take(split) + ids.drop(split).shuffled()
        } else { val saved = session.unshuffledIds.toList(); session.unshuffledIds.clear(); saved.filter { it in ids } + ids.filter { it !in saved } }
        val map = entries.associateBy { it.episodeId }
        order.forEachIndexed { i, id -> map[id]?.position = (i + 1L) * QUEUE_POSITION_DELTA }
        session.update()
    }
    refreshListeningQueue()
}

/** File permissions can be revoked and downloaded files can be removed outside the app. */
val Episode.availableLocalLocation: String?
    get() = playableLocations.firstOrNull { !it.startsWith("http://") && !it.startsWith("https://") && runCatching {
        val uri = android.net.Uri.parse(it)
        if (uri.scheme == "content") ac.mdiq.podcini.PodciniApp.getAppContext().contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        else java.io.File(if (uri.scheme == "file") uri.path.orEmpty() else it).isFile
    }.getOrDefault(false) }

val Episode.availableRemoteLocation: String?
    get() = playableLocations.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
