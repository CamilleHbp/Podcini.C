package ac.mdiq.podcini.ui.screens

import androidx.compose.runtime.Composable

/** Kept for callers using the previous route name. Browsing never activates playback. */
@Composable
fun QueuesScreen(id: Long = -1L) = PlaylistScreen(id)
