## ADDED Requirements

### Requirement: A feed list stays pinned to the top when posts are prepended

The shared timeline feed list (`PostFeedList`) SHALL keep a viewer who is **at the top** pinned to the top when a data change prepends posts, for example a pull-to-refresh or the feed reload after the viewer's own post (`mobile-post-creation` § "Successful post returns to Home and refreshes the Nearby and Global feeds"). "At the top" means first visible item index 0 with scroll offset 0. Without this, `LazyColumn` keeps the previous first item's key in view, and the newly prepended post sits just above the viewport with only its bottom edge visible.

The list SHALL do this with `LazyListState.requestScrollToItem(0)`, issued after the new posts are applied and before the remeasure. A viewer who has scrolled down SHALL keep their position, anchored on their first visible item, and SHALL NOT be jumped to the top.

#### Scenario: A viewer at the top sees a prepended post

- **GIVEN** `PostFeedList` showing posts with the viewer at the top of the list
- **WHEN** the posts change so that a new post is prepended
- **THEN** the new post is rendered fully inside the viewport at the top of the list (its unclipped top is at or below the list top)

#### Scenario: A scrolled-down viewer keeps their position

- **GIVEN** `PostFeedList` scrolled down so that a later post is the first visible item
- **WHEN** the posts change so that a new post is prepended
- **THEN** that later post is still displayed AND the prepended post is not displayed (no jump to the top)
