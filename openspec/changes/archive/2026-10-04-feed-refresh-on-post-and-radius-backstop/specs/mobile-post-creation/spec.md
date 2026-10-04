## RENAMED Requirements

- FROM: `### Requirement: Successful post returns to Home; Nearby auto-refresh on return is deferred`
- TO: `### Requirement: Successful post returns to Home and refreshes the Nearby and Global feeds`

## MODIFIED Requirements

### Requirement: Successful post returns to Home and refreshes the Nearby and Global feeds

On a `Success` outcome the composer SHALL invoke its hoisted `onPostCreated()` callback. `appEntryProvider` SHALL then (a) raise a one-shot post-created signal and (b) remove the composer entry from the back stack (`backStack.removeLastOrNull()`, the Nav3 equivalent of pop) to return to the home surface.

The `HomeRoute` entry SHALL consume that signal into a **feed reload key**. The key is an integer counter held with `rememberSaveable` inside the `HomeRoute` entry, so it is saved and restored together with the HomeRoute-scoped feed ViewModels across a configuration change. The entry SHALL pass the key down `AppShellScreen` → `HomeScreen` to the Nearby and Global feed screens.

`NearbyTimelineViewModel` and `GlobalTimelineViewModel` SHALL each record the first key they observe without fetching, because a freshly constructed ViewModel's own initial load is already current. Whenever the observed key changes, each SHALL re-fetch page 1 through its existing `reload()`: the prior list stays mounted and only `isRefreshing` toggles. A key change can arrive while a page-1 load issued under the previous key is still in flight (the viewer posted during a slow refresh). That load predates the post, so when it lands the ViewModel SHALL re-fetch page 1 once more. A load issued before any key was observed, which is the ViewModel's own first load, gets no follow-up. Both feeds carry the own-content self arm, so the viewer's new post appears in whichever of the two is on screen on return, and in the other the next time its pager page is shown.

The Following feed SHALL NOT be refreshed by a post. The viewer's own posts never appear in Following, because self-follow is impossible (`following-timeline` § "Following carries NO own-content self-arm").

The composer screen SHALL stay feed-agnostic: it references no timeline type and no reload trigger. The signal SHALL be state hoisted through the entry provider. It SHALL NOT be a `Channel`/`SharedFlow`/singleton event bus, a Nav3 result, or a `NavKey` parameter. Leaving the composer without a successful post (back / close) SHALL NOT change the key.

#### Scenario: A successful post re-fetches the Nearby feed on return

- **GIVEN** the real `appEntryProvider` hosting `HomeRoute` with the Nearby feed loaded once (fetch count 1)
- **WHEN** the composer is opened and a post is submitted with a `Success` outcome
- **THEN** the composer entry is removed from the back stack AND the Nearby feed is shown again AND the Nearby first-page fetch count becomes 2

#### Scenario: Leaving the composer without posting does not re-fetch

- **GIVEN** the real `appEntryProvider` hosting `HomeRoute` with the Nearby feed loaded once (fetch count 1)
- **WHEN** the composer is opened and then popped without a successful post
- **THEN** the Nearby first-page fetch count stays 1

#### Scenario: A feed ViewModel records its first key and reloads only on a change

- **GIVEN** a `NearbyTimelineViewModel` (and, separately, a `GlobalTimelineViewModel`) whose initial load has completed (fetch count 1)
- **WHEN** it observes a feed reload key for the first time, then the same key again, then a different key
- **THEN** the first observation and the repeat do not fetch (count stays 1) AND the changed key re-fetches page 1 once (count 2) while the prior outcome is retained during the refresh

#### Scenario: A post made during an in-flight refresh still refreshes the feed

- **GIVEN** a feed ViewModel that has observed key `k` and has a pull-to-refresh in flight
- **WHEN** it observes key `k + 1` before that refresh lands, and the refresh then lands
- **THEN** exactly one more page-1 fetch is issued after the refresh lands (no concurrent fetch while it is in flight)

#### Scenario: An off-screen Global feed refreshes when next shown, Following never does

- **GIVEN** `HomeScreen` with the Nearby, Following and Global feeds each loaded once
- **WHEN** the feed reload key changes while the Nearby page is on screen, and the viewer then swipes to Following and then to Global
- **THEN** the Nearby fetch count becomes 2 AND the Following fetch count stays 1 AND the Global fetch count becomes 2 once its page is shown

#### Scenario: The reload key is hoisted saveable state, not an event bus

- **WHEN** inspecting `AppEntryProvider.kt`, `PostCreationScreen.kt` and the feed ViewModels
- **THEN** the `HomeRoute` entry holds the key with `rememberSaveable` AND `onPostCreated` raises the provider's one-shot signal before popping AND `PostCreationScreen.kt` references no `NearbyTimeline` / `GlobalTimeline` type, no `reload`, and no `ResultEventBus` AND no `Channel` / `SharedFlow` is introduced for the signal
