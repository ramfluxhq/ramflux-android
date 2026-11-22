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
Account creation runs the SDK's connection-local creation ceremony (begin, recovery
phrase, word challenge, confirm) through `AccountCeremonyRepository`. The remaining
setup actions (`AccountAccessRepository.setup`, which covers recovery and pairing)
return typed custody-provider unavailable; no credential ceremony is simulated. Each
subsequent feature integrates through its public API and repository contract.

The design system consumes the manifest-owned generated component catalog and
light/dark tokens. Handwritten code owns state, behavior, navigation, safe
insets, system-bar contrast and accessibility. No card renderer or security
approval implementation is installed by the shell.

## Shell, tabs and panes

`:core:navigation` holds the shell model; `:app` only composes it.

- **Top-level tabs.** Messages, Groups, Agents and More each own a saveable
  Navigation 3 back stack (`TopLevelNavigator`, created by
  `rememberTopLevelNavigator`). Switching tabs keeps every stack and the
  decorated entries of hidden tabs (saved state and ViewModels). Back pops the
  selected tab; at a tab root other than Messages it returns to Messages; at the
  Messages root it leaves the app. Selecting the selected tab returns it to its
  root.
- **Navigation outside the display.** `AdaptiveShell` places the bottom bar (and,
  once the Kit has one, a rail) outside `NavDisplay`, so it can change form with
  the window. At compact width the bar hides while the selected tab shows more
  than its root; with two panes it stays visible.
- **Window size.** The shell measures its own constraints into `AdaptiveSpec`
  (compact below 600 dp, medium to 840 dp, expanded above; two panes from 700 dp;
  short height below 480 dp). Screens never read the window size.
- **Panes.** Entries declare a `PaneRole` in their metadata
  (`entry<K>(metadata = pane(PaneRole.LIST))`). `PaneSceneStrategy`, written in
  house instead of the alpha adaptive library, shows a LIST entry with the DETAIL
  entry above it side by side at two-pane width, a LIST entry alone with an empty
  detail, and everything else as one pane. Opening a conversation replaces the
  detail (`openDetail`), so back in two panes clears the selection and keeps the
  list. Folding and unfolding only change the scene; the stack, and with it the
  selected conversation, is untouched. The list pane is the design frame width
  and at most 45 percent of the window.
- **Global routes.** Account setup (`OnboardingRoute`) and the post-creation
  permission flow open on the root stack over the whole shell
  (`RoutePolicy.isGlobal`); every other key is pushed on the selected tab.
- **Locked sessions.** Route keys implementing `AccountScoped` (or listed in the
  app's `SHELL_ROUTE_POLICY` until their API module adopts the marker) are dropped
  from the root stack and from every tab stack while the shell is not ready, so
  unlocking never reopens restored account data. Tab roots stay.
- **Screen contract.** Screens take a `Modifier` and pane or back parameters
  (`rooted`: a tab root or list pane without a back affordance), render State and
  emit Action. They do not cap their own width, do not own outer window insets
  and do not read the window size. The shell and the startup host apply the
  window insets; panes apply the width cap (`column_max`).

## Verification

`python3 scripts/check-client-architecture.py` rejects SDK imports above Data,
coroutine imports in Screens and sibling implementation dependencies. ViewModel
fakes implement the repository contract; tests exercise failure/unavailable,
owned retries, cancellation and one-shot navigation. Compose behavior and
Navigation 3 restoration tests run without native libraries; the back-stack and
pane rules have plain JVM tests in `:core:navigation` and Compose tests in
`ShellNavigationTest` (tab history, two panes, fold and unfold, restoration,
locked sessions).

Screenshots use one shared parameter list (`test-support/screenshot`): widths
360, 393, 700 and 1000 dp plus a 400 dp short window, the locales en, zh-CN,
en-XA (long text) and ar-XB (right to left), light and dark, font scale 1.0 and
2.0. Board captures at 393x852 are compared with the current design exports;
everything else is a review artifact. See [design alignment](design-alignment.md).

CI runs ktlint, unit/Compose tests and screenshots independently of native
assembly. The native job builds the actual SDK and app with the pinned source,
runs Android lint and checks all packaged native libraries and APK alignment.
Native symbols are uploaded separately. Native build caching is keyed by the
SDK revision, with Cargo fingerprint validation after a cache restore.

Conversations uses the account-scoped SDK directory with opaque pagination. Hidden
rows stay hidden and reader-qualified unread counts retain their exact unsigned
value. Pages recheck the active unlocked account before publication. Refresh on
resume clears old state; failures never become successful empty lists. The current
module reads snapshots on entry and refresh.
Local search and an uninstalled chat feature return typed unavailable.

Chat installs a public account/conversation route, final chat header, native message
bubbles and controlled composer. It reads the generation-1 message read model,
redacts deleted senders and content, and renders an unknown kind as a placeholder
instead of failing the thread. Account, session and route fences precede publication.
Send, reply, edit, react and delete go through the conversation ABI. The repository
retains the operation id when the outcome is uncertain. A blocked action retains
the draft and creates no message row. While the conversation is open, the client
subscribes for new messages and typing.
