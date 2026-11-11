package io.github.ahin042.dinowalker

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Per-project controller: owns the command-file poller and the [DinoOverlay] that walks on this project's
 * Terminal tool window. Each project gets its own state directory, so terminals of different projects
 * never talk to the wrong dino.
 */
@Service(Service.Level.PROJECT)
class DinoProjectService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private var overlay: DinoOverlay? = null

    @Volatile
    private var pollJob: Job? = null

    /** Directory exported to the shell as `DINO_WALKER_STATE_DIR`; null if it could not be created. */
    val stateDir: Path? by lazy { DinoShellFiles.projectStateDir(project.locationHash) }

    /** Starts (or restarts) the walk on top of the Terminal tool window. Safe to call from any thread. */
    fun start() = onEdt {
        val terminal = terminalToolWindow()
        if (terminal != null && !terminal.isVisible) {
            // e.g. `Dino: Start` from Find Action while the terminal is closed: the dino needs its ground.
            terminal.show { overlay().start() }
        }
        else {
            overlay().start()
        }
    }

    /** Drops the meteor; the dino disappears when the animation ends. Safe to call from any thread. */
    fun exit() = onEdt {
        overlay?.die()
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

    override fun dispose() = Unit

    private fun overlay(): DinoOverlay =
        overlay ?: DinoOverlay(::groundComponent).also {
            overlay = it
            Disposer.register(this, it)
        }

    /**
     * What the dino stands on: the Terminal tool window while it is shown, otherwise (an IDE without the
     * Terminal plugin) the status bar. Returns null while the terminal is hidden; the overlay then keeps
     * watching the component it already has and resumes when that one is shown again.
     */
    private fun groundComponent(): JComponent? {
        if (project.isDisposed) return null
        val terminal = terminalToolWindow()
        if (terminal != null) return if (terminal.isVisible) terminal.component else null
        return WindowManager.getInstance().getIdeFrame(project)?.statusBar?.component
    }

    private fun terminalToolWindow(): ToolWindow? {
        if (project.isDisposed) return null
        return ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
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
