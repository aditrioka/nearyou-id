## RENAMED Requirements

- FROM: `### Requirement: Wordlist array-content editing is out of scope and guarded read-only`
- TO: `### Requirement: Wordlist content editing is delegated to the dedicated wordlist editor`

## MODIFIED Requirements

### Requirement: Wordlist content editing is delegated to the dedicated wordlist editor

The Feature Flag Admin surface SHALL NOT mutate the array *content* of `moderation_profanity_list` or `moderation_uu_ite_list` through its single-flag write path. Those two lists SHALL be presented as read-only summaries (entry count + template version) **with an edit affordance** that links to the dedicated wordlist editor (`GET /admin/feature-flags/wordlists/{list}`, capability `admin-moderation-wordlist-editor`), which owns content editing. `moderation_match_threshold` remains inline-editable on the Feature Flag Admin surface. The Firebase Console path remains available for the Tier-3 / Tier-4 fallback wordlists, which the editor does not touch.

#### Scenario: The two wordlists render read-only with an edit affordance
- **WHEN** the Feature Flag Admin page renders the moderation parameters
- **THEN** `moderation_profanity_list` and `moderation_uu_ite_list` appear as read-only summaries (count + version) each with an edit affordance linking to `/admin/feature-flags/wordlists/{list}`, while `moderation_match_threshold` remains inline-editable

#### Scenario: The feature-flags single-flag write path does not mutate list content
- **WHEN** the Feature Flag Admin single-flag write path is exercised
- **THEN** there is no inline control on that path that adds, removes, or edits entries within `moderation_profanity_list` or `moderation_uu_ite_list` — content edits are performed only via the `admin-moderation-wordlist-editor` routes
