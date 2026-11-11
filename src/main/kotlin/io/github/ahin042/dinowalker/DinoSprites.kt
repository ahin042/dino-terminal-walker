package io.github.ahin042.dinowalker

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.scale.JBUIScale
import java.awt.Component
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Loads the bundled pixel-art PNGs (no recoloring, no resampling). A missing or broken resource is
 * logged and reported as `null`; callers simply skip drawing.
 */
internal object DinoSprites {
    private val LOG = logger<DinoSprites>()

    const val DINO_WIDTH = 44
    const val DINO_HEIGHT = 47
    const val METEOR_SIZE = 24

    /** Scale factor at or above which the `@2x` images are used (see ASSETS.md). */
    private const val HIDPI_THRESHOLD = 1.5f

    /**
     * Background color of the Chromium offline page (`#F7F7F7`). The 200% Chromium sprite sheet has a few
     * pixels of it inside the T-Rex's foot (`dino_run1@2x`, `dino_left_run1@2x`); in the browser they blend
     * into the page, on a dark IDE they show up as a white dot. Those exact pixels are drawn as transparent.
     */
    private const val CHROMIUM_PAGE_BACKGROUND = 0xF7F7F7

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
                val decoded = stream.use { ImageIO.read(it) }
                if (decoded == null) LOG.warn("Dino Walker: sprite resource could not be decoded: $path")
                decoded?.let(::withoutPageBackground)
            }
        }
        catch (e: Exception) {
            LOG.warn("Dino Walker: failed to load sprite $path", e)
            null
        }
    }

    private fun withoutPageBackground(source: BufferedImage): BufferedImage {
        val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val argb = source.getRGB(x, y)
                val isPageBackground = (argb ushr 24) == 0xFF && (argb and 0xFFFFFF) == CHROMIUM_PAGE_BACKGROUND
                image.setRGB(x, y, if (isPageBackground) 0 else argb)
            }
        }
        return image
    }

    /** Pixel-art friendly rendering: nearest neighbor, no antialiasing. */
    fun applyPixelHints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
    }
}
