package io.github.elvismanchkin.taskondemand.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.elvismanchkin.taskondemand.core.ServerKind

/**
 * Passwords and tokens go to the IDE password safe (OS keychain / KeePass), keyed by server kind, URL and username.
 * The password safe may block or prompt, so every call here belongs off the EDT.
 */
object Credentials {

    /** One thread, so saves from the settings page apply in order and [afterPendingWrites] runs after them. */
    private val writer = AppExecutorUtil.createBoundedApplicationPoolExecutor("Task on Demand credentials", 1)

    private fun attributes(kind: ServerKind, url: String, username: String) =
        CredentialAttributes(generateServiceName("Task on Demand", "${kind.name}:${url.trim().trimEnd('/')}"), username)

    fun get(kind: ServerKind, url: String, username: String): String? =
        PasswordSafe.instance.getPassword(attributes(kind, url, username))

    fun set(kind: ServerKind, url: String, username: String, secret: String?) {
        PasswordSafe.instance.setPassword(attributes(kind, url, username), secret?.takeIf { it.isNotEmpty() })
    }

    /**
     * Saves in the background. [secret] null means "unchanged": the stored one is kept, or moved when the URL or
     * username changed. The entry under the old URL/username is removed, so changed settings leave nothing behind.
     */
    fun saveAsync(kind: ServerKind, oldUrl: String, oldUser: String, newUrl: String, newUser: String, secret: String?) {
        writer.execute {
            val keyChanged = attributes(kind, oldUrl, oldUser) != attributes(kind, newUrl, newUser)
            if (secret == null && !keyChanged) return@execute
            val value = secret ?: get(kind, oldUrl, oldUser)
            if (keyChanged && oldUrl.isNotBlank()) set(kind, oldUrl, oldUser, null)
            set(kind, newUrl, newUser, value)
        }
    }

    /** Reads after all earlier [saveAsync] calls are done (blocking; off the EDT). */
    fun getAfterPendingWrites(kind: ServerKind, url: String, username: String): String? =
        writer.submit<String?> { get(kind, url, username) }.get()

    /** Runs [action] on the background writer once all earlier [saveAsync] calls are done. */
    fun afterPendingWrites(action: () -> Unit) = writer.execute(action)
}
