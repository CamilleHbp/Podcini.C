package ac.mdiq.podcini.storage.tags

import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.model.*
import io.github.xilinjia.krdb.MutableRealm
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

@Serializable
data class TagBranchSnapshot(val episodes: Map<Long, List<String>>, val feeds: Map<Long, List<String>>,
    val playlists: Map<Long, PlaylistTags>, val preferences: String, val references: TagFilterReferences)
@Serializable
data class TagFilterReferences(val feeds: Map<Long, List<String>>, val facets: Map<Long, Map<String, String?>>,
    val subscriptions: Map<Long, List<String>>)
@Serializable
data class PlaylistTags(val tags: List<String>, val rule: String, val filter: String)
@Serializable
data class TagBranchChange(val before: TagBranchSnapshot, val after: TagBranchSnapshot)

fun MutableRealm.tagSnapshot() = TagBranchSnapshot(
    query(Episode::class).find().associate { it.id to it.tags.sorted() },
    query(Feed::class).find().associate { it.id to it.tags.sorted() },
    query(PlayQueue::class).find().associate { it.id to PlaylistTags(it.tags.sorted(), it.ruleTag, it.ruleBrowseFilter) },
    query(AppPrefs::class).first().find()?.libraryBrowsePreferences.orEmpty(), TagFilterReferences(
        query(Feed::class).find().associate { it.id to (listOf(it.filterString) + it.autoDLEQs.map { rule -> rule.filterStringADL }) },
        query(FacetsPrefs::class).find().associate { it.id to it.filtersMap.toMap() },
        query(SubscriptionsPrefs::class).find().associate { it.id to it.tagsSel.sorted() }))

fun MutableRealm.remapTagFilterReferences(from: String, to: String) {
    if (to.isBlank()) return
    fun mapFilter(value: String): String {
        val filter = ac.mdiq.podcini.storage.specs.EpisodeFilter(value)
        val mapped = filter.propertySet.map { if (it.startsWith("tags ")) "tags " + remapLibraryTag(it.removePrefix("tags "), from, to) else it }
        filter.propertySet.clear(); filter.propertySet.addAll(mapped)
        return filter.encode()
    }
    query(Feed::class).find().forEach { it.filterString = mapFilter(it.filterString); it.autoDLEQs.forEach { rule -> rule.filterStringADL = mapFilter(rule.filterStringADL) } }
    query(FacetsPrefs::class).find().forEach { prefs -> prefs.filtersMap.keys.toList().forEach { key -> prefs.filtersMap[key] = mapFilter(prefs.filtersMap[key].orEmpty()) } }
    query(SubscriptionsPrefs::class).find().forEach { prefs ->
        val tags = prefs.tagsSel.mapNotNull { remapLibraryTag(it, from, to) }; prefs.tagsSel.clear(); prefs.tagsSel.addAll(tags)
    }
    query(AppAttribs::class).find().forEach { attrs ->
        val feeds = attrs.feedTagSet.mapNotNull { remapLibraryTag(it, from, to) }; attrs.feedTagSet.clear(); attrs.feedTagSet.addAll(feeds)
        val episodes = attrs.episodeTagSet.mapNotNull { remapLibraryTag(it, from, to) }; attrs.episodeTagSet.clear(); attrs.episodeTagSet.addAll(episodes)
    }
}

fun MutableRealm.recordTagUndo(before: TagBranchSnapshot) {
    val after = tagSnapshot()
    fun <T> changed(first: Map<Long, T>, second: Map<Long, T>) = first.filter { (id, value) -> value != second[id] }
    val b = before.copy(episodes = changed(before.episodes, after.episodes), feeds = changed(before.feeds, after.feeds), playlists = changed(before.playlists, after.playlists))
    val a = after.copy(episodes = changed(after.episodes, before.episodes), feeds = changed(after.feeds, before.feeds), playlists = changed(after.playlists, before.playlists))
    copyToRealm(TagUndo().apply { id = UUID.randomUUID().toString(); created = System.currentTimeMillis(); snapshot = libraryJson.encodeToString(TagBranchChange(b, a)) })
    query(TagUndo::class).find().sortedByDescending { it.created }.drop(10).forEach { delete(it) }
}

suspend fun undoTagBranch() {
    MediaTagRepository.serialized {
        realm.write {
            val undo = query(TagUndo::class).find().maxByOrNull { it.created } ?: return@write
            val change = libraryJson.decodeFromString<TagBranchChange>(undo.snapshot)
            val current = tagSnapshot()
            // Never erase edits made after this operation, even when undoing a branch merge.
            require(change.after.episodes.all { current.episodes[it.key] == it.value } &&
                change.after.feeds.all { current.feeds[it.key] == it.value } &&
                change.after.playlists.all { current.playlists[it.key] == it.value } &&
                current.preferences == change.after.preferences && current.references == change.after.references) { "Tags changed since this branch operation" }
            change.before.episodes.forEach { (id, tags) -> query(Episode::class, "id == $0", id).first().find()?.let { MediaTagRepository.queue(this, it, tags) } }
            change.before.feeds.forEach { (id, tags) -> query(Feed::class, "id == $0", id).first().find()?.let { it.tags.clear(); it.tags.addAll(tags) } }
            change.before.playlists.forEach { (id, saved) -> query(PlayQueue::class, "id == $0", id).first().find()?.let {
                it.tags.clear(); it.tags.addAll(saved.tags); it.ruleTag = saved.rule; it.ruleBrowseFilter = saved.filter
            } }
            query(AppPrefs::class).first().find()?.libraryBrowsePreferences = change.before.preferences
            change.before.references.feeds.forEach { (id, filters) -> query(Feed::class, "id == $0", id).first().find()?.let {
                it.filterString = filters.first(); it.autoDLEQs.forEachIndexed { index, rule -> rule.filterStringADL = filters[index + 1] }
            } }
            change.before.references.facets.forEach { (id, filters) -> query(FacetsPrefs::class, "id == $0", id).first().find()?.let { it.filtersMap.clear(); it.filtersMap.putAll(filters) } }
            change.before.references.subscriptions.forEach { (id, tags) -> query(SubscriptionsPrefs::class, "id == $0", id).first().find()?.let { it.tagsSel.clear(); it.tagsSel.addAll(tags) } }
            delete(undo)
        }
    }
    MediaTagRepository.schedule()
}
