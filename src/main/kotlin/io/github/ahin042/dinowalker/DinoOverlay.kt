package io.github.ahin042.dinowalker

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.ui.JBUI
import io.github.ahin042.dinowalker.DinoSprites.DINO_HEIGHT
import io.github.ahin042.dinowalker.DinoSprites.DINO_WIDTH
import io.github.ahin042.dinowalker.DinoSprites.METEOR_SIZE
import io.github.ahin042.dinowalker.DinoSprites.Sprite
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JRootPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

/**
 * A transparent, click-through layer in the IDE window's [JLayeredPane]. The dino walks along the top
 * edge of the "ground" component (the Terminal tool window) and the meteor falls from above it.
 *
 * Everything here runs on the EDT: public methods are called from the EDT by [DinoProjectService],
 * and the only timer is a [javax.swing.Timer]. One 16 ms tick drives movement, leg animation and the
 * meteor; each of those accumulates real elapsed time on its own, so the 83 ms leg cadence does not
 * depend on how often (or how late) the tick fires.
 *
 * @param groundProvider the component to stand on, or null if there is none right now
 */
internal class DinoOverlay(private val groundProvider: () -> JComponent?) : JComponent(), Disposable {

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

    /** The component whose showing state we watch (pause when the terminal is hidden, resume when shown). */
    private var ground: JComponent? = null

    /** Y of the ground line inside this overlay. */
    private var groundY = 0

    // Walking
    private var dinoX = 0.0
    private var movingRight = true
    private var legFrameElapsed = 0.0
    private var legFrame = 0
    private var dinoVisible = true

    // Jumping (heights in px above the ground, velocities in px per 60 fps frame, both positive = up)
    private var jumping = false
    private var jumpHeight = 0.0
    private var jumpVelocity = 0.0
    private var reachedMinJumpHeight = false

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

    private val groundListener = HierarchyListener { e ->
        if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
            // Don't change the component tree from inside its own hierarchy notification.
            SwingUtilities.invokeLater { sync() }
        }
    }

    init {
        isOpaque = false
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    e.consume()
                    jump()
                }
            }
        })
    }

    /**
     * Only the dino itself takes mouse input (click it to jump); everywhere else clicks and hovers go to
     * whatever is underneath.
     */
    override fun contains(x: Int, y: Int): Boolean =
        state == State.WALKING && dinoVisible && dinoBounds().contains(x, y)

    /** Jumps like the original T-Rex. Ignored while already in the air or not walking. */
    fun jump() {
        if (disposed || state != State.WALKING || jumping) return
        jumping = true
        jumpHeight = 0.0
        jumpVelocity = INITIAL_JUMP_VELOCITY
        reachedMinJumpHeight = false
    }

    /** Starts walking from the left edge. Also revives the dino if the meteor animation is running. */
    fun start() {
        if (disposed) return
        if (state != State.WALKING) beginWalking()
        sync()
    }

    /**
     * Freezes the dino where it stands and drops the meteor on it; afterwards the overlay is removed and
     * the state is back to [State.IDLE]. If the dino is not on screen there is nothing to animate, so it
     * goes straight to the end.
     */
    fun die() {
        if (disposed || state != State.WALKING) return
        if (parent == null || !isVisible) {
            finishDeath()
            return
        }
        beginDeath()
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        state = State.IDLE
        observe(null)
        detach()
    }

    /**
     * Brings the overlay in line with the ground: attach and position it while the terminal is showing,
     * hide it and stop the timer while it is not.
     */
    private fun sync() {
        if (disposed) return
        if (state == State.IDLE) {
            timer.stop()
            observe(null)
            detach()
            return
        }

        val current = groundProvider() ?: ground
        observe(current)
        val placement = current?.takeIf { it.isShowing }?.let(::placementOver)
        if (placement == null) {
            timer.stop()
            isVisible = false
            // Nobody can see the meteor any more; skip to the end so that the state stays consistent.
            if (state == State.DYING) finishDeath()
            return
        }

        val (layeredPane, newBounds, newGroundY) = placement
        if (parent !== layeredPane) {
            detach()
            layeredPane.setLayer(this, JLayeredPane.PALETTE_LAYER)
            layeredPane.add(this)
        }
        if (bounds != newBounds || groundY != newGroundY) {
            bounds = newBounds
            groundY = newGroundY
            repaint()
        }
        isVisible = true
        if (!timer.isRunning) {
            lastTickNanos = System.nanoTime()
            timer.start()
        }
    }

    private data class Placement(val layeredPane: JLayeredPane, val bounds: Rectangle, val groundY: Int)

    private fun placementOver(groundComponent: JComponent): Placement? {
        val layeredPane = SwingUtilities.getRootPane(groundComponent)?.layeredPane ?: return null
        val panel = outerPanelOf(groundComponent)
        val panelBounds = SwingUtilities.convertRectangle(panel, Rectangle(0, 0, panel.width, panel.height), layeredPane)
        if (panelBounds.width <= 0) return null
        val top = maxOf(0, panelBounds.y - OVERLAY_HEIGHT)
        // If there is no room above the terminal (it fills the window), stand on its first rows instead.
        val height = maxOf(panelBounds.y - top, DINO_HEIGHT)
        return Placement(layeredPane, Rectangle(panelBounds.x, top, panelBounds.width, height), height)
    }

    /**
     * The tool window's content component sits below its header; climb to the panel that includes the
     * header (same width, at most a header's height taller) so the dino stands on the window's top edge.
     */
    private fun outerPanelOf(component: JComponent): Component {
        var panel: Component = component
        var candidate = component.parent
        while (candidate != null && candidate !is JRootPane && candidate !is JLayeredPane &&
               candidate.height <= component.height + JBUI.scale(MAX_HEADER_HEIGHT) &&
               candidate.width <= component.width + JBUI.scale(MAX_SIDE_INSETS)
        ) {
            panel = candidate
            candidate = candidate.parent
        }
        return panel
    }

    private fun observe(component: JComponent?) {
        if (ground === component) return
        ground?.removeHierarchyListener(groundListener)
        ground = component
        component?.addHierarchyListener(groundListener)
    }

    private fun detach() {
        val layeredPane = parent ?: return
        val area = bounds
        layeredPane.remove(this)
        layeredPane.repaint(area.x, area.y, area.width, area.height)
        isVisible = false
    }

    private fun beginWalking() {
        state = State.WALKING
        dinoX = 0.0
        movingRight = true
        legFrame = 0
        legFrameElapsed = 0.0
        dinoVisible = true
        meteorVisible = false
        jumping = false
        jumpHeight = 0.0
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
        // Aimed at the dino standing on the ground: a dino in mid-jump lands before the meteor arrives.
        val bodyOffsetX = if (meteorTowardsRight) METEOR_BODY_RIGHT_X else METEOR_BODY_LEFT_X
        meteorEndX = dinoCenterX - bodyOffsetX
        meteorEndY = groundDinoY() + DINO_HIT_Y - METEOR_BODY_Y
        meteorStartY = -METEOR_SIZE.toDouble() - 2.0
        // The tail art is drawn at 45°, so travel at 45° as well.
        val drop = meteorEndY - meteorStartY
        meteorStartX = if (meteorTowardsRight) meteorEndX - drop else meteorEndX + drop
        meteorX = meteorStartX
        meteorY = meteorStartY
        meteorVisible = true
        repaint()
    }

    private fun finishDeath() {
        timer.stop()
        state = State.IDLE
        meteorVisible = false
        dinoVisible = false
        observe(null)
        detach()
    }

    private fun tick() {
        if (disposed || state == State.IDLE) {
            timer.stop()
            return
        }
        try {
            sync()
            if (!timer.isRunning) return
            val before = spriteArea()

            val now = System.nanoTime()
            // Timers fire late now and then (GC, a busy EDT). Animation clocks use the real elapsed
            // time so cadence and timings stay exact; only the walking step is clamped, so that a
            // long stall does not teleport the dino.
            val dt = ((now - lastTickNanos) / 1_000_000.0).coerceAtLeast(0.0)
            lastTickNanos = now

            // Legs keep still in the air (there is no separate jumping frame among the assets).
            if (!jumping) {
                legFrameElapsed += dt
                if (legFrameElapsed >= RUN_FRAME_MS) {
                    val flips = (legFrameElapsed / RUN_FRAME_MS).toLong()
                    legFrameElapsed -= flips * RUN_FRAME_MS
                    if (flips % 2 == 1L) legFrame = 1 - legFrame
                }
            }

            if (jumping) updateJump(dt.coerceAtMost(MAX_STEP_MS))
            when (state) {
                State.WALKING -> walk(dt.coerceAtMost(MAX_STEP_MS))
                State.DYING -> advanceDeath(dt)
                State.IDLE -> Unit
            }

            // Only repaint where sprites were and are: the overlay spans the whole terminal width.
            if (parent != null) {
                val dirty = spriteArea()?.let { area -> before?.union(area) ?: area } ?: before
                dirty?.let { repaint(it) }
            }
        }
        catch (e: Exception) {
            LOG.warn("Dino Walker: animation tick failed; stopping the animation", e)
            timer.stop()
            state = State.IDLE
            detach()
        }
    }

    /** Port of Chromium's `Trex.updateJump` / `endJump` with the y axis flipped (height above ground). */
    private fun updateJump(dtMs: Double) {
        val framesElapsed = dtMs / JUMP_MS_PER_FRAME
        jumpHeight += (jumpVelocity * framesElapsed).roundToInt()
        jumpVelocity -= GRAVITY * framesElapsed

        if (jumpHeight > MIN_JUMP_HEIGHT) reachedMinJumpHeight = true
        if (jumpHeight > MAX_JUMP_HEIGHT && reachedMinJumpHeight && jumpVelocity > DROP_VELOCITY) {
            jumpVelocity = DROP_VELOCITY
        }
        if (jumpHeight <= 0.0) {
            jumping = false
            jumpHeight = 0.0
        }
    }

    private fun dinoBounds() = Rectangle(dinoX.roundToInt(), dinoY(), DINO_WIDTH, DINO_HEIGHT)

    private fun spriteArea(): Rectangle? {
        var area: Rectangle? = null
        if (dinoVisible || state == State.DYING) {
            area = dinoBounds().apply { grow(1, 1) }
        }
        if (state == State.DYING && meteorVisible) {
            val meteor = Rectangle(meteorX.roundToInt() - 1, meteorY.roundToInt() - 1, METEOR_SIZE + 2, METEOR_SIZE + 2)
            area = area?.union(meteor) ?: meteor
        }
        return area
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
                    jumping = false
                    jumpHeight = 0.0
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
                if (phaseElapsed >= REMOVE_DELAY_MS) finishDeath()
            }
        }
    }

    private fun groundDinoY(): Int = maxOf(0, groundY - DINO_HEIGHT)

    private fun dinoY(): Int = groundDinoY() - jumpHeight.roundToInt()

    override fun paintComponent(g: Graphics) {
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
        private val LOG = logger<DinoOverlay>()

        /** Leg animation period, straight from Chromium's trex.ts (`msPerFrame: 1000 / 12`) = 83 ms. */
        const val RUN_FRAME_MS: Double = (1000 / 12).toDouble()

        /** Walking speed in logical pixels per second. */
        const val WALK_SPEED_PX_PER_SEC = 60.0

        /** Position update period (~60 fps). */
        const val TICK_MS = 16

        /** Room above the terminal: the dino plus the meteor's fall. */
        private const val OVERLAY_HEIGHT = DINO_HEIGHT + 100
        private const val MAX_HEADER_HEIGHT = 80
        private const val MAX_SIDE_INSETS = 40
        private const val MAX_STEP_MS = 250.0

        const val METEOR_FALL_MS = 700.0
        const val METEOR_FRAME_MS = 70.0
        const val BLINK_MS = 80.0
        const val BLINK_COUNT = 3
        const val REMOVE_DELAY_MS = 400.0

        // Centre of the meteor's rocky body inside the 24×24 art (see meteor_*.png).
        private const val METEOR_BODY_LEFT_X = 7.5   // meteor_*: body bottom-left, tail up-right
        private const val METEOR_BODY_RIGHT_X = 16.5 // meteor_rev_*: body bottom-right, tail up-left
        private const val METEOR_BODY_Y = 16.5

        /** Where on the dino (from its top) the meteor lands: its back. */
        private const val DINO_HIT_Y = 14.0

        // Jump, from Chromium's trex.ts (normalJumpConfig, defaultTrexConfig.dropVelocity, JUMPING
        // msPerFrame). The original measures max height from the top of its 150 px canvas, whose ground
        // is at 150 - 47 - 10 (bottomPad) = 93; here both limits are expressed as height above ground.
        private const val JUMP_MS_PER_FRAME = 1000.0 / 60
        private const val INITIAL_JUMP_VELOCITY = 10.0
        private const val GRAVITY = 0.6
        private const val DROP_VELOCITY = 5.0
        private const val MIN_JUMP_HEIGHT = 30.0
        private const val MAX_JUMP_HEIGHT = 93.0 - 30.0
    }
}
