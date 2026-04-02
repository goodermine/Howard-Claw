package au.howardagent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF86EFAC),
    secondary = androidx.compose.ui.graphics.Color(0xFF38BDF8),
    tertiary = androidx.compose.ui.graphics.Color(0xFFA78BFA),
    background = androidx.compose.ui.graphics.Color(0xFF0A0A14),
    surface = androidx.compose.ui.graphics.Color(0xFF0F0F1A),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF003300),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF003355),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE2E8F0),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE2E8F0),
)

private val LightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF2E7D32),
    secondary = androidx.compose.ui.graphics.Color(0xFF1976D2),
    tertiary = androidx.compose.ui.graphics.Color(0xFF7B1FA2),
)

@Composable
fun HowardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HowardTypography,
        content = content
    )
}
