# push/international

The international edition: Google Play services present, Firebase Cloud Messaging for wakes, and per-brand
guidance for the phone settings that decide whether a wake is delivered.

- `compat/` (`:push:international:compat`): one file per brand family under `brand/`, the brand registry and the
  step texts.
- `fcm/` (`:push:international:fcm`): the only module that may name Firebase or Play services classes. In this
  build `FcmPushProvider` is a placeholder that reports "not configured"; no Firebase dependency is linked yet.

## Application ids and Firebase

Each build type has its own application id and needs its own Android app in a Firebase project:

| Build type | Application id | Firebase project |
|---|---|---|
| `debug` | `org.ramflux.android.dev` | development |
| `realrun` | `org.ramflux.android.realrun` | development |
| `release` | `org.ramflux.android` | production (company account) |

To register one: in the Firebase console, add an Android app with the exact application id above, download its
`google-services.json`, and keep the file outside the repository. The build reads the Firebase values from that
file's path given by the Gradle property `ramflux.firebase.configFile` (the client entry that matches the build's
application id is used; no property or no match means "not configured"). The Google services Gradle plugin is not
applied, the file is never committed, and initialisation is manual. This wiring arrives with the Firebase
dependency; until then nothing reads the property.
