package io.github.ahin042.dinowalker

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.JBColor
import io.github.ahin042.dinowalker.DinoSprites.DINO_HEIGHT
import io.github.ahin042.dinowalker.DinoSprites.DINO_WIDTH
import io.github.ahin042.dinowalker.DinoSprites.METEOR_SIZE
import io.github.ahin042.dinowalker.DinoSprites.Sprite
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.roundToInt

/**
 * The strip the dino walks on.
 *
 * Everything here runs on the EDT: public methods are called from the EDT by [DinoProjectService],
 * and the only timer is a [javax.swing.Timer]. One 16 ms tick drives movement, leg animation and the
 * meteor; each of those accumulates real elapsed time on its own, so the 83 ms leg cadence does not
 * depend on how often (or how late) the tick fires.
 */
internal class DinoPanel(private val onDeathFinished: () -> Unit) : JPanel(null), Disposable {

    enum class State { IDLE, WALKING, DYING }

    private enum class DeathPhase { FALLING, BLINKING, GONE }

    var state: State = State.IDLE
        private set

    private val timer = Timer(TICK_MS) { tick() }.apply {
        isRepeats = true
        isCoalesce = true
    }
    private var lastTickNanos = 0L
    private var disposed = false

    // Walking
    private var dinoX = 0.0
    private var movingRight = true
    private var legFrameElapsed = 0.0
    private var legFrame = 0
    private var dinoVisible = true

    // Dying
    private var deathPhase = DeathPhase.FALLING
    private var phaseElapsed = 0.0
    private var meteorVisible = false
    private var meteorTowardsRight = false
    private var meteorFrame = 0
    private var meteorFrameElapsed = 0.0
    private var meteorStartX = 0.0
    private var meteorStartY = 0.0
    private var meteorEndX = 0.0
    private var meteorEndY = 0.0
    private var meteorX = 0.0
    private var meteorY = 0.0

    private val showingListener = HierarchyListener { e ->
        if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
            if (isShowing) onShown() else onHidden()
        }
    }

    init {
        isOpaque = true
        background = JBColor.PanelBackground
        preferredSize = Dimension(DINO_WIDTH * 4, PANEL_HEIGHT)
        minimumSize = Dimension(DINO_WIDTH, PANEL_HEIGHT)
        addHierarchyListener(showingListener)
    }

    /** Starts walking from the left edge. Also revives the dino if the meteor animation is running. */
    fun start() {
        if (disposed) return
        when (state) {
            State.WALKING -> resumeTimerIfShowing()
            State.IDLE, State.DYING -> beginWalking()
        }
    }

    /**
     * Freezes the dino where it stands and drops the meteor on it. When the animation is over the
     * panel goes back to [State.IDLE] and [onDeathFinished] is called (the owner hides the tool window).
     * If the panel is not on screen there is nothing to animate, so it goes straight to the end.
     */
    fun die() {
        if (disposed || state != State.WALKING) return
        if (!isShowing) {
            finishDeath()
            return
        }
        beginDeath()
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        removeHierarchyListener(showingListener)
        state = State.IDLE
    }

    private fun onShown() {
        if (disposed) return
        when (state) {
            // Opened by hand (stripe button, Window menu...) or by `start dino`: either way, walk.
            State.IDLE -> beginWalking()
            State.WALKING -> resumeTimerIfShowing()
            State.DYING -> Unit
        }
    }

    private fun onHidden() {
        timer.stop()
        // Nobody can see the meteor any more; skip to the end so that state stays consistent.
        if (state == State.DYING) finishDeath()
    }

    private fun beginWalking() {
        state = State.WALKING
        dinoX = 0.0
        movingRight = true
        legFrame = 0
        legFrameElapsed = 0.0
        dinoVisible = true
        meteorVisible = false
        resumeTimerIfShowing()
        repaint()
    }

    private fun beginDeath() {
        state = State.DYING
        deathPhase = DeathPhase.FALLING
        phaseElapsed = 0.0
        dinoVisible = true

        val dinoCenterX = dinoX + DINO_WIDTH / 2.0
        // Dino on the right half -> meteor comes from the upper left heading down-right (the "rev" art,
        // tail pointing up-left). Dino on the left half -> from the upper right heading down-left.
        meteorTowardsRight = dinoCenterX > width / 2.0
        meteorFrame = 0
        meteorFrameElapsed = 0.0

        // The meteor's body sits in the lower part of the 24×24 image; aim that body at the dino's back.
        val bodyOffsetX = if (meteorTowardsRight) METEOR_BODY_RIGHT_X else METEOR_BODY_LEFT_X
        meteorEndX = dinoCenterX - bodyOffsetX
        meteorEndY = dinoY() + DINO_HIT_Y - METEOR_BODY_Y
        meteorStartY = -METEOR_SIZE.toDouble() - 2.0
        // The tail art is drawn at 45°, so travel at 45° as well.
        val drop = meteorEndY - meteorStartY
        meteorStartX = if (meteorTowardsRight) meteorEndX - drop else meteorEndX + drop
        meteorX = meteorStartX
        meteorY = meteorStartY
        meteorVisible = true

        resumeTimerIfShowing()
        repaint()
    }

    private fun finishDeath() {
        timer.stop()
        state = State.IDLE
        meteorVisible = false
        dinoVisible = false
        repaint()
        onDeathFinished()
    }

    private fun resumeTimerIfShowing() {
        if (disposed || state == State.IDLE || !isShowing) return
        if (!timer.isRunning) {
            lastTickNanos = System.nanoTime()
            timer.start()
        }
    }

    private fun tick() {
        if (disposed || state == State.IDLE) {
            timer.stop()
            return
        }
        try {
            val now = System.nanoTime()
            // Timers fire late now and then (GC, a busy EDT). Animation clocks use the real elapsed
            // time so cadence and timings stay exact; only the walking step is clamped, so that a
            // long stall does not teleport the dino.
            val dt = ((now - lastTickNanos) / 1_000_000.0).coerceAtLeast(0.0)
            lastTickNanos = now

            legFrameElapsed += dt
            if (legFrameElapsed >= RUN_FRAME_MS) {
                val flips = (legFrameElapsed / RUN_FRAME_MS).toLong()
                legFrameElapsed -= flips * RUN_FRAME_MS
                if (flips % 2 == 1L) legFrame = 1 - legFrame
            }

            when (state) {
                State.WALKING -> walk(dt.coerceAtMost(MAX_STEP_MS))
                State.DYING -> advanceDeath(dt)
                State.IDLE -> Unit
            }
            repaint()
        }
        catch (e: Exception) {
            LOG.warn("Dino Walker: animation tick failed; stopping the animation", e)
            timer.stop()
            state = State.IDLE
            repaint()
        }
    }

    private fun walk(dtMs: Double) {
        val maxX = (width - DINO_WIDTH).toDouble()
        if (maxX <= 0.0) {
            dinoX = 0.0
            return
        }
        val step = WALK_SPEED_PX_PER_SEC * dtMs / 1000.0
        dinoX += if (movingRight) step else -step
        if (dinoX >= maxX) {
            dinoX = maxX
            movingRight = false
        }
        else if (dinoX <= 0.0) {
            dinoX = 0.0
            movingRight = true
        }
    }

    private fun advanceDeath(dtMs: Double) {
        phaseElapsed += dtMs
        when (deathPhase) {
            DeathPhase.FALLING -> {
                meteorFrameElapsed += dtMs
                if (meteorFrameElapsed >= METEOR_FRAME_MS) {
                    val flips = (meteorFrameElapsed / METEOR_FRAME_MS).toLong()
                    meteorFrameElapsed -= flips * METEOR_FRAME_MS
                    if (flips % 2 == 1L) meteorFrame = 1 - meteorFrame
                }
                val t = (phaseElapsed / METEOR_FALL_MS).coerceIn(0.0, 1.0)
                val eased = t * t // ease-in: slow start, accelerating towards the impact
                meteorX = meteorStartX + (meteorEndX - meteorStartX) * eased
                meteorY = meteorStartY + (meteorEndY - meteorStartY) * eased
                if (t >= 1.0) {
                    meteorVisible = false
                    deathPhase = DeathPhase.BLINKING
                    phaseElapsed = 0.0
                    dinoVisible = false
                }
            }
            DeathPhase.BLINKING -> {
                // off, on, off, on, off, on: three blinks of BLINK_MS each half.
                val halfBlinks = (phaseElapsed / BLINK_MS).toInt()
                if (halfBlinks >= BLINK_COUNT * 2) {
                    dinoVisible = false
                    deathPhase = DeathPhase.GONE
                    phaseElapsed = 0.0
                }
                else {
                    dinoVisible = halfBlinks % 2 == 1
                }
            }
            DeathPhase.GONE -> {
                if (phaseElapsed >= CLOSE_DELAY_MS) finishDeath()
            }
        }
    }

    private fun dinoY(): Int = maxOf(0, height - DINO_HEIGHT - GROUND_PADDING)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (state == State.IDLE) return
        val g2 = g.create() as Graphics2D
        try {
            DinoSprites.applyPixelHints(g2)
            if (dinoVisible) {
                val sprite = when {
                    movingRight && legFrame == 0 -> Sprite.RUN_RIGHT_1
                    movingRight -> Sprite.RUN_RIGHT_2
                    legFrame == 0 -> Sprite.RUN_LEFT_1
                    else -> Sprite.RUN_LEFT_2
                }
                DinoSprites.get(sprite, this)?.let {
                    g2.drawImage(it, dinoX.roundToInt(), dinoY(), DINO_WIDTH, DINO_HEIGHT, null)
                }
            }
            if (state == State.DYING && meteorVisible) {
                val sprite = when {
                    meteorTowardsRight && meteorFrame == 0 -> Sprite.METEOR_REV_1
                    meteorTowardsRight -> Sprite.METEOR_REV_2
                    meteorFrame == 0 -> Sprite.METEOR_1
                    else -> Sprite.METEOR_2
                }
                DinoSprites.get(sprite, this)?.let {
                    g2.drawImage(it, meteorX.roundToInt(), meteorY.roundToInt(), METEOR_SIZE, METEOR_SIZE, null)
                }
            }
        }
        catch (e: Exception) {
            LOG.warn("Dino Walker: paint failed", e)
        }
        finally {
            g2.dispose()
        }
    }

    companion object {
        private val LOG = logger<DinoPanel>()

        /** Leg animation period, straight from Chromium's trex.ts (`msPerFrame: 1000 / 12`) = 83 ms. */
        const val RUN_FRAME_MS: Double = (1000 / 12).toDouble()

        /** Walking speed in logical pixels per second. */
        const val WALK_SPEED_PX_PER_SEC = 60.0

        /** Position update period (~60 fps). */
        const val TICK_MS = 16

        const val PANEL_HEIGHT = 60
        private const val GROUND_PADDING = 4
        private const val MAX_STEP_MS = 250.0

        const val METEOR_FALL_MS = 700.0
        const val METEOR_FRAME_MS = 70.0
        const val BLINK_MS = 80.0
        const val BLINK_COUNT = 3
        const val CLOSE_DELAY_MS = 400.0

        // Centre of the meteor's rocky body inside the 24×24 art (see meteor_*.png).
        private const val METEOR_BODY_LEFT_X = 7.5   // meteor_*: body bottom-left, tail up-right
        private const val METEOR_BODY_RIGHT_X = 16.5 // meteor_rev_*: body bottom-right, tail up-left
        private const val METEOR_BODY_Y = 16.5

        /** Where on the dino (from its top) the meteor lands: its back. */
        private const val DINO_HIT_Y = 14.0
    }
}
