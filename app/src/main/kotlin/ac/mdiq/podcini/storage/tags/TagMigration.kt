package ac.mdiq.podcini.storage.tags

import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.model.*
import kotlinx.serialization.encodeToString

/** Database-only migration. File writes are never scheduled from here. */
fun migrateTagPaths(database: io.github.xilinjia.krdb.Realm = realm) {
    if (database.query(AppPrefs::class).first().find()?.tagPathsMigrated == true) return
    fun path(value: String) = if (value.isBlank()) "" else legacyTagPath(value)
    fun paths(values: Collection<String>) = values.filter(String::isNotBlank).map(::path).toSet()
    fun filter(value: LibraryFilter) = value.copy(tags = paths(value.tags).toList(), excludedTags = paths(value.excludedTags).toList(), scopeTag = path(value.scopeTag))
    fun episodeFilter(value: String): String {
        val filter = ac.mdiq.podcini.storage.specs.EpisodeFilter(value)
        val mapped = filter.propertySet.map { if (it.startsWith("tags ")) "tags " + path(it.removePrefix("tags ")) else it }
        filter.propertySet.clear(); filter.propertySet.addAll(mapped)
        return filter.encode()
    }
    database.writeBlocking {
        val prefs = query(AppPrefs::class).first().find() ?: return@writeBlocking
        query(Episode::class).find().forEach { val tags = paths(it.tags); it.tags.clear(); it.tags.addAll(tags) }
        query(Feed::class).find().forEach {
            val tags = paths(it.tags); it.tags.clear(); it.tags.addAll(tags); it.filterString = episodeFilter(it.filterString)
            it.autoDLEQs.forEach { rule -> rule.filterStringADL = episodeFilter(rule.filterStringADL) }
        }
        query(FacetsPrefs::class).find().forEach { prefs -> prefs.filtersMap.keys.toList().forEach { key -> prefs.filtersMap[key] = episodeFilter(prefs.filtersMap[key].orEmpty()) } }
        query(PlayQueue::class).find().forEach {
            val tags = paths(it.tags); it.tags.clear(); it.tags.addAll(tags)
            it.ruleTag = path(it.ruleTag)
            decodeLibraryFilter(it.ruleBrowseFilter)?.let { value -> it.ruleBrowseFilter = filter(value).encode() }
        }
        query(SubscriptionsPrefs::class).find().forEach { val tags = paths(it.tagsSel); it.tagsSel.clear(); it.tagsSel.addAll(tags) }
        query(AppAttribs::class).find().forEach {
            val feedTags = paths(it.feedTagSet); it.feedTagSet.clear(); it.feedTagSet.addAll(feedTags)
            val episodeTags = paths(it.episodeTagSet); it.episodeTagSet.clear(); it.episodeTagSet.addAll(episodeTags)
        }
        val saved = decodeBrowsePreferences(prefs.libraryBrowsePreferences)
        prefs.libraryBrowsePreferences = libraryJson.encodeToString(saved.copy(emptyTags = paths(saved.emptyTags).toList(),
            pins = saved.pins.map { it.copy(destination = it.destination.copy(tag = path(it.destination.tag), filter = filter(it.destination.filter))) }))
        prefs.tagPathsMigrated = true
    }
}
