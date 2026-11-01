package app.badger.kiosk

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Deliberately small palette: one ink/accent colour on neutral surfaces, plus one error red.
val Ink = Color(0xFF1A237E)
val Accent = Color(0xFF5C6BC0)
val IdleBg = Color(0xFFF7F8FA)
val ErrorRed = Color(0xFFB3261E)
val Muted = Color(0xFF6B7280)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink, onPrimary = Color.White,
            primaryContainer = Color(0xFFE8EAF6), onPrimaryContainer = Ink,
            secondaryContainer = Color(0xFFE8EAF6), onSecondaryContainer = Ink,
            background = IdleBg, surface = Color.White, surfaceVariant = Color(0xFFEEF0F4),
            error = ErrorRed
        ),
        content = content
    )
}
