## RENAMED Requirements

- FROM: ### Requirement: Mobile Settings entry is deferred (out of scope)
- TO: ### Requirement: The mobile Settings data-export entry is owned by mobile-settings

## MODIFIED Requirements

### Requirement: The mobile Settings data-export entry is owned by mobile-settings

The `account-data-export` capability is the user-facing export **producer** only — it SHALL ship the `POST` / `GET /api/v1/account/export` endpoints and SHALL add **no** Compose/mobile UI. The mobile "Unduh Data Saya" Settings row + confirm dialog + status banner SHALL be owned by the `mobile-settings` capability and ship in the `mobile-data-export-entry` change (no longer deferred); the mobile entry MUST drive the export entirely through these shipped endpoints with no further backend change for the happy path.

#### Scenario: account-data-export adds no mobile UI

- **WHEN** the modules touched by the `account-data-export` change are enumerated
- **THEN** `:mobile:app` is not modified (this capability is backend + `:infra:*` only); the mobile entry lives in `mobile-settings`

#### Scenario: The mobile entry drives the export through the shipped endpoints

- **WHEN** the mobile "Unduh Data Saya" Settings entry runs
- **THEN** it drives the export entirely through the shipped `POST` / `GET /api/v1/account/export` endpoints (no further backend change required for the happy path), and the previously-tracking `follow-up` issue is closed by the `mobile-data-export-entry` change
