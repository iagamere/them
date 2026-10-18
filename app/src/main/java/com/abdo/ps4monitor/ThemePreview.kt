package com.abdo.ps4monitor

import kotlin.math.abs

/**
 * Theme packs (the .rar / .zip people share) contain, next to the encrypted .pkg, a PREVIEW PICTURE: a capture of the PS4's own "Settings > Themes"
 * screen, which shows the theme twice, as two 16:9 panels: the HOME screen (wallpaper, icon row) on the left and the SETTINGS / function screen on the right.
 * That screen is Sony's own layout, identical for every theme, so the panels sit at the same normalised place whatever the picture size (verified on the
 * 1920x1080 preview of "God of War - 10th Anniversary": left panel x 190..930, y 257..673, right panel x 990..1730).
 * This is the only readable picture of the real wallpaper (the theme's own images are inside the encrypted part of the .pkg).
 *
 * Pure Kotlin (pixels in, rectangles out) so it is tested on a plain JVM.
 */
class PRect(val x: Int, val y: Int, val w: Int, val h: Int)
class PanelPair(val home: PRect, val func: PRect, val score: Double)

object PreviewPanels {
    // normalised: x, y, w, h
    private val HOME = doubleArrayOf(190 / 1920.0, 257 / 1080.0, 740 / 1920.0, 416 / 1080.0)
    private val FUNC = doubleArrayOf(990 / 1920.0, 257 / 1080.0, 740 / 1920.0, 416 / 1080.0)
    private fun rect(n: DoubleArray, w: Int, h: Int, dx: Int, dy: Int) = PRect((n[0] * w).toInt() + dx, (n[1] * h).toInt() + dy, (n[2] * w).toInt(), (n[3] * h).toInt())

    private fun luma(p: Int) = 0.299 * ColorMath.red(p) + 0.587 * ColorMath.green(p) + 0.114 * ColorMath.blue(p)

    /**
     * Edge strength of [r]: the mean |brightness jump| across its four edges (3 px inside vs 3 px outside), divided by the mean jump between two rings INSIDE
     * (3 px vs 9 px from the edge). A picture on a different background jumps at its edge but not just inside it; noise or texture jumps everywhere (ratio ~1).
     * Returns (jump, ratio).
     */
    private fun edgeScore(px: IntArray, w: Int, h: Int, r: PRect): Pair<Double, Double> {
        if (r.x < 10 || r.y < 10 || r.x + r.w + 10 > w || r.y + r.h + 10 > h) return 0.0 to 0.0
        var edge = 0.0; var inner = 0.0; var n = 0
        fun at(x: Int, y: Int) = luma(px[y * w + x])
        for (x in r.x + 10 until r.x + r.w - 10 step 3) {
            edge += abs(at(x, r.y + 2) - at(x, r.y - 3)) + abs(at(x, r.y + r.h - 3) - at(x, r.y + r.h + 2))
            inner += abs(at(x, r.y + 2) - at(x, r.y + 8)) + abs(at(x, r.y + r.h - 3) - at(x, r.y + r.h - 9)); n += 2
        }
        for (y in r.y + 10 until r.y + r.h - 10 step 3) {
            edge += abs(at(r.x + 2, y) - at(r.x - 3, y)) + abs(at(r.x + r.w - 3, y) - at(r.x + r.w + 2, y))
            inner += abs(at(r.x + 2, y) - at(r.x + 8, y)) + abs(at(r.x + r.w - 3, y) - at(r.x + r.w - 9, y)); n += 2
        }
        val e = edge / n; val i = inner / n
        return e to e / (i + 1.0)
    }

    /**
     * The two panels, or null when the picture does not look like that screen (not 16:9, or no panel edges where Sony puts them). A shift of up to ~1.2 %
     * of the width is searched so a slightly different capture crop still locks on. [minScore] is the mean edge jump required (0..255 scale).
     */
    fun find(px: IntArray, w: Int, h: Int, minScore: Double = 9.0, minRatio: Double = 1.8): PanelPair? {
        if (w < 640 || h < 360 || px.size < w * h) return null
        val ar = w.toDouble() / h
        if (abs(ar - 16.0 / 9.0) > 0.06) return null
        val rng = maxOf(1, w / 80)
        var best: Triple<Int, Int, Double>? = null; var bestRatio = 0.0
        for (dy in -rng..rng step 2) for (dx in -rng..rng step 2) {
            val a = edgeScore(px, w, h, rect(HOME, w, h, dx, dy)); val c = edgeScore(px, w, h, rect(FUNC, w, h, dx, dy))
            val s = minOf(a.first, c.first)
            if (best == null || s > best.third) { best = Triple(dx, dy, s); bestRatio = minOf(a.second, c.second) }
        }
        val b = best ?: return null
        if (b.third < minScore || bestRatio < minRatio) return null
        return PanelPair(rect(HOME, w, h, b.first, b.second), rect(FUNC, w, h, b.first, b.second), b.third)
    }
}
