package cz.flos.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The AES key that encrypts saved passwords and tokens on Windows. It is kept in the user's own
 * profile folder (%APPDATA%\Flos), apart from the settings in the registry, so a registry export
 * or a screenshot of it no longer shows any password.
 */
object DesktopSecretKey {
    fun getOrCreate(dir: File): SecretKey? = runCatching {
        val f = File(dir, "settings.key")
        val bytes = if (f.isFile && f.length() == 32L) f.readBytes() else ByteArray(32).also {
            SecureRandom().nextBytes(it)
            f.writeBytes(it)
            // Only the current user may read it (works on Linux/macOS; on Windows %APPDATA% is already per user).
            runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
            runCatching { Files.setAttribute(f.toPath(), "dos:hidden", true) }
        }
        SecretKeySpec(bytes, "AES")
    }.getOrNull()
}
