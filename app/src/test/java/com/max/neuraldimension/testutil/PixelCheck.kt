package com.max.neuraldimension.testutil

import android.graphics.BitmapFactory
import java.io.File
import org.junit.Assert.assertTrue

/**
 * Supplemental sanity metrics for captured PNGs. These NEVER replace visual
 * inspection of the actual image: they only guard against a silently blank or
 * flat capture being archived as evidence.
 */
object PixelCheck {

    /**
     * Fails unless the PNG exists, decodes, and contains a plausible rendered
     * UI: at least 0.5% sampled pixels brighter than the near-black background
     * (NdBlack sums to 24) and at least 12 distinct sampled colors.
     */
    fun assertNonBlankPng(relativePath: String) {
        val f = File(relativePath)
        assertTrue("PNG missing or tiny: $relativePath (${f.length()} bytes)", f.exists() && f.length() > 10_000)
        val bmp = BitmapFactory.decodeFile(f.absolutePath)
        assertTrue("PNG did not decode: $relativePath", bmp != null)
        bmp!!
        var lit = 0
        var total = 0
        val colors = HashSet<Int>()
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                colors.add(p)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r + g + b > 60) lit++
                total++
                x += 4
            }
            y += 4
        }
        assertTrue("blank render in $relativePath: $lit/$total lit pixels", total > 0 && lit * 1000 / total >= 5)
        assertTrue("flat render in $relativePath: ${colors.size} distinct colors", colors.size >= 12)
    }
}
