package ac.mdiq.podcini.ui.actions

/** File actions depend on media availability, independently of the current play button. */
internal enum class EpisodeDownloadAction { DOWNLOAD, CANCEL, REMOVE_DOWNLOAD, DELETE_LOCAL_FILE }

internal fun episodeDownloadAction(local: Boolean, hasFile: Boolean, downloading: Boolean,
    hasRemoteMedia: Boolean, supportsDownload: Boolean): EpisodeDownloadAction? = when {
    local -> if (hasFile) EpisodeDownloadAction.DELETE_LOCAL_FILE else null
    downloading -> EpisodeDownloadAction.CANCEL
    hasFile -> EpisodeDownloadAction.REMOVE_DOWNLOAD
    hasRemoteMedia && supportsDownload -> EpisodeDownloadAction.DOWNLOAD
    else -> null
}
