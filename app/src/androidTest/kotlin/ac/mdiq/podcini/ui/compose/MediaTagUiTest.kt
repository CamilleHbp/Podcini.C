package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.activity.MainActivity
import ac.mdiq.podcini.config.AppConfig
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.tags.MediaTagRepository
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MediaTagUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun nodes(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        repeat(150) {
            instrumentation.uiAutomation.clearCache()
            nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }?.let { return it }
            Thread.sleep(100)
        }
        error("Control not visible: $label; " + nodes().mapNotNull { it.text }.joinToString())
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        Thread.sleep(200)
    }
    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "file-tags-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun englishTreeBreadcrumbsSearchAndInheritancePromptAreUsable() = checkPicker("en")
    @Test fun frenchTreeBreadcrumbsSearchAndInheritancePromptAreUsable() = checkPicker("fr")

    @Test fun manyTagsStaySearchableWithColorPersistence() = checkPicker("en", true)
    @Test fun manyFrenchTagsInDarkTheme() = checkPicker("fr", true, true)

    private fun checkPicker(language: String, dense: Boolean = false, dark: Boolean = false) {
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        instrumentation.uiAutomation.executeShellCommand("dumpsys deviceidle whitelist +${context.packageName}").close()
        val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
        val previousLocale = localeManager.applicationLocales
        main { localeManager.applicationLocales = android.os.LocaleList.forLanguageTags(language) }
        instrumentation.waitForIdleSync()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val id = 8_730_101L
        var chosen: List<String>? = null
        var embed: Boolean? = null
        var previousPreferences: String? = null
        val selected = setOf("Tags/Activity/Focus") + if (dense) (1..40).map { "Tags/Activity/Topic $it" } else emptyList()
        val capture = "$language-${if (dense) "dense" else "basic"}-${if (dark) "dark" else "light"}"
        try {
            assertEquals(language, activity.resources.configuration.locales[0].language)
            AppConfig.initialize()
            previousPreferences = realm.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences
            realm.writeBlocking {
                val item = copyToRealm(Episode().apply { this.id = id; title = "Tag fixture"; tags.addAll(listOf("Genre/Rock/Alternative", "Mood/Calm", "Tags/Activity/Focus")) })
                MediaTagRepository.queue(this, item, item.tags.toList())
                query(AppPrefs::class).first().find()!!.inheritedTagPolicy = "ask"
            }
            main {
                activity.setContent { PodciniTheme(if (dark) AppThemes.DARK else AppThemes.LIGHT) { TagSettingDialog(TagType.Feed, selected, onDismiss = {}, onEmbeddingChoice = { embed = it }) { chosen = it } } }
            }
            waitFor(activity.getString(ac.mdiq.podcini.R.string.file_tags_search))
            screenshot("picker-$capture")
            click(activity.getString(ac.mdiq.podcini.R.string.tag_color_choose_for, "Genre"))
            waitFor(activity.getString(ac.mdiq.podcini.R.string.tag_color_title))
            click(activity.getString(ac.mdiq.podcini.R.string.tag_color_blue))
            screenshot("colour-$capture")
            click(activity.getString(ac.mdiq.podcini.R.string.library_save))
            waitFor(activity.getString(ac.mdiq.podcini.R.string.file_tags_search))
            assertEquals(208, decodeBrowsePreferences(realm.query(AppPrefs::class).first().find()!!.libraryBrowsePreferences).tagColors["genre"])
            if (dense) {
                click(activity.getString(ac.mdiq.podcini.R.string.tag_selected_count, selected.size))
                waitFor(activity.getString(ac.mdiq.podcini.R.string.tags_label) + " › Activity › Focus")
                screenshot("selected-$capture")
                val filter = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Topic 40") }
                check(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, filter))
                waitFor(activity.getString(ac.mdiq.podcini.R.string.tags_label) + " › Activity › Topic 40")
                click(activity.getString(ac.mdiq.podcini.R.string.tag_clear_search))
                click(activity.getString(ac.mdiq.podcini.R.string.tag_browse))
            }
            click("Genre"); click("Rock")
            waitFor("Alternative"); waitFor("Genre › Rock")
            screenshot("hierarchy-$capture")
            click(activity.getString(ac.mdiq.podcini.R.string.file_tags_parent)); click(activity.getString(ac.mdiq.podcini.R.string.file_tags_parent))
            val query = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Alternative") }
            check(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, query))
            waitFor("Genre › Rock › Alternative")
            query.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            check(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, query))
            click(activity.getString(ac.mdiq.podcini.R.string.confirm_label))
            waitFor(activity.getString(ac.mdiq.podcini.R.string.file_tags_keep_inherited)); waitFor(activity.getString(ac.mdiq.podcini.R.string.file_tags_embed))
            screenshot("inheritance-$capture")
            click(activity.getString(ac.mdiq.podcini.R.string.file_tags_keep_inherited))
            assertEquals(canonicalTags(selected), chosen)
            assertEquals(false, embed)
            if (dense) {
                var overflowOpened = false
                main { activity.setContent { PodciniTheme(if (dark) AppThemes.DARK else AppThemes.LIGHT) {
                    Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                        CompactTagList(selected, onTagClick = {}, onShowAll = { overflowOpened = true })
                    } }
                } } }
                val more = activity.resources.getQuantityString(ac.mdiq.podcini.R.plurals.tag_more_count, selected.size - 3, selected.size - 3)
                waitFor(more); screenshot("compact-$capture"); click(more)
                assertTrue(overflowOpened)
            }
            assertTrue(realm.query(Episode::class, "id == $0", id).first().find()!!.tags.contains("Genre/Rock/Alternative"))
        } finally {
            realm.writeBlocking {
                previousPreferences?.let { query(AppPrefs::class).first().find()!!.libraryBrowsePreferences = it }
                delete(query(MediaTagState::class, "episodeId == $0", id).find())
                delete(query(Episode::class, "id == $0", id).find())
            }
            main { activity.finish(); localeManager.applicationLocales = previousLocale }
            instrumentation.waitForIdleSync()
        }
    }
}
