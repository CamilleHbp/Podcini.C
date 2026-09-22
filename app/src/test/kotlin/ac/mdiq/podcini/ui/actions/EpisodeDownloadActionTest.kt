package ac.mdiq.podcini.ui.actions

import ac.mdiq.podcini.sourcing.download.DownloadStatus
import org.junit.Assert.*
import org.junit.Test

class EpisodeDownloadActionTest {
    @Test fun `completed or failed progress does not leave a cancel action visible`() {
        for (status in listOf(
            DownloadStatus(DownloadStatus.State.COMPLETED.code, 100),
            DownloadStatus(DownloadStatus.State.COMPLETED.code, -1),
            DownloadStatus(DownloadStatus.State.INCOMPLETE.code, 37),
        )) {
            assertNull(status.activeProgress)
            assertEquals(EpisodeDownloadAction.REMOVE_DOWNLOAD,
                episodeDownloadAction(false, true, status.activeProgress != null, true, true))
            assertEquals(EpisodeDownloadAction.DOWNLOAD,
                episodeDownloadAction(false, false, status.activeProgress != null, true, true))
        }
    }
    @Test fun `queued and running transfers remain cancellable until completion`() {
        for (status in listOf(
            DownloadStatus(DownloadStatus.State.QUEUED.code, 0),
            DownloadStatus(DownloadStatus.State.RUNNING.code, 100),
        )) {
            assertNotNull(status.activeProgress)
            assertEquals(EpisodeDownloadAction.CANCEL,
                episodeDownloadAction(false, true, status.activeProgress != null, true, true))
        }
    }
    @Test fun `local files never offer download or cancel even with a source URL`() {
        assertEquals(EpisodeDownloadAction.DELETE_LOCAL_FILE, episodeDownloadAction(true, true, true, true, true))
        assertNull(episodeDownloadAction(true, false, false, true, true))
    }
    @Test fun `active remote transfers offer cancellation rather than deletion of a partial file`() {
        assertEquals(EpisodeDownloadAction.CANCEL, episodeDownloadAction(false, true, true, true, true))
    }
    @Test fun `existing downloads can be removed even when provider stops supporting downloads`() {
        assertEquals(EpisodeDownloadAction.REMOVE_DOWNLOAD, episodeDownloadAction(false, true, false, true, false))
    }
    @Test fun `text-only and streaming-only content have no download action`() {
        assertNull(episodeDownloadAction(false, false, false, false, true))
        assertNull(episodeDownloadAction(false, false, false, true, false))
    }
    @Test fun `download is available for supported remote media`() {
        assertEquals(EpisodeDownloadAction.DOWNLOAD, episodeDownloadAction(false, false, false, true, true))
    }
}
