package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.ui.compose.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

@Composable
fun SavedLibraryScreen() {
    var selectedKind by rememberSaveable { mutableIntStateOf(0) }
    val kinds = listOf(R.string.archive_all_saved, R.string.archive_liked_episodes, R.string.archive_notes, R.string.archive_bookmarks, R.string.archive_clips)
    val highlightsQuery = "rating >= ${Rating.GOOD.code} OR comment != '' OR marks.@count > 0 OR clips.@count > 0"
    val highlightCount by remember {
        realm.query(Episode::class, highlightsQuery).count().asFlow()
    }.collectAsStateWithLifecycle(initialValue = null)
    val saved = remember(selectedKind) {
        val predicate = when (selectedKind) {
            1 -> "rating >= ${Rating.GOOD.code}"
            2 -> "comment != ''"
            3 -> "marks.@count > 0"
            4 -> "clips.@count > 0"
            else -> highlightsQuery
        }
        realm.query(Episode::class, "$predicate SORT(pubDate DESC)").asFlow().map { it.list.toList() }
    }
    val episodes by saved.collectAsStateWithLifecycle(initialValue = null)
    val details = remember { MutableStateFlow<List<Episode>>(emptyList()) }
    LaunchedEffect(episodes) { details.value = episodes.orEmpty() }
    DisposableEffect(episodeForInfo) {
        if (episodeForInfo != null) handleBackSubScreens.add("Saved") else handleBackSubScreens.remove("Saved")
        onDispose { handleBackSubScreens.remove("Saved") }
    }
    BackHandler(episodeForInfo != null) { episodeForInfo = null }
    Box(Modifier.fillMaxSize()) {
        Column {
        if ((highlightCount ?: 0L) > 0L) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(kinds.size) { index -> FilterChip(selected = selectedKind == index, onClick = { selectedKind = index }, label = { Text(stringResource(kinds[index])) }) }
        }
        when {
            highlightCount == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            highlightCount == 0L -> ArchiveEmpty(R.string.archive_empty_saved, R.string.archive_empty_saved_body, R.string.archive_listen) { selectPrimary(Listen) }
            episodes == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            episodes!!.isEmpty() && selectedKind != 0 -> {
                val empty = when (selectedKind) {
                    1 -> R.string.archive_no_liked to R.string.archive_no_liked_body
                    2 -> R.string.archive_no_notes to R.string.archive_notes_empty_body
                    3 -> R.string.archive_no_bookmarks to R.string.archive_no_bookmarks_body
                    else -> R.string.archive_no_clips to R.string.archive_no_clips_body
                }
                ArchiveEmpty(empty.first, empty.second, R.string.archive_all_saved) { selectedKind = 0 }
            }
            episodes!!.isEmpty() -> ArchiveEmpty(R.string.archive_empty_saved, R.string.archive_empty_saved_body, R.string.archive_listen) { selectPrimary(Listen) }
            else -> EpisodeLazyColumn(episodes!!, showHighlights = true)
        }
        }
        if (episodeForInfo != null) EpisodeScreen(episodeForInfo!!, listFlow = details, allowOpenFeed = true)
    }
}
