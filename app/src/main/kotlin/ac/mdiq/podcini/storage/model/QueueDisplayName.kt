package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.R
import ac.mdiq.podcini.utils.localizedString

/** Localize built-in names without changing persisted names or user-created names. */
val PlayQueue.displayName: String
    get() = queueDisplayName(id, name)

fun queueDisplayName(id: Long, name: String): String = when {
    id == 0L && name == "Default" -> localizedString(R.string.ui_default_queue)
    id == VIRTUAL_QUEUE_ID && name == "Virtual" -> localizedString(R.string.ui_virtual_queue)
    id in 1L..4L && name == "Queue $id" -> localizedString(R.string.ui_numbered_queue, id)
    else -> name
}
