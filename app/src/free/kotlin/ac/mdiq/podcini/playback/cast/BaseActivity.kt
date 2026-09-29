package ac.mdiq.podcini.playback.cast

import android.view.Menu
import androidx.compose.runtime.Composable
import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity() {
    val TAG = this::class.simpleName ?: "Anonymous"

    fun requestCastButton(menu: Menu?) {}

    @Composable
    fun CastIconButton() {}
}
