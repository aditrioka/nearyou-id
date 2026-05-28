package id.nearyou.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import id.nearyou.resources.theme.LocalNearYouColors
import id.nearyou.resources.theme.NearYouColorScheme
import id.nearyou.resources.theme.NearYouColors
import id.nearyou.resources.theme.nearYouTypography

@Composable
fun NearYouTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) NearYouColorScheme.dark else NearYouColorScheme.light
    val nearYouColors = if (darkTheme) NearYouColors.dark else NearYouColors.light
    CompositionLocalProvider(LocalNearYouColors provides nearYouColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = nearYouTypography(),
            content = content,
        )
    }
}
