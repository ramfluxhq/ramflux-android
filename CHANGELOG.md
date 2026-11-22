# Changelog

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

The version lives in `version.properties`; bump it with the shared `bump-version.sh` script.
