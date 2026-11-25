# push

Background delivery for the Android client: how a wake reaches the phone, and what the phone's operating
system needs so that it arrives. Design: `ANDROID-PUSH-COMPAT-01` (workspace documents).

```
push/
├── api/                  :push:api                      interfaces and value types only
├── impl/                 :push:impl                     edition-neutral logic and screens
├── international/
│   ├── compat/           :push:international:compat     per-brand settings guidance
│   └── fcm/              :push:international:fcm        Firebase Cloud Messaging provider
└── china/                reserved, no module
```

## Edition selection

The Gradle property `ramflux.edition` (default `international`, set in `gradle.properties`) selects which edition
modules `:app` depends on. `international` adds `:push:international:compat` and `:push:international:fcm`; any
other value fails configuration with "edition not implemented". All edition modules stay in
`settings.gradle.kts`, so lint, ktlint and the guard scripts always see them. There are no product flavours.

## Rules

1. Google, Firebase and Play services classes appear only under `push/international/fcm/`;
   `scripts/check-client-architecture.py` enforces it.
2. Brand knowledge appears only under `push/<edition>/compat/brand/`. `:push:impl` never names a vendor.
3. Every vendor intent is data (`IntentCandidate`), launched only through `SafeSettingsLauncher` in `:push:impl`.
4. Nothing under `push/` logs a token, route tag, wake id or account id, and the types that carry them redact
   `toString()`.
5. Text shown to the user is a string resource, complete in every shipped locale (`scripts/check-ui-resources.py`).

## Dependencies

| Module | May depend on | Must not depend on |
|---|---|---|
| `:push:api` | `:core:navigation` | Google classes |
| `:push:impl` | `:push:api`, `:core:data`, `:core:designsystem` | `:core:sdk`, any edition module |
| `:push:international:compat` | `:push:api` | `:push:impl`, Google classes |
| `:push:international:fcm` | `:push:api` | `:push:impl`, `:core:sdk`, `:core:data` |
