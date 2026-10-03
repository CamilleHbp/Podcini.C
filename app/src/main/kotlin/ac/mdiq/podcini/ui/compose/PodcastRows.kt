package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.utils.NetworkUtils.imageLoader
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ac.mdiq.podcini.shared.FeedSearchResult
import ac.mdiq.podcini.sourcing.feed.PodcastLibrary
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.ui.screens.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun PodcastArtwork(url: String?, modifier: Modifier = Modifier, finished: Boolean = false) {
    var fallback by remember(url) { mutableStateOf(true) }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        AsyncImage(model = url, imageLoader = imageLoader, contentDescription = null, contentScale = ContentScale.Fit,
            onState = { fallback = it !is coil3.compose.AsyncImagePainter.State.Success },
            colorFilter = if (finished) androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }) else null,
            modifier = Modifier.matchParentSize())
        if (fallback) Icon(painterResource(R.drawable.archive_headphones), null, Modifier.fillMaxSize().padding(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PodcastRow(title: String, author: String?, imageUrl: String?, onOpen: () -> Unit,
               modifier: Modifier = Modifier, inLibrary: Boolean = false, adding: Boolean = false,
               supporting: String? = null, onAdd: (() -> Unit)? = null) {
    val largeText = LocalConfiguration.current.fontScale >= 1.3f
    val addLabel = stringResource(R.string.search_add_podcast_accessibility, title)
    val memberLabel = stringResource(R.string.search_in_library)
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PodcastArtwork(imageUrl, Modifier.size(if (largeText) 48.dp else 64.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = if (largeText) 4 else 3, overflow = TextOverflow.Ellipsis)
                if (!author.isNullOrBlank()) Text(author, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!supporting.isNullOrBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (inLibrary && onAdd != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(memberLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (onAdd != null && !inLibrary) {
            OutlinedButton(onClick = onAdd, enabled = !adding, contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = addLabel }) {
                if (adding) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.archive_add))
            }
        }
    }
}

/** Shared visible actions for directories, related shows and previously removed podcasts. */
@Composable
fun OnlinePodcastRow(result: FeedSearchResult, previouslyRemoved: Boolean = false) {
    val scope = rememberCoroutineScope()
    var adding by remember(result.feedUrl) { mutableStateOf(false) }
    var failed by remember(result.feedUrl) { mutableStateOf(false) }
    val feeds by remember { realm.query(Feed::class, "id > 1000").asFlow().map { it.list.toList() } }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val key = ac.mdiq.podcini.sourcing.searcher.podcastUrlKey(result.feedUrl)
    val member = feeds.firstOrNull { key != null && ac.mdiq.podcini.sourcing.searcher.podcastUrlKey(it.downloadUrl) == key }
    Column {
        PodcastRow(result.title, result.author, result.imageUrl,
            onOpen = { if (member != null) navTo(FeedDetails(member.id)) else result.feedUrl?.let { navTo(OnlineFeed(it, result.source)) } },
            inLibrary = member != null, adding = adding,
            supporting = if (previouslyRemoved && member == null) stringResource(R.string.search_previously_removed) else null,
            onAdd = if (result.feedUrl == null) null else ({
                if (!adding) {
                    adding = true; failed = false
                    scope.launch {
                        try { PodcastLibrary.add(result) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { adding = false }
                    }
                }
            }))
        if (failed) Text(stringResource(R.string.search_add_failed), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}
