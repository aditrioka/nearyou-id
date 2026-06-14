package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * The polymorphic [SavedStateConfiguration] that makes the Nav3 back stack saveable on non-JVM
 * targets (iOS), where Nav3's reflection-based serialization is unavailable. Passed to
 * `rememberNavBackStack(navSavedStateConfiguration, RootRoute)` in `App()`
 * (`mobile-app-scaffold` § "Back stack is created with the polymorphic configuration").
 *
 * EVERY [NavKey] route MUST be registered with an explicit `subclass(...)` below — a missing
 * registration fails [id.nearyou.app.screens.routing] serialization round-trip
 * (`NavKeySerializationTest`, `mobile-app-scaffold` § "Every route key round-trips"). The explicit
 * (non-`subclassesOfSealed`) form is deliberate: it keeps that test load-bearing.
 */
val navSavedStateConfiguration: SavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(RootRoute::class, RootRoute.serializer())
                    subclass(SignInRoute::class, SignInRoute.serializer())
                    subclass(HomeRoute::class, HomeRoute.serializer())
                    subclass(AgeGateRoute::class, AgeGateRoute.serializer())
                    subclass(PostCreationRoute::class, PostCreationRoute.serializer())
                    subclass(ConsentRoute::class, ConsentRoute.serializer())
                    subclass(PostDetailRoute::class, PostDetailRoute.serializer())
                    subclass(SettingsRoute::class, SettingsRoute.serializer())
                    subclass(BlockedUsersRoute::class, BlockedUsersRoute.serializer())
                    subclass(ConsentSettingsRoute::class, ConsentSettingsRoute.serializer())
                    subclass(ProfileRoute::class, ProfileRoute.serializer())
                    subclass(FollowListRoute::class, FollowListRoute.serializer())
                    subclass(ConversationListRoute::class, ConversationListRoute.serializer())
                    subclass(ChatThreadRoute::class, ChatThreadRoute.serializer())
                    subclass(SearchRoute::class, SearchRoute.serializer())
                    subclass(PaywallRoute::class, PaywallRoute.serializer())
                }
            }
    }
