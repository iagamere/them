package com.abdo.ps4monitor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Smart PS4 theme": from the pictures of a theme (its icon0.png, a preview, a screenshot ...) to a complete Material 3 colour scheme.
 * Pure Kotlin on purpose (no android.* classes), so it is unit-tested on a plain JVM against real theme files. Colours are ARGB ints.
 *
 * Pipeline:  pixels --[ThemeExtract.swatches]--> weighted colour clusters (k-means in Oklab)
 *                   --[ThemeExtract.seed]------> primary / secondary / tertiary hues + "is the picture dark"
 *                   --[ThemeScheme.build]------> every Material 3 role, light or dark, contrast-checked.
 */

/** Oklab / Oklch (Bjorn Ottosson). Perceptually uniform: equal steps in L, C, h look equal, which RGB / HSL do not. */
object Ok {
    class Lch(val l: Double, val c: Double, val h: Double)

    private fun lin(v: Int): Double { val x = v / 255.0; return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
    private fun enc(x: Double): Int {
        val c = x.coerceIn(0.0, 1.0)
        val y = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
        return (y * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    /** [L, a, b] of an ARGB colour (alpha ignored). */
    fun lab(argb: Int): DoubleArray {
        val r = lin(ColorMath.red(argb)); val g = lin(ColorMath.green(argb)); val b = lin(ColorMath.blue(argb))
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }

    private fun linearRgb(L: Double, a: Double, b: Double): DoubleArray {
        val l = (L + 0.3963377774 * a + 0.2158037573 * b).let { it * it * it }
        val m = (L - 0.1055613458 * a - 0.0638541728 * b).let { it * it * it }
        val s = (L - 0.0894841775 * a - 1.2914855480 * b).let { it * it * it }
        return doubleArrayOf(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
    }
    private fun inGamut(c: DoubleArray) = c.all { it >= -0.0005 && it <= 1.0005 }

    fun fromLab(L: Double, a: Double, b: Double): Int {
        val c = linearRgb(L, a, b)
        return ColorMath.rgb(enc(c[0]), enc(c[1]), enc(c[2]))
    }

    fun lch(argb: Int): Lch {
        val v = lab(argb)
        val c = sqrt(v[1] * v[1] + v[2] * v[2])
        var h = Math.toDegrees(atan2(v[2], v[1])); if (h < 0) h += 360.0
        return Lch(v[0], c, if (c < 1e-4) 0.0 else h)
    }

    /** Oklch -> ARGB. A chroma the screen cannot show at this lightness is reduced (hue and lightness are kept), never clipped to a different colour. */
    fun toArgb(l: Double, c: Double, h: Double): Int {
        val L = l.coerceIn(0.0, 1.0); val hr = Math.toRadians(h)
        fun at(cc: Double) = linearRgb(L, cc * cos(hr), cc * sin(hr))
        if (inGamut(at(c))) return fromLab(L, c * cos(hr), c * sin(hr))
        var lo = 0.0; var hi = c
        repeat(22) { val mid = (lo + hi) / 2; if (inGamut(at(mid))) lo = mid else hi = mid }
        return fromLab(L, lo * cos(hr), lo * sin(hr))
    }

    /** Material-style "tone" (CIE L*, 0 = black, 100 = white) -> Oklab lightness. For greys Oklab L is the cube root of luminance, so tone differences keep their contrast meaning. */
    fun toneL(tone: Double): Double {
        val fy = (tone.coerceIn(0.0, 100.0) + 16.0) / 116.0
        val y = if (fy * fy * fy > 0.008856) fy * fy * fy else tone / 903.3
        return cbrt(y)
    }
    fun hueDist(a: Double, b: Double): Double { val d = abs(a - b) % 360.0; return min(d, 360.0 - d) }
}

/** One colour cluster of a picture: how much of the (visible) picture it covers and where it sits in Oklch. */
class Swatch(val argb: Int, val share: Float, val l: Double, val c: Double, val h: Double)

/**
 * What a theme "feels like": up to three accent colours (primary first), whether the picture is dark, and the swatches that were found
 * (the screen shows them so the person can pick another accent).
 */
class ThemeSeed(val primary: Int, val secondary: Int?, val tertiary: Int?, val monochrome: Boolean, val darkPicture: Boolean, val swatches: List<Swatch>) {
    /** The same picture with another accent colour as the primary (the person tapped a swatch). */
    fun withPrimary(argb: Int): ThemeSeed {
        val others = swatches.filter { it.argb != argb }
        val ph = Ok.lch(argb).h
        val sec = others.firstOrNull { it.c >= 0.05 && Ok.hueDist(it.h, ph) >= 35 }?.argb
        val ter = others.firstOrNull { it.c >= 0.05 && Ok.hueDist(it.h, ph) >= 35 && it.argb != sec && (sec == null || Ok.hueDist(it.h, Ok.lch(sec).h) >= 35) }?.argb
        return ThemeSeed(argb, sec, ter, false, darkPicture, swatches)
    }
}

object ThemeExtract {
    private class Bin(val lab: DoubleArray, var w: Double)

    /**
     * Weighted k-means in Oklab over a 5-bit-per-channel histogram (so a 1920x1080 picture costs the same as a thumbnail).
     * Deterministic: the first centre is the heaviest colour, each next one the colour that is heavy AND far from the chosen ones.
     */
    fun swatches(px: IntArray, k: Int = 10): List<Swatch> {
        val hist = HashMap<Int, Int>()
        var total = 0
        for (p in px) {
            if (ColorMath.alpha(p) < 128) continue
            total++
            val key = ((ColorMath.red(p) shr 3) shl 10) or ((ColorMath.green(p) shr 3) shl 5) or (ColorMath.blue(p) shr 3)
            hist[key] = (hist[key] ?: 0) + 1
        }
        if (total == 0) return emptyList()
        val bins = hist.map { (key, n) ->
            val r = ((key shr 10) and 31) * 8 + 4; val g = ((key shr 5) and 31) * 8 + 4; val b = (key and 31) * 8 + 4
            Bin(Ok.lab(ColorMath.rgb(r, g, b)), n.toDouble())
        }.sortedByDescending { it.w }
        val kk = min(k, bins.size)
        val centres = ArrayList<DoubleArray>(); centres.add(bins[0].lab.copyOf())
        while (centres.size < kk) {
            var best = -1.0; var pick: Bin? = null
            for (b in bins) {
                var d = Double.MAX_VALUE
                for (c in centres) d = min(d, dist2(b.lab, c))
                val s = d * sqrt(b.w)
                if (s > best) { best = s; pick = b }
            }
            if (pick == null || best <= 0.0) break
            centres.add(pick.lab.copyOf())
        }
        val assign = IntArray(bins.size)
        repeat(12) {
            for ((i, b) in bins.withIndex()) {
                var bi = 0; var bd = Double.MAX_VALUE
                for ((ci, c) in centres.withIndex()) { val d = dist2(b.lab, c); if (d < bd) { bd = d; bi = ci } }
                assign[i] = bi
            }
            val sum = Array(centres.size) { DoubleArray(4) }
            for ((i, b) in bins.withIndex()) { val s = sum[assign[i]]; s[0] += b.lab[0] * b.w; s[1] += b.lab[1] * b.w; s[2] += b.lab[2] * b.w; s[3] += b.w }
            for (ci in centres.indices) if (sum[ci][3] > 0) for (j in 0..2) centres[ci][j] = sum[ci][j] / sum[ci][3]
        }
        val weight = DoubleArray(centres.size)
        for ((i, b) in bins.withIndex()) weight[assign[i]] += b.w
        return centres.indices.filter { weight[it] > 0 }.map { ci ->
            val c = centres[ci]; val argb = Ok.fromLab(c[0], c[1], c[2]); val lc = Ok.lch(argb)
            Swatch(argb, (weight[ci] / total).toFloat(), lc.l, lc.c, lc.h)
        }.sortedByDescending { it.share }
    }

    private fun dist2(a: DoubleArray, b: DoubleArray): Double { val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return x * x + y * y + z * z }

    /** How well a swatch would serve as an accent: it must be colourful and neither near-black nor near-white; area counts (square root, so a small vivid detail still competes). */
    private fun score(s: Swatch): Double {
        if (s.c < 0.05 || s.l < 0.20 || s.l > 0.93 || s.share < 0.008f) return 0.0
        return sqrt(s.share.toDouble()) * (0.40 + 0.60 * min(s.c, 0.22) / 0.22)
    }

    fun seed(px: IntArray): ThemeSeed? {
        val sw = swatches(px)
        if (sw.isEmpty()) return null
        val meanL = sw.sumOf { it.l * it.share } / sw.sumOf { it.share.toDouble() }
        val ranked = sw.filter { score(it) > 0 }.sortedByDescending { score(it) }
        if (ranked.isEmpty()) {
            // grey / black / white picture: a quiet, almost neutral scheme (the faint hue of the most colourful cluster, if any)
            val tint = sw.maxByOrNull { it.c }?.takeIf { it.c >= 0.012 }
            return ThemeSeed(Ok.toArgb(0.68, if (tint != null) 0.035 else 0.012, tint?.h ?: 260.0), null, null, true, meanL < 0.5, sw)
        }
        val top = ranked[0]
        val sec = ranked.drop(1).firstOrNull { Ok.hueDist(it.h, top.h) >= 35 && score(it) >= 0.30 * score(top) }
        val ter = ranked.drop(1).firstOrNull { it !== sec && Ok.hueDist(it.h, top.h) >= 35 && (sec == null || Ok.hueDist(it.h, sec.h) >= 35) && score(it) >= 0.30 * score(top) }
        return ThemeSeed(top.argb, sec?.argb, ter?.argb, false, meanL < 0.5, sw)
    }

    /** Several pictures of one theme count together; a picture with fewer pixels counts less (so a tiny icon does not swamp nothing and a preview does not drown the icon). */
    fun seedOf(vararg pictures: IntArray): ThemeSeed? = seed(pictures.fold(IntArray(0)) { a, p -> a + p })
}

/** Every colour role the app's Material 3 scheme needs (ARGB). */
class SchemeColors(
    val dark: Boolean,
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int,
    val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
    val error: Int, val onError: Int, val errorContainer: Int, val onErrorContainer: Int,
    val background: Int, val onBackground: Int, val surface: Int, val onSurface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
    val surfaceTint: Int, val inverseSurface: Int, val inverseOnSurface: Int, val inversePrimary: Int,
    val outline: Int, val outlineVariant: Int, val scrim: Int,
    val surfaceBright: Int, val surfaceDim: Int,
    val surfaceContainerLowest: Int, val surfaceContainerLow: Int, val surfaceContainer: Int, val surfaceContainerHigh: Int, val surfaceContainerHighest: Int) {

    /** (label, text colour, background colour, minimum WCAG contrast) of every pair that must stay readable. Used by the tests and by [worstContrast]. */
    fun pairs(): List<Triple<String, Pair<Int, Int>, Double>> = listOf(
        Triple("onSurface/surface", onSurface to surface, 7.0),
        Triple("onSurface/surfaceContainerHighest", onSurface to surfaceContainerHighest, 7.0),
        Triple("onSurfaceVariant/surface", onSurfaceVariant to surface, 4.5),
        Triple("onSurfaceVariant/surfaceContainerHighest", onSurfaceVariant to surfaceContainerHighest, 4.5),
        Triple("primary/surface", primary to surface, 4.5),
        Triple("primary/surfaceContainerHighest", primary to surfaceContainerHighest, 4.5),
        Triple("onPrimary/primary", onPrimary to primary, 4.5),
        Triple("onPrimaryContainer/primaryContainer", onPrimaryContainer to primaryContainer, 4.5),
        Triple("onSecondary/secondary", onSecondary to secondary, 4.5),
        Triple("onSecondaryContainer/secondaryContainer", onSecondaryContainer to secondaryContainer, 4.5),
        Triple("secondary/surface", secondary to surface, 3.0),
        Triple("onTertiary/tertiary", onTertiary to tertiary, 4.5),
        Triple("onTertiaryContainer/tertiaryContainer", onTertiaryContainer to tertiaryContainer, 4.5),
        Triple("tertiary/surface", tertiary to surface, 3.0),
        Triple("onError/error", onError to error, 4.5),
        Triple("onErrorContainer/errorContainer", onErrorContainer to errorContainer, 4.5),
        Triple("inverseOnSurface/inverseSurface", inverseOnSurface to inverseSurface, 4.5),
        Triple("outline/surface", outline to surface, 3.0))
    fun worstContrast(): Pair<String, Double> = pairs().map { it.first to ColorMath.contrast(it.second.first, it.second.second) }.minByOrNull { it.second }!!
}

object ThemeScheme {
    private const val SCRIM = 0xFF000000.toInt()
    private fun tone(t: Double, c: Double, h: Double) = Ok.toArgb(Ok.toneL(t), c, h)

    /** Moves the lightness of (c, h) away from [bg] in steps until the pair reaches [minContrast]; the hue and chroma are kept. */
    private fun fit(h: Double, c: Double, startL: Double, bg: Int, minContrast: Double, lighter: Boolean): Int {
        var l = startL
        repeat(120) {
            val col = Ok.toArgb(l, c, h)
            if (ColorMath.contrast(col, bg) >= minContrast) return col
            l = (l + if (lighter) 0.008 else -0.008).coerceIn(0.0, 1.0)
        }
        return if (lighter) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }
    /** Text / icon colour on a filled background: the near-black or near-white tone of the accent's hue that reads best. */
    private fun on(bg: Int, h: Double, c: Double): Int {
        val dark = tone(10.0, c * 0.25, h); val light = tone(99.0, c * 0.08, h)
        return if (ColorMath.contrast(dark, bg) >= ColorMath.contrast(light, bg)) dark else light
    }

    private class Accent(val h: Double, val c: Double, val l: Double)
    private fun accent(argb: Int?, fallbackH: Double, fallbackC: Double, scaleC: Double): Accent {
        if (argb == null) return Accent(fallbackH, fallbackC, 0.7)
        val x = Ok.lch(argb); return Accent(x.h, (x.c * scaleC).coerceIn(0.04, 0.20), x.l)
    }

    /**
     * The scheme of a theme. Surfaces carry a faint tint of the primary hue (as PS4 themes tint the whole home screen); the primary keeps the theme's
     * own hue and chroma and is only lightened (dark) or darkened (light) as much as needed to reach 4.5:1 against the surfaces (so text and icons stay
     * readable); the theme's deep, saturated colour itself is used for the containers (cards, chips, buttons' backgrounds).
     */
    fun build(seed: ThemeSeed, dark: Boolean): SchemeColors {
        val p = accent(seed.primary, 260.0, 0.10, 1.0).let { if (seed.monochrome) Accent(it.h, min(it.c, 0.035), it.l) else Accent(it.h, it.c.coerceIn(0.07, 0.19), it.l) }
        val s = if (seed.monochrome) Accent(p.h, 0.02, 0.7) else accent(seed.secondary, p.h, p.c * 0.45, 0.85).let { if (seed.secondary == null) it else Accent(it.h, it.c.coerceIn(0.05, 0.16), it.l) }
        val t = if (seed.monochrome) Accent(p.h, 0.02, 0.7) else accent(seed.tertiary, (p.h + 60.0) % 360.0, p.c * 0.75, 0.85).let { if (seed.tertiary == null) Accent(it.h, it.c.coerceAtLeast(0.06), it.l) else Accent(it.h, it.c.coerceIn(0.05, 0.16), it.l) }
        val nh = p.h
        val nc = if (seed.monochrome) 0.004 else 0.020          // surfaces
        val nv = if (seed.monochrome) 0.008 else 0.030          // surface variant / outline

        if (dark) {
            val surface = tone(6.0, nc, nh); val hi = tone(22.0, nc, nh)
            val pr = fit(p.h, p.c, p.l.coerceIn(Ok.toneL(45.0), Ok.toneL(80.0)), hi, 4.5, true)
            val prC = tone(30.0, p.c * 0.9, p.h)
            val se = fit(s.h, s.c, s.l.coerceIn(Ok.toneL(50.0), Ok.toneL(80.0)), hi, 4.5, true)
            val seC = tone(30.0, s.c * 0.8, s.h)
            val te = fit(t.h, t.c, t.l.coerceIn(Ok.toneL(50.0), Ok.toneL(80.0)), hi, 4.5, true)
            val teC = tone(30.0, t.c * 0.8, t.h)
            val inv = tone(90.0, nc, nh)
            return SchemeColors(true,
                pr, on(pr, p.h, p.c), prC, fit(p.h, p.c * 0.25, Ok.toneL(90.0), prC, 7.0, true),
                se, on(se, s.h, s.c), seC, fit(s.h, s.c * 0.25, Ok.toneL(90.0), seC, 7.0, true),
                te, on(te, t.h, t.c), teC, fit(t.h, t.c * 0.25, Ok.toneL(90.0), teC, 7.0, true),
                0xFFF2B8B5.toInt(), 0xFF601410.toInt(), 0xFF8C1D18.toInt(), 0xFFF9DEDC.toInt(),
                surface, tone(90.0, nc, nh), surface, tone(90.0, nc, nh), tone(30.0, nv, nh), tone(80.0, nv, nh),
                pr, inv, tone(20.0, nc, nh), fit(p.h, p.c, Ok.toneL(40.0), inv, 4.5, false),
                tone(62.0, nv, nh), tone(30.0, nv, nh), SCRIM,
                tone(24.0, nc, nh), surface, tone(4.0, nc, nh), tone(10.0, nc, nh), tone(12.0, nc, nh), tone(17.0, nc, nh), hi)
        }
        val surface = tone(98.0, nc, nh); val hi = tone(90.0, nc, nh)
        val pr = fit(p.h, p.c, p.l.coerceIn(Ok.toneL(35.0), Ok.toneL(55.0)), hi, 4.5, false)
        val prC = tone(90.0, p.c * 0.7, p.h)
        val se = fit(s.h, s.c, s.l.coerceIn(Ok.toneL(35.0), Ok.toneL(55.0)), hi, 4.5, false)
        val seC = tone(90.0, s.c * 0.6, s.h)
        val te = fit(t.h, t.c, t.l.coerceIn(Ok.toneL(35.0), Ok.toneL(55.0)), hi, 4.5, false)
        val teC = tone(90.0, t.c * 0.6, t.h)
        val inv = tone(20.0, nc, nh)
        return SchemeColors(false,
            pr, on(pr, p.h, p.c), prC, fit(p.h, p.c * 0.4, Ok.toneL(10.0), prC, 7.0, false),
            se, on(se, s.h, s.c), seC, fit(s.h, s.c * 0.4, Ok.toneL(10.0), seC, 7.0, false),
            te, on(te, t.h, t.c), teC, fit(t.h, t.c * 0.4, Ok.toneL(10.0), teC, 7.0, false),
            0xFFB3261E.toInt(), 0xFFFFFFFF.toInt(), 0xFFF9DEDC.toInt(), 0xFF410E0B.toInt(),
            surface, tone(10.0, nc, nh), surface, tone(10.0, nc, nh), tone(90.0, nv, nh), tone(30.0, nv, nh),
            pr, inv, tone(95.0, nc, nh), fit(p.h, p.c, Ok.toneL(80.0), inv, 4.5, true),
            tone(50.0, nv, nh), tone(80.0, nv, nh), SCRIM,
            surface, tone(87.0, nc, nh), tone(100.0, nc, nh), tone(96.0, nc, nh), tone(94.0, nc, nh), tone(92.0, nc, nh), hi)
    }
}
