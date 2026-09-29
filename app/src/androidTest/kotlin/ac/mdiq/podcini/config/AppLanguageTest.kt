package ac.mdiq.podcini.config

import ac.mdiq.podcini.PodciniApp
import ac.mdiq.podcini.R
import ac.mdiq.podcini.activity.MainActivity
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun currentActivity(): Activity? {
        var activity: Activity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity }
        }
        return activity
    }

    private fun awaitLabel(label: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (currentActivity()?.getString(R.string.archive_listen) == label &&
                PodciniApp.getAppContext().getString(R.string.archive_listen) == label) return
            Thread.sleep(100)
        }
        assertEquals(label, currentActivity()?.getString(R.string.archive_listen))
        assertEquals(label, PodciniApp.getAppContext().getString(R.string.archive_listen))
    }

    @Test
    fun appLanguageAppliesToActivityAndBackgroundResourcesAndCanReturnToSystem() {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val original = AppCompatDelegate.getApplicationLocales()
        try {
            for ((tag, label) in listOf("fr" to "Écouter", "en" to "Listen")) {
                instrumentation.runOnMainSync {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
                awaitLabel(label)
                val activity = currentActivity()
                instrumentation.runOnMainSync { activity?.recreate() }
                awaitLabel(label)
            }
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
            val systemConfiguration = Configuration(instrumentation.targetContext.resources.configuration)
                .apply { setLocales(Resources.getSystem().configuration.locales) }
            val expected = instrumentation.targetContext.createConfigurationContext(systemConfiguration)
                .getString(R.string.archive_listen)
            awaitLabel(expected)
            assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
        } finally {
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(original) }
        }
    }

    @Test
    fun frenchRegionsPluralsAndEnglishFallbackUsePackagedTranslations() {
        val context = instrumentation.targetContext
        fun resources(tag: String) = context.createConfigurationContext(Configuration(context.resources.configuration)
            .apply { setLocale(Locale.forLanguageTag(tag)) }).resources
        for (tag in listOf("fr-FR", "fr-CA")) {
            val french = resources(tag)
            assertEquals("Écouter", french.getString(R.string.archive_listen))
            assertEquals("Afficher 1 résultat", french.getQuantityString(R.plurals.filter_show_results, 1, 1))
            assertEquals("Afficher 2 résultats", french.getQuantityString(R.plurals.filter_show_results, 2, 2))
            assertEquals("Lecteur 2", french.getString(R.string.archive_player_number, 2))
        }
        assertEquals("Listen", resources("zz").getString(R.string.archive_listen))
    }
}
