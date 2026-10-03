package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.R
import ac.mdiq.podcini.utils.localizedString

/** Localize built-in names without changing persisted names or user-created names. */
val PlayQueue.displayName: String
    get() = queueDisplayName(id, name)

fun queueDisplayName(id: Long, name: String): String = when {
    id == 0L && name in listOf("Default", "Listen later") -> localizedString(R.string.playlist_listen_later)
    id == VIRTUAL_QUEUE_ID -> localizedString(R.string.listening_queue)
    id == ac.mdiq.podcini.storage.database.FAVOURITES_PLAYLIST_ID -> localizedString(R.string.library_favourites)
    id in 1L..4L && name == "Queue $id" -> localizedString(R.string.ui_numbered_queue, id)
    else -> name
}
