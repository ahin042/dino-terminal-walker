package io.github.ahin042.dinowalker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Per-project controller: owns the command-file poller and drives the [DinoPanel] of this project's
 * tool window. Each project gets its own state directory, so terminals of different projects never
 * talk to the wrong dino.
 */
@Service(Service.Level.PROJECT)
class DinoProjectService(private val project: Project, private val scope: CoroutineScope) {

    private var panel: DinoPanel? = null

    @Volatile
    private var pollJob: Job? = null

    /** Directory exported to the shell as `DINO_WALKER_STATE_DIR`; null if it could not be created. */
    val stateDir: Path? by lazy { DinoShellFiles.projectStateDir(project.locationHash) }

    /** Shows the tool window and starts (or restarts) the walk. Safe to call from any thread. */
    fun start() = onEdt {
        val toolWindow = toolWindow() ?: return@onEdt
        keepTerminalVisible(toolWindow)
        toolWindow.show { panel?.start() }
    }

    /** Drops the meteor; the tool window closes itself when the animation ends. Safe from any thread. */
    fun exit() = onEdt {
        val current = panel
        if (current == null || current.state != DinoPanel.State.WALKING) return@onEdt
        current.die()
    }

    /**
     * Starts watching `<stateDir>/command` on a background coroutine (never on the EDT).
     * Called when a terminal is opened for this project; cheap to call repeatedly.
     */
    fun ensurePolling() {
        if (pollJob != null) return
        synchronized(this) {
            if (pollJob != null) return
            val file = stateDir?.resolve(COMMAND_FILE_NAME) ?: return
            // A command left over from a previous IDE session must not fire now.
            runCatching { Files.deleteIfExists(file) }
            pollJob = scope.launch(Dispatchers.IO) {
                // Keep all work in pollOnce(): no locals live across delay(), so the compiler does not
                // need coroutine helpers that are missing from the Kotlin stdlib bundled with 2024.2.
                while (true) {
                    delay(POLL_INTERVAL_MS)
                    pollOnce(file)
                }
            }
        }
    }

    private fun pollOnce(file: Path) {
        when (val command = takeCommand(file)) {
            null -> Unit
            "start" -> start()
            "exit", "stop" -> exit()
            else -> LOG.info("Dino Walker: ignoring unknown command '$command'")
        }
    }

    internal fun attachPanel(newPanel: DinoPanel) {
        panel = newPanel
        Disposer.register(newPanel) { if (panel === newPanel) panel = null }
    }

    internal fun onDeathFinished() {
        toolWindow()?.hide(null)
    }

    private fun toolWindow(): ToolWindow? {
        if (project.isDisposed) return null
        return ToolWindowManager.getInstance(project).getToolWindow(DinoToolWindowFactory.ID)
    }

    /**
     * Two docked tool windows sharing an anchor and side hide each other. If Dino would replace the
     * visible Terminal, move Dino to the other half of that anchor (side by side for bottom, stacked
     * for left/right) so that both stay visible.
     */
    private fun keepTerminalVisible(dino: ToolWindow) {
        if (dino.isVisible) return
        val terminal = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID) ?: return
        if (terminal.isVisible &&
            terminal.type == ToolWindowType.DOCKED &&
            dino.type == ToolWindowType.DOCKED &&
            terminal.anchor == dino.anchor &&
            terminal.isSplitMode == dino.isSplitMode
        ) {
            dino.setSplitMode(!terminal.isSplitMode, null)
        }
    }

    private fun onEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        val safe = Runnable {
            try {
                action()
            }
            catch (e: Exception) {
                LOG.warn("Dino Walker: command failed", e)
            }
        }
        if (app.isDispatchThread) safe.run() else app.invokeLater(safe, project.disposed)
    }

    private fun takeCommand(file: Path): String? {
        if (!Files.exists(file)) return null
        return try {
            val text = Files.readString(file)
            Files.deleteIfExists(file)
            text.trim().lowercase().ifEmpty { null }
        }
        catch (_: NoSuchFileException) {
            null
        }
        catch (e: Exception) {
            LOG.warn("Dino Walker: could not read $file", e)
            runCatching { Files.deleteIfExists(file) }
            null
        }
    }

    companion object {
        private val LOG = logger<DinoProjectService>()

        const val COMMAND_FILE_NAME = "command"
        const val POLL_INTERVAL_MS = 150L
        private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"

        fun getInstance(project: Project): DinoProjectService = project.service()
    }
}
