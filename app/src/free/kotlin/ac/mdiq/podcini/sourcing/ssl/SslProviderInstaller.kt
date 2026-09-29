package ac.mdiq.podcini.sourcing.ssl

import ac.mdiq.podcini.R
import ac.mdiq.podcini.utils.localizedString
import ac.mdiq.podcini.utils.Logt
import org.conscrypt.Conscrypt
import java.security.Security

object SslProviderInstaller {
    fun install() {
        // Insert bundled conscrypt as highest security provider (overrides OS version).
        runCatching {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }.onFailure { e ->
            Logt("Security", localizedString(R.string.message_failed_to_install_conscrypt_provider_using_system_default_securit, (e.message).toString()))
        }
    }
}
