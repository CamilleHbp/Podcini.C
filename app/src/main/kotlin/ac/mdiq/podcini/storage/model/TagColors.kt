package ac.mdiq.podcini.storage.model

/** UI-only hues, keyed by canonical path; never included in FileTagValues. */
fun tagColorHue(path: String, colors: Map<String, Int>): Int {
    val segments = decodeTagSegments(path).orEmpty()
    for (size in segments.size downTo 1) {
        colors[tagIdentity(encodeTagSegments(segments.take(size)))]?.takeIf { it in 0..359 }?.let { return it }
    }
    val family = tagIdentity(encodeTagSegments(segments.take(2)))
    return listOf(12, 38, 82, 150, 180, 208, 238, 272, 310, 340)[Math.floorMod(family.hashCode(), 10)]
}

/** Destination overrides win on merge. Descendant overrides follow a renamed or moved branch. */
fun remapTagColors(colors: Map<String, Int>, from: String, to: String): Map<String, Int> {
    val result = colors.filterKeys { !tagMatches(it, from) }.toMutableMap()
    colors.filterKeys { tagMatches(it, from) }.forEach { (path, hue) ->
        remapLibraryTag(path, from, to)?.let { result.putIfAbsent(tagIdentity(it), hue) }
    }
    return result
}
