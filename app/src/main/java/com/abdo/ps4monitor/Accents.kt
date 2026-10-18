package com.abdo.ps4monitor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** The app colours the person can choose when "Use phone colours" is off (Settings > Appearance). The first one is the original purple. */
class Accent(val en: String, val ar: String, val dark: Triple<Long, Long, Long>, val light: Triple<Long, Long, Long>)   // primary, secondary, tertiary

object Accents {
    val all = listOf(
        Accent("Purple", "بنفسجي", Triple(0xFFCFBCFF, 0xFFCCC2DC, 0xFFEFB8C8), Triple(0xFF6A3DE8, 0xFF625B71, 0xFF7D5260)),
        Accent("Blue", "أزرق", Triple(0xFFA8C8FF, 0xFFBBC7DB, 0xFFD7BDE4), Triple(0xFF1A5FB4, 0xFF565F71, 0xFF715573)),
        Accent("Teal", "تركوازي", Triple(0xFF4FD8C4, 0xFFB1CCC7, 0xFFADCBE0), Triple(0xFF006B60, 0xFF4A635F, 0xFF456179)),
        Accent("Green", "أخضر", Triple(0xFF8FD98A, 0xFFB9CCB4, 0xFFA0CFD3), Triple(0xFF2B6B2E, 0xFF52634F, 0xFF39656B)),
        Accent("Orange", "برتقالي", Triple(0xFFFFB86B, 0xFFDEC1A5, 0xFFC8CA8E), Triple(0xFF9A5100, 0xFF745A42, 0xFF5E6135)),
        Accent("Red", "أحمر", Triple(0xFFFFB3AB, 0xFFE7BDB8, 0xFFDEC48C), Triple(0xFFB3261E, 0xFF775652, 0xFF6F5C2E)),
        Accent("Pink", "وردي", Triple(0xFFFFB0CB, 0xFFE3BDC8, 0xFFEFB8C8), Triple(0xFFB02E66, 0xFF74565F, 0xFF7D5260)))

    /** Index 0 is exactly the app's original scheme; the others also colour the containers (selected chips, cards) so nothing stays purple. */
    fun scheme(i: Int, dark: Boolean): ColorScheme {
        val a = all[i.coerceIn(0, all.size - 1)]
        val (p, s, t) = (if (dark) a.dark else a.light).let { Triple(Color(it.first), Color(it.second), Color(it.third)) }
        if (i <= 0) return if (dark) darkColorScheme(primary = p, secondary = s, tertiary = t) else lightColorScheme(primary = p, secondary = s, tertiary = t)
        return if (dark) darkColorScheme(
            primary = p, onPrimary = Color(0xFF1E1E1E), primaryContainer = lerp(p, Color.Black, 0.62f), onPrimaryContainer = lerp(p, Color.White, 0.5f),
            secondary = s, onSecondary = Color(0xFF1E1E1E), secondaryContainer = lerp(s, Color.Black, 0.62f), onSecondaryContainer = lerp(s, Color.White, 0.5f),
            tertiary = t, onTertiary = Color(0xFF1E1E1E), tertiaryContainer = lerp(t, Color.Black, 0.62f), onTertiaryContainer = lerp(t, Color.White, 0.5f))
        else lightColorScheme(
            primary = p, onPrimary = Color.White, primaryContainer = lerp(p, Color.White, 0.82f), onPrimaryContainer = lerp(p, Color.Black, 0.6f),
            secondary = s, onSecondary = Color.White, secondaryContainer = lerp(s, Color.White, 0.82f), onSecondaryContainer = lerp(s, Color.Black, 0.6f),
            tertiary = t, onTertiary = Color.White, tertiaryContainer = lerp(t, Color.White, 0.82f), onTertiaryContainer = lerp(t, Color.Black, 0.6f))
    }
}
