package id.nearyou.app.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_back
import id.nearyou.resources.generated.resources.ic_arrow_back
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The back affordance of a root-stack overlay's M3 `TopAppBar` (`navigationIcon`): the `ic_arrow_back` glyph,
 * described "Kembali" (`cta_back`), in a 48dp [IconButton]. One shared composable for post detail, the
 * conversation list, the chat thread, and the share-to-chat picker (docs/11 § 4 rule of three). The host passes
 * its own test tag through [modifier].
 */
@Composable
fun BackNavigationIcon(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(
            painter = painterResource(Res.drawable.ic_arrow_back),
            contentDescription = stringResource(Res.string.cta_back),
        )
    }
}
