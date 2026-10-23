package io.github.elvismanchkin.taskondemand.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import io.github.elvismanchkin.taskondemand.core.ServerKind

/** Passwords and tokens go to the IDE password safe (OS keychain / KeePass), keyed by server kind and URL. */
object Credentials {

    private fun attributes(kind: ServerKind, url: String, username: String) =
        CredentialAttributes(generateServiceName("Task on Demand", "${kind.name}:${url.trim().trimEnd('/')}"), username)

    fun get(kind: ServerKind, url: String, username: String): String? =
        PasswordSafe.instance.getPassword(attributes(kind, url, username))

    fun set(kind: ServerKind, url: String, username: String, secret: String?) {
        PasswordSafe.instance.setPassword(attributes(kind, url, username), secret?.takeIf { it.isNotEmpty() })
    }
}
