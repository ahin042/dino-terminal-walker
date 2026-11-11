package io.github.ahin042.dinowalker

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * File-system side of the IDE <-> shell bridge. Everything lives under
 * `<java.io.tmpdir>/dino-walker-<user>/`:
 *
 * ```
 * shell/dino-init.sh        shell functions (exit/start/dino), safe to `source` from an rc file
 * shell/dino-jediterm.sh    entry point passed to the IDE terminal through JEDITERM_SOURCE
 * shell/bin/dino            `dino start` / `dino exit` for any POSIX shell
 * shell/bin/dino.bat        the same for cmd.exe / PowerShell
 * projects/<hash>/command   written by the shell, read and deleted by the IDE
 * ```
 */
internal object DinoShellFiles {
    private val LOG = logger<DinoShellFiles>()

    const val ENV_STATE_DIR = "DINO_WALKER_STATE_DIR"
    const val ENV_INIT_SCRIPT = "DINO_WALKER_INIT"

    private val SHELL_RESOURCES = listOf(
        "dino-init.sh" to false,
        "dino-jediterm.sh" to false,
        "bin/dino" to true,
        "bin/dino.bat" to false,
    )

    class ShellFiles(val initScript: Path, val jeditermScript: Path, val binDir: Path)

    @Volatile
    private var installed: ShellFiles? = null

    val baseDir: Path by lazy {
        val user = (System.getProperty("user.name") ?: "user").replace(Regex("[^A-Za-z0-9._-]"), "_")
        Path.of(System.getProperty("java.io.tmpdir"), "dino-walker-$user")
    }

    /** Copies the shell scripts out of the plugin jar (once per IDE session). Returns null on failure. */
    fun install(): ShellFiles? {
        installed?.let { return it }
        synchronized(this) {
            installed?.let { return it }
            return try {
                val shellDir = createPrivateDirectories(baseDir.resolve("shell"))
                createPrivateDirectories(shellDir.resolve("bin"))
                for ((name, executable) in SHELL_RESOURCES) {
                    copyResource("/shell/$name", shellDir.resolve(name), executable)
                }
                ShellFiles(shellDir.resolve("dino-init.sh"), shellDir.resolve("dino-jediterm.sh"), shellDir.resolve("bin"))
                    .also { installed = it }
            }
            catch (e: Exception) {
                LOG.warn("Dino Walker: could not install shell helper scripts", e)
                null
            }
        }
    }

    /** Per-project directory the shell writes its `command` file into. */
    fun projectStateDir(projectHash: String): Path? {
        return try {
            createPrivateDirectories(baseDir.resolve("projects").resolve(projectHash.replace(Regex("[^A-Za-z0-9._-]"), "_")))
        }
        catch (e: Exception) {
            LOG.warn("Dino Walker: could not create the state directory", e)
            null
        }
    }

    private fun copyResource(resource: String, target: Path, executable: Boolean) {
        val stream = DinoShellFiles::class.java.getResourceAsStream(resource)
            ?: throw IOException("Missing plugin resource $resource")
        // Write next to the target and move into place, so a shell (or another IDE instance)
        // never sees a half-written script.
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            if (!SystemInfo.isWindows) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString(if (executable) "rwxr-xr-x" else "rw-r--r--"))
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun createPrivateDirectories(dir: Path): Path {
        Files.createDirectories(dir)
        if (!SystemInfo.isWindows) {
            // Only the current user may drop commands into these directories.
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
        return dir
    }
}
