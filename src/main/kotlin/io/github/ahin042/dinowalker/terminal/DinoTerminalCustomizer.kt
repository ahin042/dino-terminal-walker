package io.github.ahin042.dinowalker.terminal

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import io.github.ahin042.dinowalker.DinoProjectService
import io.github.ahin042.dinowalker.DinoShellFiles
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer
import java.io.File

/**
 * Prepares every new local terminal session of the project:
 *
 * - `DINO_WALKER_STATE_DIR`: where `__dino_notify` / `dino` write the `command` file.
 * - `DINO_WALKER_INIT`: path of `dino-init.sh`, for users who source it from their rc file.
 * - `PATH`: the extracted `bin/` directory goes first, so `dino start` / `dino exit` work in any shell.
 * - `JEDITERM_SOURCE` (bash and zsh only): the IDE's own shell integration scripts
 *   (`bash-integration.bash`, `zsh-integration.zsh`) source this file after the user's rc files,
 *   which is where the `exit`/`start` wrappers get defined. A value set by another customizer
 *   (e.g. a Python virtualenv activation) is preserved and sourced first by `dino-jediterm.sh`.
 *
 * Registered with `order="last"` so it sees what other customizers put into `JEDITERM_SOURCE`.
 */
class DinoTerminalCustomizer : LocalTerminalCustomizer() {

    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<String>,
        envs: MutableMap<String, String>,
    ): Array<String> {
        try {
            customize(project, command, envs)
        }
        catch (e: Exception) {
            if (e is ControlFlowException) throw e
            LOG.warn("Dino Walker: terminal customization failed; `dino start` / Find Action still work", e)
        }
        return command
    }

    private fun customize(project: Project, command: Array<String>, envs: MutableMap<String, String>) {
        val service = DinoProjectService.getInstance(project)
        val stateDir = service.stateDir ?: return
        val files = DinoShellFiles.install() ?: return

        envs[DinoShellFiles.ENV_STATE_DIR] = stateDir.toString()
        envs[DinoShellFiles.ENV_INIT_SCRIPT] = files.initScript.toString()
        prependToPath(envs, files.binDir.toString())

        if (shellName(command) in SOURCING_SHELLS) {
            envs.remove(JEDITERM_SOURCE)?.let { envs[PREV_SOURCE] = it }
            envs.remove(JEDITERM_SOURCE_ARGS)?.let { envs[PREV_SOURCE_ARGS] = it }
            envs.remove(JEDITERM_SOURCE_SINGLE_ARG)?.let { envs[PREV_SOURCE_SINGLE_ARG] = it }
            envs[JEDITERM_SOURCE] = files.jeditermScript.toString()
        }

        service.ensurePolling()
    }

    private fun prependToPath(envs: MutableMap<String, String>, dir: String) {
        // On Windows the variable is usually spelled "Path", and the map may be case-sensitive.
        val key = if (SystemInfo.isWindows) envs.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "Path" else "PATH"
        val current = envs[key] ?: System.getenv(key).orEmpty()
        if (current.split(File.pathSeparator).contains(dir)) return
        envs[key] = if (current.isEmpty()) dir else dir + File.pathSeparator + current
    }

    private fun shellName(command: Array<String>): String {
        val executable = command.firstOrNull() ?: return ""
        return File(executable).name.lowercase().removeSuffix(".exe")
    }

    companion object {
        private val LOG = logger<DinoTerminalCustomizer>()

        private val SOURCING_SHELLS = setOf("bash", "zsh")

        private const val JEDITERM_SOURCE = "JEDITERM_SOURCE"
        private const val JEDITERM_SOURCE_ARGS = "JEDITERM_SOURCE_ARGS"
        private const val JEDITERM_SOURCE_SINGLE_ARG = "JEDITERM_SOURCE_SINGLE_ARG"
        private const val PREV_SOURCE = "DINO_WALKER_PREV_SOURCE"
        private const val PREV_SOURCE_ARGS = "DINO_WALKER_PREV_SOURCE_ARGS"
        private const val PREV_SOURCE_SINGLE_ARG = "DINO_WALKER_PREV_SOURCE_SINGLE_ARG"
    }
}
