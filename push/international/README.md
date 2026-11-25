# push/international

The international edition: Google Play services present, Firebase Cloud Messaging for wakes, and per-brand
guidance for the phone settings that decide whether a wake is delivered.

- `compat/` (`:push:international:compat`): one file per brand family under `brand/`, the brand registry and the
  step texts (English and Simplified Chinese). Every vendor screen is an `IntentCandidate`, data that only
  `SafeSettingsLauncher` (`:push:impl`) opens: it resolves first, skips what is missing and falls back to the
  app-details page. The vendor package names are also listed as `<queries>` in the module manifest; add a package
  there whenever a candidate names a new one.
- `fcm/` (`:push:international:fcm`): the only module that may name Firebase or Play services classes
  (`FcmPushProvider`, `PlayServicesStatus`, `FcmConfig`, the messaging service).

## Application ids and Firebase

Each build type has its own application id and needs its own Android app in a Firebase project:

| Build type | Application id | Firebase project |
|---|---|---|
| `debug` | `org.ramflux.android.dev` | development |
| `realrun` | `org.ramflux.android.realrun` | development |
| `release` | `org.ramflux.android` | production (company account) |

To register one: in the Firebase console, add an Android app with the exact application id above, download its
`google-services.json`, and keep the file outside the repository.

## Firebase configuration

The Google services Gradle plugin is not applied and no `google-services.json` is ever committed. The Gradle property
`ramflux.firebase.configFile` names the file, for example on the command line or in `~/.gradle/gradle.properties`:

```
./gradlew :app:assembleDebug -Pramflux.firebase.configFile=/path/outside/the/repository/google-services.json
```

`:push:international:fcm` reads every client entry of the file at build time and compiles
`package,appId,apiKey,projectId,senderId` records into its `BuildConfig`. At run time `FcmPushProvider` starts
`FirebaseApp` by hand (name `ramflux`) from the entry whose package name equals the app's application id. With no
property, an unreadable file, or no entry for the application id (for example a release build with only the
development file), nothing from Firebase runs and the state is `NOT_CONFIGURED`; the build still succeeds. The
values are never printed, logged or stored outside the build output.

The manifest of the fcm module turns off automatic start of messaging, analytics, the advertising id and default
data collection, removes the Firebase init provider and the `AD_ID` permission, and declares the messaging service
(not exported). The token is kept in memory only and is never logged; `PushToken.toString()` is redacted.

## Dependencies added for Firebase (international edition)

Only `firebase-messaging` is declared, through the Firebase BOM `35.1.0` (version catalog `libs.versions.toml`).
Resolved release runtime classpath of `:app` (from `:app:dependencies`):

| Artifact | Version | Licence (from its POM) |
|---|---|---|
| `com.google.firebase:firebase-bom` | 35.1.0 | Apache-2.0 |
| `com.google.firebase:firebase-messaging` | 26.0.1 | Apache-2.0 |
| `com.google.firebase:firebase-common` | 23.0.0 | Apache-2.0 |
| `com.google.firebase:firebase-components` | 20.0.0 | Apache-2.0 |
| `com.google.firebase:firebase-installations` | 20.0.0 | Apache-2.0 |
| `com.google.firebase:firebase-installations-interop` | 18.0.0 | Apache-2.0 |
| `com.google.firebase:firebase-datatransport` | 21.0.0 | Apache-2.0 |
| `com.google.firebase:firebase-encoders`, `-encoders-json`, `-encoders-proto` | 18.0.0, 19.0.0, 17.0.0 | Apache-2.0 |
| `com.google.firebase:firebase-annotations` | 18.0.0 | Apache-2.0 |
| `com.google.firebase:firebase-iid-interop` | 17.1.0 | Android Software Development Kit License |
| `com.google.firebase:firebase-measurement-connector` | 19.0.0 | Android Software Development Kit License |
| `com.google.android.datatransport:transport-api`, `-runtime`, `-backend-cct` | 5.0.0 | Apache-2.0 |
| `com.google.android.gms:play-services-base` | 18.9.0 | Android Software Development Kit License |
| `com.google.android.gms:play-services-basement` | 18.9.0 | Android Software Development Kit License |
| `com.google.android.gms:play-services-tasks` | 18.4.0 | Android Software Development Kit License |
| `com.google.android.gms:play-services-cloud-messaging` | 17.4.0 | Android Software Development Kit License |
| `com.google.android.gms:play-services-stats` | 17.0.2 | Android Software Development Kit License |

Not present: `firebase-analytics`, any `play-services-measurement*`, `play-services-ads-identifier`. The
`firebase-measurement-connector` library is only an interface for an analytics SDK that is not linked. The merged
manifest gains the permission `com.google.android.c2dm.permission.RECEIVE` (brought by the messaging service, listed
in `scripts/os-service-allowlist.json`); the `WAKE_LOCK` and `ACCESS_NETWORK_STATE` permissions that Firebase also
declares are removed by the removal markers of `:core:media`. The licence texts and the Google terms notice are in the
app's open-source notices (`app/src/main/assets/notices/`).

### Network capture still to do (needs a device)

Whether `firebase-datatransport` (and the Firebase installation service) contact Google beyond creating an
installation id and a token is unverified. On a phone with Play services and the development configuration, capture
the app's traffic from a cold start through the first token request and compare the hosts: expected are the
Firebase Installations and FCM registration endpoints only. Any host used for delivery telemetry means the
`firebase-datatransport` dependency must be excluded.

## Vendor screens verified on a device

Every vendor component under `compat/.../brand/` is unverified until a phone has opened it. Add one row per tested
device: the model, the OS build, the candidate that resolved and the screen it reached.

| Model | OS build | Candidate that resolved | Screen reached |
|---|---|---|---|
