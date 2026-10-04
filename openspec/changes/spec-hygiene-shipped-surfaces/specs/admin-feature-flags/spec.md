## MODIFIED Requirements

### Requirement: The panel renders the canonical flag catalog with typed controls and the active environment

The page SHALL render exactly the canonical flag catalog: `image_upload_enabled`, `search_enabled`, `perspective_api_enabled`, `premium_username_customization_enabled`, `premium_like_cap_override` as boolean toggles; `attestation_mode` as a three-valued enum control (`enforce` / `warn` / `off`); `moderation_match_threshold` as an editable integer; and `premium_image_upload_cap_override` as an editable integer (the Premium daily image-upload cap override consumed by `premium-image-upload`, default 50 when unset). It SHALL render `moderation_profanity_list` and `moderation_uu_ite_list` as read-only summaries (entry count + template version) with no content-editing control. It SHALL surface the active environment (e.g., `STAGING` / `PRODUCTION`) of the Remote Config project the running service is bound to. All rendered Remote Config values SHALL be HTML-escaped.

#### Scenario: Catalog renders with the correct control type per parameter
- **WHEN** the page renders
- **THEN** `attestation_mode` shows a three-valued enum control (not a boolean toggle) AND `moderation_match_threshold` and `premium_image_upload_cap_override` each show an integer input AND the five boolean flags show on/off toggles

#### Scenario: Active environment is shown
- **WHEN** the running admin service is bound to the staging Firebase project
- **THEN** the page surfaces `STAGING` as the active environment

#### Scenario: A Remote Config value containing markup is HTML-escaped
- **WHEN** a rendered parameter value contains HTML metacharacters
- **THEN** the rendered output is HTML-escaped and not interpreted as markup

### Requirement: A flag write validates the submitted value against the parameter type

A write SHALL validate the submitted value against the target parameter's type before any publish: `attestation_mode` accepts only `enforce` / `warn` / `off`; a boolean flag accepts only a boolean value; `premium_image_upload_cap_override` accepts only an integer within `[1, 10000]` (it is an integer parameter, not a boolean). An invalid value SHALL be rejected inline with no publish and no `feature_flag_toggled` audit row.

#### Scenario: Each attestation_mode value is accepted
- **WHEN** an authorized admin submits `attestation_mode` = `enforce`, `warn`, or `off` (each in turn) with a reason and the gates pass
- **THEN** the submitted value is published to the Server template AND audited once

#### Scenario: An unknown attestation_mode value is rejected
- **WHEN** an admin submits `attestation_mode` = a value outside {`enforce`, `warn`, `off`}
- **THEN** the write is rejected inline with no publish AND no `feature_flag_toggled` audit row

#### Scenario: A non-boolean value for a boolean flag is rejected
- **WHEN** an admin submits a non-boolean value for a boolean flag (e.g. `search_enabled = maybe`)
- **THEN** the write is rejected inline with no publish AND no `feature_flag_toggled` audit row

#### Scenario: premium_image_upload_cap_override validates as an in-range integer

- **WHEN** the catalog validates `premium_image_upload_cap_override` = `100`, `50`, `true`, and `0`
- **THEN** `100` and `50` are valid (normalized to `100` / `50`) while `true` (a boolean for an integer parameter) and `0` (below the minimum of 1) are invalid — an invalid value is rejected inline with no publish and no `feature_flag_toggled` audit row
