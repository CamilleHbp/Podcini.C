package ac.mdiq.podcini.storage.model

/** Namespace and hierarchy boundaries are significant: science never matches science-fiction. */
fun tagMatches(tag: String, filter: String, descendants: Boolean = true): Boolean {
    val value = decodeTagSegments(tag) ?: return false
    val query = decodeTagSegments(filter) ?: return false
    return value.size >= query.size && (descendants || value.size == query.size) &&
        query.indices.all { value[it].lowercase(java.util.Locale.ROOT) == query[it].lowercase(java.util.Locale.ROOT) }
}

fun tagParents(tag: String): List<String> {
    val parts = decodeTagSegments(tag) ?: return emptyList()
    return parts.indices.map { encodeTagSegments(parts.take(it + 1)) }
}

/** Pure ordering policy shared by the player and the upcoming preview. */
fun nextListeningId(ids: List<Long>, current: Long?, continuous: Boolean, repeat: Boolean, manual: Boolean = false): Long? {
    if (ids.isEmpty() || (!manual && !continuous)) return null
    val index = ids.indexOf(current)
    return ids.getOrNull(index + 1) ?: ids.firstOrNull()?.takeIf { repeat }
}
