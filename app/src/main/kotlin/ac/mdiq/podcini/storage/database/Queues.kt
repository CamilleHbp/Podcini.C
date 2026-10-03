package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.R
import ac.mdiq.podcini.utils.localizedString
import ac.mdiq.podcini.playback.actQueueFlow
import ac.mdiq.podcini.playback.theatres
import ac.mdiq.podcini.shared.getEntityId
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.PlayQueue
import ac.mdiq.podcini.storage.model.QueueEntry
import ac.mdiq.podcini.storage.model.VIRTUAL_QUEUE_ID
import ac.mdiq.podcini.storage.specs.EnqueueLocation
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent.QueueEvent
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.timeIt
import io.github.xilinjia.krdb.notifications.ResultsChange
import io.github.xilinjia.krdb.notifications.UpdatedResults
import kotlinx.coroutines.Job
import kotlin.math.min
import kotlin.random.Random

private const val TAG: String = "Queues"

const val QUEUE_POSITION_DELTA = 10000L

val queuesFlow = realm.query(PlayQueue::class).sort("name").asFlow()
var queuesLive = listOf<PlayQueue>()
    private set
private var virQueue = PlayQueue()

var queuesJob: Job? = null

fun initQueues() {
    migrateListeningLibrary()
    queuesLive = realm.query(PlayQueue::class).sort("name").find()
    refreshListeningQueue()
    if (queuesJob == null) queuesJob = runOnIOScope {
        queuesFlow.collect { changes ->
            queuesLive = changes.list
            changes.list.firstOrNull { it.id == LISTENING_QUEUE_ID }?.let { actQueueFlow.value = it }
        }
    }
}

fun cancelQueuesJob() {
    queuesJob?.cancel()
}

var curIndexInActQueue = -1

fun inQueueEpisodeIdSet(): Set<Long> {
    Logd(TAG) { "getQueueIDList() called" }
    return realm.query(QueueEntry::class).find().map { it.episodeId }.toSet()
}

suspend fun persistOrdered(episodes: List<Episode>, queueEntries: List<QueueEntry>) {
    realm.write {
        for (i in episodes.indices) {
            val e = episodes[i]
            val qe = queueEntries.find { it.episodeId == e.id }
            if (qe == null) {
                Loge(TAG, localizedString(R.string.message_can_t_find_queueentry_for_episode, (e.title).toString()))
                continue
            }
            findLatest(qe)?.position = (i+1) * QUEUE_POSITION_DELTA
        }
    }
}

suspend fun addToAssQueue(episodes: List<Episode>) {
    Logd(TAG) { "addToAssQueue( ... ) called" }
    val mapByFeed = episodes.groupBy { it.feedId }
    for (en in mapByFeed.entries) {
        val fid = en.key ?: continue
        val f = feedsMap[fid] ?: continue
        val assigned = f.queue ?: continue
        val q = if (assigned.isVirtual()) realm.query(PlayQueue::class, "id == 0").first().find() ?: continue else assigned
        val episodes = en.value
        addToQueue(episodes, q)
    }
}

suspend fun addToQueue(episodes: List<Episode>, queue: PlayQueue, location: EnqueueLocation? = null) {
    Logd(TAG) { "addToQueue( ... ) called" }
    if (queue.smart) return
    val curPlaying = if (queue.id == actQueueFlow.value.id) theatres[0].mPlayerFlow.value?.curMediaFlow?.value else null
    realm.write {
        episodes.forEach { item -> if (query(Episode::class, "id == $0", item.id).first().find() == null) copyToRealm(item) }
        if (location != null) {
            val existing = query(QueueEntry::class, "queueId == $0 SORT(position ASC)", queue.id).find().toList()
            val chosen = episodes.map { it.id }.distinct()
            val ordered = existing.map { it.episodeId }.filterNot { it in chosen }.toMutableList()
            val index = when (location) {
                EnqueueLocation.FRONT -> 0
                EnqueueLocation.BACK -> ordered.size
                EnqueueLocation.AFTER_CURRENTLY_PLAYING -> (ordered.indexOf(curPlaying?.id) + 1).coerceAtLeast(0)
                EnqueueLocation.RANDOM -> Random.nextInt(ordered.size + 1)
            }
            ordered.addAll(index, chosen)
            val byEpisode = existing.associateBy { it.episodeId }
            ordered.forEachIndexed { order, id ->
                val entry = byEpisode[id] ?: copyToRealm(QueueEntry().apply {
                    this.id = getEntityId()
                    queueId = queue.id
                    episodeId = id
                })
                entry.position = (order + 1L) * QUEUE_POSITION_DELTA
            }
        } else for (e in episodes) {
            val entries = query(QueueEntry::class, "queueId == $0 SORT(position ASC)", queue.id).find()
            if (entries.any { it.episodeId == e.id }) continue
            val insertPosition = if (queue.autoSort) 0 else calcPosition(entries, EnqueueLocation.fromCode(queue.enqueueLocation), curPlaying)
            copyToRealm(QueueEntry().apply {
                id = getEntityId()
                queueId = queue.id
                episodeId = e.id
                position = insertPosition
            })
        }
    }
    val toSetStat = episodes.filter { it.playState < EpisodeState.QUEUE.code }
    if (toSetStat.isNotEmpty()) realm.write { for (e in toSetStat) findLatest(e)?.setPlayState(EpisodeState.QUEUE, false) }
    if (queue.autoSort && location == null) queue.sort()
}

suspend fun queueToVirtual(episode: Episode, episodes: List<Episode>, listIdentity: String, sortOrder: EpisodeSortOrder, playInSequence: Boolean = true) {
    val origin = episode.feed?.title ?: localizedString(R.string.library_items)
    replaceListeningQueue(episodes, episode.id, origin, continuous = playInSequence)
}

suspend fun smartRemoveFromQueues(item_: Episode, queues_: List<PlayQueue> = listOf()) {
    val targets = queues_.ifEmpty { listOf(actQueueFlow.value) }
    for (queue in targets) removeFromQueue(queue, listOf(item_))
}

suspend fun removeFromAllQueues(episodes: Collection<Episode>, playState: EpisodeState? = null) {
    // Legacy completion/download actions must not erase membership in saved playlists.
    val queue = actQueueFlow.value
    curIndexInActQueue = queue.entries.indexOfFirst { it.episodeId == theatres[0].mPlayerFlow.value?.curMediaFlow?.value?.id }
    removeFromQueue(queue, episodes, playState)
}

internal suspend fun removeFromQueue(queue_: PlayQueue?, episodes: Collection<Episode>, playState: EpisodeState? = null) {
//    Logd(TAG) { "removeFromQueue called ${queue_?.name}" }
    val queue = queue_ ?: actQueueFlow.value
    if (queue.size() == 0) {
        queue.checkAndFill()
        return
    }
    if (episodes.isEmpty()) return
    val currentId = theatres[0].mPlayerFlow.value?.curMediaFlow?.value?.id
    if (queue.id == LISTENING_QUEUE_ID && episodes.any { it.id == currentId }) curIndexInActQueue = queue.entries.indexOfFirst { it.episodeId == currentId }
    val removeFromActQueue = mutableListOf<Episode>()
    realm.write {
        val qes = query(QueueEntry::class).query("queueId == $0 AND episodeId IN $1", queue.id, episodes.map { it.id }).find()
        if (qes.isNotEmpty()) {
            findLatest(queue)?.let {
                for (qe in qes) {
                    val id = qe.episodeId
                    it.idsBinList.remove(id)
                    it.idsBinList.add(id)
                }
                it.trimBin()
                it.update()
            }
            for (e in episodes) {
                if (qes.indexOfFirst { it.episodeId == e.id } >= 0) {
                    if (playState != null && e.playState == EpisodeState.QUEUE.code) query(Episode::class).query("id == ${e.id}").first().find()?.setPlayState(playState)
                    if (queue.id == actQueueFlow.value.id) removeFromActQueue.add(e)
                }
            }
            delete(qes)
        }
        val qqes = query(QueueEntry::class).query("queueId == $0", queue.id).find()
        val eps = query(Episode::class).query("id IN $0", qqes.map { it.episodeId }).find()
        if (eps.size < qqes.size) {
            for (qe in qqes) {
                val e = query(Episode::class).query("id == $0", qe.episodeId).find()
                if (e.isEmpty()) delete(qe)
            }
        }
    }
    if (removeFromActQueue.isNotEmpty()) EventFlow.postEvent(QueueEvent.removed(removeFromActQueue))
    queue.checkAndFill()
}

suspend fun removeFromAllQueuesQuiet(episodeIds: List<Long>, updateState: Boolean = true) {
    Logd(TAG) { "removeFromAllQueuesQuiet called " }

    suspend fun doit(q: PlayQueue, isActQueue: Boolean = false) {
        if (q.size() == 0) {
            if (isActQueue) upsert(q) { it.update() }
            q.checkAndFill()
            return
        }
        val qes = realm.query(QueueEntry::class).query("queueId == $0 AND episodeId IN $1", q.id, episodeIds).find()
        val idsInQueuesToRemove = qes.map { it.episodeId }
        if (idsInQueuesToRemove.isNotEmpty()) {
            realm.write { for (qe in qes) findLatest(qe)?.let { delete(it) } }
            if (updateState) {
                val eList = realm.query(Episode::class).query("id IN $0 AND playState < ${EpisodeState.SKIPPED.code}", idsInQueuesToRemove).find().filter { !shouldPreserve(it.playState) }
                if (eList.isNotEmpty()) realm.write { for (e in eList) findLatest(e)?.setPlayState(EpisodeState.SKIPPED, false) }
            }
            val qNew = upsert(q) {
                it.idsBinList.removeAll(idsInQueuesToRemove)
                it.idsBinList.addAll(idsInQueuesToRemove)
                it.trimBin()
                it.update()
            }
            qNew.checkAndFill()
        }
    }

    for (q in queuesLive) {
        if (q.id == actQueueFlow.value.id) continue
        doit(q)
    }
    //        ensure actQueueFlow.value is last updated
    doit(actQueueFlow.value, true)
}

private fun calcPosition(queueEntries: List<QueueEntry>, loc: EnqueueLocation, currentPlaying: Episode?): Long {
    if (queueEntries.isEmpty()) return 0

    fun getCurrentlyPlayingPosition(): Int {
        if (currentPlaying == null) return -1
        val curPlayingItemId = currentPlaying.id
        for (i in queueEntries.indices) if (curPlayingItemId == queueEntries[i].episodeId) return i
        return -1
    }
    val size = queueEntries.size
    return when (loc) {
        EnqueueLocation.BACK -> queueEntries[size-1].position + QUEUE_POSITION_DELTA
        EnqueueLocation.FRONT -> QUEUE_POSITION_DELTA / 2
        EnqueueLocation.AFTER_CURRENTLY_PLAYING -> {
            val curPlayPos = getCurrentlyPlayingPosition()
            if (curPlayPos in 0..<size) queueEntries[curPlayPos].position + QUEUE_POSITION_DELTA / 2 else QUEUE_POSITION_DELTA / 2
        }
        EnqueueLocation.RANDOM -> queueEntries[Random.nextInt(queueEntries.size)].position + QUEUE_POSITION_DELTA / 2
    }
}

