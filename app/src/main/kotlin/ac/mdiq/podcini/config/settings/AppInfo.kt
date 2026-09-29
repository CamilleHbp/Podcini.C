package ac.mdiq.podcini.config.settings

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext

const val githubAddress = "https://github.com/CamilleHbp/Podcini.C"

const val developerEmail = "xilin.vw@gmail.com"

fun getCopyrightNoticeText(): String = getAppContext().getString(ac.mdiq.podcini.R.string.settings_upstream_attribution)
