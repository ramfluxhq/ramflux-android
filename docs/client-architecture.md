# Native client architecture

The application is the Hilt composition root. Feature modules have `api`, `impl`
and `test` libraries. Their API owns Navigation 3 destinations; implementation
libraries never depend on another feature implementation. Navigation owns
one-shot event collection and lifecycle-aware StateFlow collection. A Screen
renders its immutable State and emits Action only. ViewModels expose one
`onAction` entry and a separate Event channel, own their coroutine jobs, and
share replayable state with `WhileSubscribed(5_000)`.

Repositories in `core:data` own data domains and map SDK results into app-owned
values. No SDK reply, secret holder or SDK error enters UI, State or ViewModel.
The shared native supervisor remains behind `core:sdk`. The application process
lifecycle starts/stops the session through the repository. Stopping is confirmed
before the app reports its private data closed; a pending writer remains owned.
The shell's ready state means the supervisor opened, not account authentication.
Appearance preferences have their own repository and are not SDK account state.

The app-shell, onboarding and conversations features are installed. Selecting a feature
which is not installed returns typed `Unavailable(FEATURE_NOT_INSTALLED)` from
Data. This is not a fake account, conversation, or service response. Onboarding reads real account metadata and saved nodes, performs SDK node discovery,
confirms the reviewed public key and rechecks saved-node safety before selection.
Account creation, recovery and pairing return typed custody-provider unavailable;
no credential ceremony is simulated. Each subsequent feature integrates through its public API and repository contract.

The design system consumes the manifest-owned generated component catalog and
light/dark tokens. Handwritten code owns state, behavior, navigation, safe
insets, system-bar contrast and accessibility. The shell is scrollable and
constrains its content width using the shared column token. No card renderer or
security approval implementation is installed by the shell.

## Verification

`python3 scripts/check-client-architecture.py` rejects SDK imports above Data,
coroutine imports in Screens and sibling implementation dependencies. ViewModel
fakes implement the repository contract; tests exercise failure/unavailable,
owned retries, cancellation and one-shot navigation. Compose behavior and
Navigation 3 restoration tests run without native libraries. Roborazzi records
nine window sizes in both themes, large text and major state variants in CI;
images are uploaded as review artifacts, not automatically accepted goldens.

CI runs ktlint, unit/Compose tests and screenshots independently of native
assembly. The native job builds the actual SDK and app with the pinned source,
runs Android lint and checks all packaged native libraries and APK alignment.
Native symbols are uploaded separately. Native build caching is keyed by the
SDK revision, with Cargo fingerprint validation after a cache restore.

Conversations uses the account-scoped SDK directory with opaque pagination. Hidden
rows stay hidden and reader-qualified unread counts retain their exact unsigned
value. Pages recheck the active unlocked account before publication. Refresh on
resume clears old state; failures never become successful empty lists. The current
module reads snapshots on entry/refresh; it does not claim live event integration.
Local search and an uninstalled chat feature return typed unavailable.
