package ac.mdiq.podcini.config.settings

/** Exported media uses title.episodeId.extension; titles may themselves contain dots. */
internal fun backupMediaEpisodeId(name: String): Long? =
    name.substringBeforeLast('.', "").substringAfterLast('.', "").toLongOrNull()

/** Clips use recorded_episodeId_clipName.extension; clip names may contain dots/underscores. */
internal fun backupClipEpisodeId(name: String): Long? =
    name.takeIf { it.startsWith("recorded_") }?.removePrefix("recorded_")?.substringBefore('_')?.toLongOrNull()
