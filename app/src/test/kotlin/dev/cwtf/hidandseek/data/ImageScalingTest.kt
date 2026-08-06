package dev.cwtf.hidandseek.data

import dev.cwtf.hidandseek.data.chat.ImageScaling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageScalingTest {

    @Test
    fun `a landscape photo is limited by its width`() {
        val (w, h) = ImageScaling.fit(4032, 3024, maxEdge = 1568)
        assertEquals(1568, w)
        assertEquals(1176, h)
    }

    @Test
    fun `a portrait photo is limited by its height`() {
        val (w, h) = ImageScaling.fit(3024, 4032, maxEdge = 1568)
        assertEquals(1568, h)
        assertEquals(1176, w)
    }

    @Test
    fun `aspect ratio is preserved`() {
        val (w, h) = ImageScaling.fit(1920, 1080, maxEdge = 800)
        assertEquals(1920.0 / 1080.0, w.toDouble() / h, 0.01)
    }

    @Test
    fun `smaller images are left alone rather than upscaled`() {
        // Upscaling costs tokens and adds nothing a model can use.
        assertEquals(800 to 600, ImageScaling.fit(800, 600, maxEdge = 1568))
    }

    @Test
    fun `an image exactly at the limit is unchanged`() {
        assertEquals(1568 to 900, ImageScaling.fit(1568, 900, maxEdge = 1568))
    }

    @Test
    fun `an extreme aspect ratio never collapses to zero`() {
        val (w, h) = ImageScaling.fit(10_000, 3, maxEdge = 1568)
        assertEquals(1568, w)
        assertTrue(h >= 1, "a dimension must never round down to zero")
    }

    @Test
    fun `zero dimensions are rejected`() {
        assertFailsWith<IllegalArgumentException> { ImageScaling.fit(0, 100, 1568) }
    }

    @Test
    fun `sample size halves until close to the target`() {
        assertEquals(1, ImageScaling.sampleSize(1600, 1200, 1568))
        assertEquals(2, ImageScaling.sampleSize(4032, 3024, 1568))
        assertEquals(4, ImageScaling.sampleSize(8000, 6000, 1568))
    }

    @Test
    fun `sample size never drops below one`() {
        assertEquals(1, ImageScaling.sampleSize(100, 100, 1568))
    }
}
