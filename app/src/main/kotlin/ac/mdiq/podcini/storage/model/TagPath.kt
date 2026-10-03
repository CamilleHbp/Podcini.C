package ac.mdiq.podcini.storage.model

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

enum class TagKind(val root: String, val field: String) {
    GENRE("Genre", "GENRE"), MOOD("Mood", "MOOD"), CUSTOM("Tags", "TAGS")
}

/** A path is stored as segments so a literal slash never becomes an ancestor. */
data class TagPath(val kind: TagKind, val segments: List<String>) {
    val value: String get() = encodeTagSegments(segments)
    val path: String get() = "${kind.root}/$value"

    companion object {
        fun parse(value: String): TagPath? {
            val parts = decodeTagSegments(value) ?: return null
            val kind = TagKind.entries.firstOrNull { it.root.equals(parts.firstOrNull(), true) } ?: return null
            return parts.drop(1).takeIf { it.isNotEmpty() }?.let { TagPath(kind, it) }
        }
    }
}

fun normalizeTagSegment(value: String): String = Normalizer.normalize(value.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC)
fun encodeTagSegments(parts: List<String>): String = parts.joinToString("/") { it.replace("\\", "\\\\").replace("/", "\\/") }

fun decodeTagSegments(value: String): List<String>? {
    val result = mutableListOf<String>()
    val part = StringBuilder()
    var escaped = false
    for (ch in value) {
        if (escaped) {
            if (ch != '/' && ch != '\\') return null
            part.append(ch); escaped = false
        } else when (ch) {
            '\\' -> escaped = true
            '/' -> { result.add(normalizeTagSegment(part.toString())); part.clear() }
            else -> if (ch == '\u0000' || ch.isISOControl()) return null else part.append(ch)
        }
    }
    if (escaped) return null
    result.add(normalizeTagSegment(part.toString()))
    return result.takeIf { it.all(String::isNotBlank) }
}

fun tagIdentity(path: String): String = (decodeTagSegments(path)?.let(::encodeTagSegments) ?: path.trim()).lowercase(Locale.ROOT)
fun canonicalTags(values: Collection<String>): List<String> = values.mapNotNull { TagPath.parse(it)?.path }.distinctBy(::tagIdentity).sortedBy(::tagIdentity)
fun legacyTagPath(value: String): String = TagPath(TagKind.CUSTOM,
    value.trim().trim('/').split('/').map(::normalizeTagSegment).filter(String::isNotBlank)).path

@Serializable
data class FileTagValues(
    val genres: List<String> = emptyList(), val moods: List<String> = emptyList(),
    val tags: List<String> = emptyList(), val convention: String = "",
) {
    fun paths(splitLegacy: Boolean = false, nestedLegacy: Boolean = false): List<String> = TagKind.entries.flatMap { kind ->
        val source = when (kind) { TagKind.GENRE -> genres; TagKind.MOOD -> moods; TagKind.CUSTOM -> tags }
        source.flatMap { if (splitLegacy && convention.isEmpty()) it.split(',', ';') else listOf(it) }.mapNotNull { value ->
            val parts = if (convention == "1" || (nestedLegacy && kind == TagKind.CUSTOM)) decodeTagSegments(value)
                else listOf(normalizeTagSegment(value)).takeIf { it.first().isNotEmpty() }
            require(convention != "1" || value.isBlank() || parts != null) { "Invalid embedded tag path" }
            parts?.let { TagPath(kind, it).path }
        }
    }.let(::canonicalTags)

    companion object {
        fun fromPaths(paths: Collection<String>): FileTagValues {
            val values = canonicalTags(paths).mapNotNull(TagPath::parse).groupBy { it.kind }
            return FileTagValues(values[TagKind.GENRE].orEmpty().map { it.value }, values[TagKind.MOOD].orEmpty().map { it.value },
                values[TagKind.CUSTOM].orEmpty().map { it.value }, "1")
        }
    }
}
