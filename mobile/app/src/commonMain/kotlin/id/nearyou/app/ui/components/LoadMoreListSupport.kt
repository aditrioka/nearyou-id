package id.nearyou.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.error_generic
import org.jetbrains.compose.resources.stringResource

/** Test tag on the load-more footer's in-flight spinner. */
const val LOAD_MORE_FOOTER_TAG: String = "loadMoreFooter"

/** Test tag on the load-more footer's retry control (error state). */
const val LOAD_MORE_RETRY_TAG: String = "loadMoreRetry"

/**
 * The canonical list load-more footer (`mobile-design-system` § "Canonical list load-more
 * (infinite-scroll) pattern"): an in-flight spinner while a page loads, a **non-destructive** retry row
 * on error (the loaded list above it is untouched), and nothing when idle / end-reached. Render this as
 * the LAST `item {}` of a paginated `LazyColumn` so it never co-occurs with the initial-load skeleton or
 * the pull-to-refresh indicator (those are mutually-exclusive list states). All copy via `stringResource`.
 */
@Composable
fun LoadMoreFooter(
    isLoadingMore: Boolean,
    loadMoreError: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoadingMore ->
            Box(
                modifier = modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.testTag(LOAD_MORE_FOOTER_TAG))
            }
        loadMoreError ->
            androidx.compose.foundation.layout.Row(
                modifier = modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.error_generic),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry, modifier = Modifier.testTag(LOAD_MORE_RETRY_TAG)) {
                    Text(text = stringResource(Res.string.cta_retry))
                }
            }
    }
}

/**
 * Fires [onLoadMore] when the [listState] is scrolled within [threshold] items of the end. The read is
 * rate-limited to composition via `derivedStateOf` (docs/11 §2.4) rather than computed every frame. The
 * in-flight, end-reached, and refresh-in-flight guards live in `LoadMoreController` + the host, so an
 * eager trigger on a short list is harmless (it no-ops there). Attach once where the paginated list is
 * rendered; for a list whose first items are non-paginated chrome (e.g. the post-detail header + like
 * row), the end-relative threshold still keys correctly off the list tail (the footer is the terminal
 * item).
 */
@Composable
fun LoadMoreOnScrollEnd(
    listState: LazyListState,
    threshold: Int = 3,
    onLoadMore: () -> Unit,
) {
    val shouldLoadMore by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val total = layout.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= total - 1 - threshold
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
}
