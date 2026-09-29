package ac.mdiq.podcini.utils

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import androidx.annotation.StringRes

/** Resolve background messages using the app language, including on Android 12 and earlier. */
fun localizedString(@StringRes resource: Int, vararg arguments: Any): String =
    getAppContext().getString(resource, *arguments)
