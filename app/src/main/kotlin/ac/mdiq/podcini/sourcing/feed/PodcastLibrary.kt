package ac.mdiq.podcini.sourcing.feed

import ac.mdiq.podcini.shared.FeedSearchResult
import ac.mdiq.podcini.sourcing.EPISODE_BATCH_SIZE
import ac.mdiq.podcini.sourcing.clientBySearcher
import ac.mdiq.podcini.sourcing.searcher.PodcastSearcherRegistry
import ac.mdiq.podcini.sourcing.searcher.podcastUrlKey
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.toFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Adding membership never downloads audio, starts playback, or replaces a saved playlist. */
object PodcastLibrary {
    private val additions = Mutex()

    fun existing(url: String?): Feed? {
        val key = podcastUrlKey(url) ?: return null
        return realm.query(Feed::class, "id > 1000").find().firstOrNull { podcastUrlKey(it.downloadUrl) == key }
    }

    suspend fun add(result: FeedSearchResult): Long = withContext(Dispatchers.IO) {
        additions.withLock {
            val url = result.feedUrl ?: throw IOException("Missing feed URL")
            existing(url)?.let { return@withLock it.id }
            val client = clientBySearcher(result.source)
            var built: Feed? = null
            if (client != null) {
                val ipc = client.withProvider { it.buildFeed(url, 0) } ?: throw IOException("Provider did not return a podcast")
                // The provider loads further pages during normal refresh; acquisition stays bounded.
                ipc.episodes = client.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, 0L) }.orEmpty().toMutableList()
                built = ipc.toFeed()
            } else {
                val resolved = PodcastSearcherRegistry.lookupUrl(url)
                existing(resolved)?.let { return@withLock it.id }
                FeedBuilder { _, _ -> }.buildPodcast(resolved, "", "") { feed, _ ->
                    currentCoroutineContext().ensureActive()
                    if (built == null) built = feed
                }
            }
            currentCoroutineContext().ensureActive()
            val feed = built ?: throw IOException("Unable to read podcast")
            feed.autoDownload = false
            feed.autoEnqueue = false
            saveBuilt(feed)
        }
    }

    suspend fun save(feed: Feed): Long = withContext(Dispatchers.IO) { additions.withLock { saveBuilt(feed) } }

    private suspend fun saveBuilt(feed: Feed): Long {
        existing(feed.downloadUrl)?.let { return it.id }
        // Existing records own progress, files and personal metadata: never upsert a preview over them.
        val identity = feed.identifier?.takeIf { it.isNotBlank() }
        if (identity != null) realm.query(Feed::class, "id > 1000 AND identifier == $0", identity).first().find()?.let { return it.id }
        currentCoroutineContext().ensureActive()
        subscribe(feed)
        return if (feed.id == 0L) existing(feed.downloadUrl)?.id ?: throw IOException("Podcast was not saved") else feed.id
    }
}
