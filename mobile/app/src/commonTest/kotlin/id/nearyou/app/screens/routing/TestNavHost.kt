package id.nearyou.app.screens.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import id.nearyou.app.theme.NearYouTheme

/**
 * Test harness that hosts the REAL [appEntryProvider] in a [NavDisplay] over a back stack seeded with
 * [start] — the Nav3 analogue of the retired Voyager `Navigator(Screen())` harness. Drives actual
 * route transitions, so a test can assert the visible destination after a sign-in / FAB / pop /
 * process-death-guard transition. Shared by the Robolectric `*ScreenTest` (androidUnitTest) and the
 * iOS `*FlowIosTest` (iosTest) — both depend on this `commonTest` helper.
 *
 * [onBackStack] receives the live back stack (via `SideEffect`, after each successful composition) so
 * a test may assert its contents (e.g. `backStack.last() == PostCreationRoute`). [NearYouTheme] is
 * applied here; tests wrap this in `KoinContext` and provide the screens' Koin bindings.
 */
@Composable
fun TestNavHost(
    vararg start: NavKey,
    onBackStack: (NavBackStack<NavKey>) -> Unit = {},
) {
    NearYouTheme {
        val backStack = rememberNavBackStack(navSavedStateConfiguration, *start)
        SideEffect { onBackStack(backStack) }
        val entryProvider = remember(backStack) { appEntryProvider(backStack) }
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = entryProvider,
        )
    }
}
