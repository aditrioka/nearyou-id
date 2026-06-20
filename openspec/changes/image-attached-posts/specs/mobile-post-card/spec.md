## MODIFIED Requirements

### Requirement: Card layout renders identity header, content, and location meta per mockup frames 1 and 19

The card SHALL render, per the canonical mockup (frames 1 + 19, `dev/mockups/nearyou-screens-mockup.html`, binding for look/layout per docs/11 § 2.8 — behavior governed by this spec):

- An **identity header row**: the letter avatar (per § "Letter avatar derivation is deterministic"), the author's **display name** (prominent), the **@username handle** (sourced via a `stringResource` format — the `@` prefix is not hardcoded in Kotlin), and the post **time label** (the existing date-label treatment; relative "5 mnt"-style formatting remains deferred to `mobile-timeline-relative-timestamp`). The display-name and handle texts render **single-line with ellipsis overflow**, so maximal-length identities (V2 maxima: 50-char display name, 60-char username) cannot wrap or push the time label out of the header.
- The post **content** text.
- An optional **attached image**: when the card model supplies a non-null `imageUrl`, the card SHALL render the image below the content via the async image loader (Coil 3), with an aspect-ratio placeholder and graceful failure (no error chrome) per the docs/02 § 6 delivery rules — no preload during scroll, on-screen render only. When `imageUrl` is null the card renders no image element and is visually identical to the pre-image baseline.
- A **location meta row**: the coral location pin (tint `locationPin`) + `city_name` (when non-empty) + the distance string via `DistanceRenderer.render(distanceM)` when a non-null `distanceM` is supplied (Nearby); Global supplies `null` and renders no distance. When `city_name` is empty AND `distanceM` is null, the location meta row (including the pin) SHALL be omitted entirely (no orphan pin icon).
- The **action row** per § "Action row renders interactive reply and like affordances per mockup frame 1".

The card model/API SHALL NOT accept the author UUID or raw `latitude`/`longitude` (the fields do not exist on the rendered model), so the card structurally cannot render them. The card model MAY accept a public `imageUrl: String?` (the coordinate-independent delivery URL) — this is not PII.

#### Scenario: Identity header renders display name, handle, and time

- **GIVEN** a post with `authorDisplayName = "Raka Pratama"`, `authorUsername = "raka.jkt"`
- **WHEN** the card is rendered
- **THEN** the tree contains a node with text "Raka Pratama" AND a node whose text is the handle format applied to "raka.jkt" (renders as "@raka.jkt") AND a node with the post's time label

#### Scenario: Nearby variant renders city and distance; Global variant renders city only

- **WHEN** the card is rendered with `cityName = "Jakarta Selatan"`, `distanceM = 5400.0` AND again with `cityName = "Jakarta Selatan"`, `distanceM = null`
- **THEN** the first render contains the pin + "Jakarta Selatan" + `DistanceRenderer.render(5400.0)` AND the second render contains the pin + "Jakarta Selatan" and NO distance string

#### Scenario: Empty city and null distance hide the location row

- **WHEN** the card is rendered with `cityName = ""` and `distanceM = null`
- **THEN** the tree contains no location-pin icon node and no empty-string city text (the meta row is absent, no crash)

#### Scenario: Maximal-length identity stays single-line and does not break the header

- **WHEN** the card is rendered with a 50-character `authorDisplayName` and a 60-character `authorUsername` (the V2 column maxima)
- **THEN** the display-name and handle nodes render single-line with ellipsis (no wrap) AND the time label remains visible in the header row

#### Scenario: Card renders the attached image when imageUrl is present, and nothing when absent

- **WHEN** the card is rendered once with a non-null `imageUrl` and once with `imageUrl = null`
- **THEN** the first render contains an async image node below the content AND the second render contains no image element (the no-image card is unchanged from the pre-image baseline)
