package ac.mdiq.podcini.storage.specs

/** A detached value: editing or resetting it never mutates an applied filter. */
data class EpisodeFilterDraft(
    val properties: Set<String> = emptySet(),
    val andOr: String = "AND",
    val durationFloor: Int = 0,
    val durationCeiling: Int = Int.MAX_VALUE,
    val titleText: String = "",
) {
    constructor(filter: EpisodeFilter) : this(filter.propertySet.toSet(), filter.andOr.ifBlank { "AND" }, filter.durationFloor, filter.durationCeiling, filter.titleText)

    fun toFilter(): EpisodeFilter = EpisodeFilter(andOr = andOr).also {
        it.propertySet.addAll(properties)
        it.durationFloor = durationFloor
        it.durationCeiling = durationCeiling
        it.titleText = titleText
    }

    fun select(group: EpisodeFilter.EpisodesFilterGroup, values: Set<String>): EpisodeFilterDraft =
        copy(properties = properties - group.properties.map { it.filterId }.toSet() + values)

    fun reset(disabled: Set<EpisodeFilter.EpisodesFilterGroup>): EpisodeFilterDraft = EpisodeFilterDraft(
        properties = properties.intersect(disabled.flatMap { it.properties.map { p -> p.filterId } }.toSet()))
}

data class FeedFilterDraft(
    val properties: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val queueIds: Set<Long> = emptySet(),
) {
    fun select(group: FeedFilter.FeedFilterGroup, values: Set<String>): FeedFilterDraft =
        copy(properties = properties - group.values.map { it.filterId }.toSet() + values)

    /** Keep the Library's existing special empty-selection meanings. */
    fun queryString(allLanguages: Set<String>, allTags: Set<String>, allQueues: Set<Long>): String {
        val parts = mutableListOf(FeedFilter(*properties.toTypedArray()).queryString())
        when {
            languages.isEmpty() -> parts.add("langSet.@count > 0")
            languages.size != allLanguages.size -> parts.add(languages.joinToString(" OR ") { "ANY langSet == ${filterQuote(it)}" })
        }
        when {
            tags.isEmpty() -> parts.add("tags.@count == 0")
            tags.size != allTags.size -> parts.add(tags.joinToString(" OR ") { "ANY tags == ${filterQuote(it)}" })
        }
        val queues = when {
            queueIds.isEmpty() -> setOf(-2L)
            (allQueues - queueIds).isEmpty() -> emptySet()
            else -> queueIds - -2L
        }
        if (queues.isNotEmpty()) parts.add(queues.joinToString(" OR ") { "queueId == '$it'" })
        return parts.joinToString(" AND ") { "($it)" }
    }
}

// Realm's encoded string literal preserves quotes, backslashes and persisted CSV delimiters.
internal fun filterQuote(value: String): String = "B64\"${java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))}\""
