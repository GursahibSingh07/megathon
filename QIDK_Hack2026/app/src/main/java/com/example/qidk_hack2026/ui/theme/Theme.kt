package com.example.qidk_hack2026.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Auto follows the system; the app's own toggle can pin light or dark. */
enum class ThemeMode { AUTO, LIGHT, DARK;
    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun load(context: Context): ThemeMode =
            runCatching { valueOf(context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("theme", "AUTO")!!) }
                .getOrDefault(AUTO)

        fun save(context: Context, mode: ThemeMode) {
            context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("theme", mode.name).apply()
        }
    }
}

/** Everything the glass look needs that Material's scheme has no slot for. */
@Immutable
data class GlassColors(
    val dark: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val blobA: Color,
    val blobB: Color,
    val blobC: Color,
    val glassFill: Color,
    val glassFillStrong: Color,
    val edgeLight: Color,
    val edgeDim: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val accentEnd: Color,
    val onAccent: Color,
    val good: Color,
    val busy: Color,
    val warn: Color,
    val shadow: Color,
)

private val LightGlass = GlassColors(
    dark = false,
    bgTop = Color(0xFFF1F4FB), bgBottom = Color(0xFFDDE4F4),
    blobA = Color(0xFF7FA8FF), blobB = Color(0xFFC49BFF), blobC = Color(0xFF86E3D2),
    glassFill = Color(0x80FFFFFF), glassFillStrong = Color(0xB3FFFFFF),
    edgeLight = Color(0xF2FFFFFF), edgeDim = Color(0x40FFFFFF),
    text = Color(0xFF0E1218), textMuted = Color(0xFF596070),
    accent = Color(0xFF3D7BFF), accentEnd = Color(0xFF7C5CFF), onAccent = Color.White,
    good = Color(0xFF22B455), busy = Color(0xFFF2453D), warn = Color(0xFFE98A00),
    shadow = Color(0x24203050),
)

private val DarkGlass = GlassColors(
    dark = true,
    bgTop = Color(0xFF06080F), bgBottom = Color(0xFF0D1426),
    blobA = Color(0xFF2F5BD6), blobB = Color(0xFF6A3FD0), blobC = Color(0xFF138C86),
    glassFill = Color(0x1AFFFFFF), glassFillStrong = Color(0x2EFFFFFF),
    edgeLight = Color(0x59FFFFFF), edgeDim = Color(0x0FFFFFFF),
    text = Color(0xFFF4F6FA), textMuted = Color(0xFF9AA4B6),
    accent = Color(0xFF6C97FF), accentEnd = Color(0xFFA582FF), onAccent = Color(0xFF060A1E),
    good = Color(0xFF3DDC77), busy = Color(0xFFFF5E55), warn = Color(0xFFFFB02E),
    shadow = Color(0x66000000),
)

val LocalGlass = staticCompositionLocalOf { LightGlass }

object Glass {
    val colors: GlassColors @Composable get() = LocalGlass.current
}

@Composable
fun QIDK_Hack2026Theme(mode: ThemeMode = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val g = if (dark) DarkGlass else LightGlass
    val scheme = if (dark) darkColorScheme(primary = g.accent, onPrimary = g.onAccent, background = g.bgTop, surface = g.bgTop, onSurface = g.text, onBackground = g.text)
    else lightColorScheme(primary = g.accent, onPrimary = g.onAccent, background = g.bgTop, surface = g.bgTop, onSurface = g.text, onBackground = g.text)
    CompositionLocalProvider(LocalGlass provides g) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
