package io.github.ahin042.dinowalker

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.ScalableIcon
import com.intellij.ui.scale.JBUIScale
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.Icon
import kotlin.math.roundToInt

/**
 * Loads the bundled pixel-art PNGs exactly as shipped (no recoloring, no resampling at load time).
 * A missing or broken resource is logged once and reported as `null`; callers simply skip drawing.
 */
internal object DinoSprites {
    private val LOG = logger<DinoSprites>()

    const val DINO_WIDTH = 44
    const val DINO_HEIGHT = 47
    const val METEOR_SIZE = 24

    /** Scale factor at or above which the `@2x` images are used (see ASSETS.md). */
    private const val HIDPI_THRESHOLD = 1.5f

    enum class Sprite(val baseName: String) {
        RUN_RIGHT_1("dino_run1"),
        RUN_RIGHT_2("dino_run2"),
        RUN_LEFT_1("dino_left_run1"),
        RUN_LEFT_2("dino_left_run2"),
        METEOR_1("meteor_frame1"),
        METEOR_2("meteor_frame2"),
        METEOR_REV_1("meteor_rev_frame1"),
        METEOR_REV_2("meteor_rev_frame2"),
    }

    private val cache = HashMap<String, BufferedImage?>()

    fun get(sprite: Sprite, component: Component?): BufferedImage? {
        val suffix = if (JBUIScale.sysScale(component) >= HIDPI_THRESHOLD) "@2x" else "@1x"
        val path = "/assets/${sprite.baseName}$suffix.png"
        return synchronized(cache) { cache.getOrPut(path) { load(path) } }
    }

    private fun load(path: String): BufferedImage? {
        return try {
            val stream = DinoSprites::class.java.getResourceAsStream(path)
            if (stream == null) {
                LOG.warn("Dino Walker: sprite resource not found: $path")
                null
            }
            else {
                stream.use { ImageIO.read(it) }.also {
                    if (it == null) LOG.warn("Dino Walker: sprite resource could not be decoded: $path")
                }
            }
        }
        catch (e: Exception) {
            LOG.warn("Dino Walker: failed to load sprite $path", e)
            null
        }
    }

    /** Pixel-art friendly rendering: nearest neighbor, no antialiasing. */
    fun applyPixelHints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
    }

    /**
     * Tool window icon: an unscaled 16×16 crop of the dino's head from `dino_run1`.
     * Pixels are copied 1:1 (2:1 from the `@2x` image on HiDPI screens), never resampled smoothly.
     */
    class HeadIcon(size: Int = BASE_SIZE) : ScalableIcon {
        private val size = size.coerceIn(1, DINO_WIDTH)

        // Larger icons show a larger window around the head instead of enlarging pixels.
        private val cropX = minOf(HEAD_CROP_X, DINO_WIDTH - this.size)
        private val cropY = minOf(HEAD_CROP_Y, DINO_HEIGHT - this.size)

        override fun getIconWidth() = size

        override fun getIconHeight() = size

        override fun getScale(): Float = size.toFloat() / BASE_SIZE

        /** New UI tool window stripes ask for their own size (e.g. 20 px); see SquareStripeButton. */
        override fun scale(scaleFactor: Float): Icon = HeadIcon((size * scaleFactor).roundToInt())

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val image = get(Sprite.RUN_RIGHT_1, c) ?: return
            val factor = image.width / DINO_WIDTH
            val g2 = g.create() as Graphics2D
            try {
                applyPixelHints(g2)
                g2.drawImage(
                    image,
                    x, y, x + size, y + size,
                    cropX * factor, cropY * factor, (cropX + size) * factor, (cropY + size) * factor,
                    null,
                )
            }
            catch (e: Exception) {
                LOG.warn("Dino Walker: icon paint failed", e)
            }
            finally {
                g2.dispose()
            }
        }

        private companion object {
            const val BASE_SIZE = 16
            const val HEAD_CROP_X = 24
            const val HEAD_CROP_Y = 1
        }
    }
}
