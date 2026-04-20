# Word-pair dataset (dev seed)

`wordpairs.json` is a dev-scale seed (50 adjectives × 50 nouns × 10 modifiers
= 25 000 `adj_noun_modifier` combinations). Used by `UsernameGenerator` for
auto-generated signup handles per
`docs/05-Implementation.md § Username Generation & Customization`.

## Constraints

- All strings lowercase, matching regex `^[a-z0-9]+$` (loader fails fast otherwise).
- Dev-scale is enough to exercise the collision-retry paths in tests.

## Replacement plan

Per `docs/08-Roadmap-Risk.md` Pre-Phase 1 item 20, the full 600 × 600 × 100
dataset (36M combos) lands before Pre-Launch. Drop-in replacement — same file
name, same schema (`{"adjectives": [...], "nouns": [...], "modifiers": [...]}`);
no code change required.

Until then, review additions against:

- Indonesian-safe (no slurs, no ambiguous sexual connotation).
- Not tripping the (future) profanity / UU ITE wordlists.
- Unique within each array (no duplicates).
