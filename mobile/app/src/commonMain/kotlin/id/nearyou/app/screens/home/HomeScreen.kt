package id.nearyou.app.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.app_name
import id.nearyou.resources.generated.resources.home_placeholder_title
import id.nearyou.resources.generated.resources.home_placeholder_version
import id.nearyou.resources.generated.resources.logo_brand_dark
import id.nearyou.resources.generated.resources.logo_brand_light
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

class HomeScreen : Screen {
    @Composable
    override fun Content() {
        Column(
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .safeContentPadding()
                    .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val logo =
                if (isSystemInDarkTheme()) {
                    Res.drawable.logo_brand_dark
                } else {
                    Res.drawable.logo_brand_light
                }
            Image(
                painter = painterResource(logo),
                contentDescription = stringResource(Res.string.app_name),
                modifier = Modifier.size(120.dp),
            )
            Text(
                text = stringResource(Res.string.home_placeholder_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(Res.string.home_placeholder_version, "1.0"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
