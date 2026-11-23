# Changelog

## [Unreleased]

- Live updates: the SDK's account journal is followed through `bus.subscribe`, so conversations and account state update without a manual refresh.
- The outbox is sealed at rest and resumes after a lock or a relaunch; a local attachment send is shown as waiting or not sent, never as a failed download.
- Onboarding fixes: one back control per step, the new account opens after the permission step, the account-created step follows `account.status` until the account is live, and the whole account row opens or unlocks the account.
- After an unlock the screens that were open when the account was locked are reopened.
- Backup shows the progress of a running job while the screen is open.
- Secret entry fields are presented to accessibility services as edit fields.
- A new group's conversation opens by the id its row names.
- Opt-in real-run suite and its manual (`docs/realrun.md`): every machine, device and path is a setting; there are no personal or host defaults. The `realrun` build type is absent from release builds.
- Repository clean-up: the README describes the app and its modules, the manuals no longer refer to a private workspace, and design-token metadata is neutral.

## [0.2.6] - 2026-11-23

- Align release metadata with product version 0.2.6.

## [0.2.5] - 2026-11-22

- Version line aligned with product 0.2.5.

## [0.2.4] - 2026-11-21

- Version line aligned with product 0.2.4.

## [0.2.3] - 2026-11-21

- Version line aligned with product 0.2.3.

## [0.2.2] - 2026-11-21

- Version line aligned with product 0.2.2.

## [0.2.1] - 2026-11-17

- Version line aligned with product 0.2.1.

## 0.2.0 - 2026-11-16

First release under the shared Ramflux product version (all repos move together).
Changes since 0.1.0, at a high level:

- Android custody: account keys now stay in the device's secure custody, and the app waits for the secure store before showing private data.
- Generation-1 messaging is supported: the app speaks the new message format and delivery path.
- Contact safety numbers can be verified; edit conflicts are detected rather than overwritten.
- Settings now shows the app version and build number ("Version 0.2.0 (build 2)"), in English and Simplified Chinese.

The version lives in `version.properties`; it follows the shared product version line.
