package ac.mdiq.podcini.storage.model

import kotlin.math.abs
import kotlin.math.roundToInt

/** UI-only opaque ARGB colours. Values from 0 to 359 are legacy hue preferences. */
fun tagColorArgb(path: String, colors: Map<String, Int>): Int {
    val segments = decodeTagSegments(path).orEmpty()
    for (size in segments.size downTo 1) {
        colors[tagIdentity(encodeTagSegments(segments.take(size)))]?.let {
            if (it in 0..359) return legacyTagColor(it)
            if (it ushr 24 == 255) return it
        }
    }
    val family = tagIdentity(encodeTagSegments(segments.take(2)))
    return legacyTagColor(listOf(12, 38, 82, 150, 180, 208, 238, 272, 310, 340)[Math.floorMod(family.hashCode(), 10)])
}

/** Preserve the original light-theme HSL swatch when reading an older preference. */
private fun legacyTagColor(hue: Int): Int {
    val chroma = (1 - abs(2 * .36f - 1)) * .60f
    val x = chroma * (1 - abs((hue / 60f) % 2 - 1))
    val (r, g, b) = when (hue / 60) {
        0 -> Triple(chroma, x, 0f)
        1 -> Triple(x, chroma, 0f)
        2 -> Triple(0f, chroma, x)
        3 -> Triple(0f, x, chroma)
        4 -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    fun channel(value: Float) = ((value + .36f - chroma / 2) * 255).roundToInt()
    return (255 shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

/** Destination overrides win on merge. Descendant overrides follow a renamed or moved branch. */
fun remapTagColors(colors: Map<String, Int>, from: String, to: String): Map<String, Int> {
    val result = colors.filterKeys { !tagMatches(it, from) }.toMutableMap()
    colors.filterKeys { tagMatches(it, from) }.forEach { (path, color) ->
        remapLibraryTag(path, from, to)?.let { result.putIfAbsent(tagIdentity(it), color) }
    }
    return result
}
