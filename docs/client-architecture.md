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
phrase, word challenge, confirm with the six-digit account code) through
`AccountCeremonyRepository`. The account code is entered through the Kit code field
backed by an erasable buffer; unlock and lock go through `AccountAccessRepository`,
and the SDK's named refusals (wrong code, wait with its `retry_after_ms`, attempts
exhausted, missing device unlock secret) map to `AccountCodeRefusal`. Phrase recovery
(`account.recover.*`) works for an account whose data is on this installation;
the SDK's `account.list` reports every account on this device, open or locked, with its display name, so
unlock and the account list offer locked accounts by name and ask only for the six-digit code; the app keeps no
list of its own. An account the SDK refuses to open for a reason other than the code shows the typed reason and
can be removed with its local data (`removeLocal`, `account.remove_local`). Settings also signs the account out
(`signOut`, `account.sign_out`); both erase the host's account-scoped stores (drafts, outbox, media) first.
A new installation needs a data restore or pairing first. Pairing (`AccountAccessRepository.setup(PAIR)`) still returns typed
custody-provider unavailable; no credential ceremony is simulated. Each
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
- **Navigation outside the display.** `AdaptiveShell` places the top-level
  navigation outside `NavDisplay`, so it changes form with the window: the Kit tab
  bar (`KitTabBar`) at compact width and the Kit rail (`KitNavigationRail`) at
  medium and expanded widths. Both take the window's bottom inset from the shell;
  the tab bar draws over the home-indicator area as the design frame does. At
  compact width the bar hides while the selected tab shows more than its root;
  with two panes it stays visible, and the rail is always visible.
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
- **Bottom inset owners.** An entry declared with `pane(role, ownsBottomInset = true)`
  (the chat) receives the bottom window inset through `LocalShellBottomInset`
  instead of being padded above it, so its composer band reaches the home
  indicator; every other entry is padded. With the tab bar visible the bar takes
  the inset and owners receive zero.
- **Routes from the chat.** The chat's header and menu reach the group details
  (`GroupDetailRoute`, group chats only), the direct-conversation details (drawn over the chat by the chat
  feature, direct chats only: the contact, mute through `conversation.mute`, the contact's safety number and
  verification state, media and files, and this conversation's storage with clearing), a one-to-one call (`CallRoute`, direct
  chats only), the camera explanation (`PermissionsRoute(CAMERA_PHOTO)`), the
  conversation gallery (a sheet inside the chat) and media storage
  (`MediaStorageRoute`, also linked from Settings). The call history is a More
  hub row; answering an incoming call there opens `CallRoute(callId)`.
- **Global routes.** Account setup (`OnboardingRoute`) and the post-creation
  permission flow open on the root stack over the whole shell
  (`RoutePolicy.isGlobal`), framed by `ShellPage` (window insets including the
  keyboard, and the width cap); every other key is pushed on the selected tab.
- **Locked sessions.** Route keys implementing `AccountScoped` (chat, group details,
  call, media storage, contacts, tasks, calls, cards, settings and permissions
  declare it in their API module;
  the route policy keeps no list) are dropped from the root stack and from every
  tab stack while the shell is not ready, so unlocking never reopens restored
  account data. Tab roots stay.
- **Screen contract.** Screens take a `Modifier` and one pane parameter,
  `rooted`: the screen has nothing to go back to inside its pane (a tab root, a
  list pane, or a detail shown beside its list) and shows no back affordance. The
  shell tells each entry the role it is shown in (`LocalPaneRole`: list or detail
  side by side, none for a full pane); entry providers derive `rooted` from it,
  screens never read it. Screens render State and emit Action, do not cap their
  own width, do not own outer window insets, do not read the window size and do
  not intercept system back except to close an overlay they show (the chat's
  message actions), so predictive back runs through the shell. The shell, the
  startup host and `ShellPage` apply the window insets; panes apply the width cap
  (`column_max`).

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
value. Pages recheck the active unlocked account before publication; failures never
become successful empty lists. Every page merges into a bounded window
(`ConversationWindow`, 1,000 rows) that is ordered recent-first by `last_activity`
off the main thread and evicts the least recent rows. The SDK pages
`conversation.directory` by id, not by activity, so recent-first holds within the
rows loaded so far; after a full scan the window holds the most recent rows overall.
The end of the list loads the next page automatically until a page adds no row to
the window, then on request only. While the list is on screen it subscribes through
the shared event hub to `conversation.changed`, `message.received` and the inbound
contact-request topics (never `*`): changed rows are upserted in place, removed rows
dropped, the message-requests row recounted, and `resync_required` reloads the
window. The SDK directory row has no message preview; rows show the conversation
kind. A direct row has no SDK title: `DirectConversationTitles` names it from the row's direct-route recipient
(then its participants) against one `contact.list` read per page, reused for a minute and re-read on a miss at
most every five seconds, off the main thread. Without a matching contact the title is a short identifier
(`titleIsIdentifier`) drawn left-to-right isolated (`Bidi.ltr`); the raw `dm:` id is never shown. Local search and an uninstalled chat feature return typed unavailable.

Chat installs a public account/conversation route and renders on the Kit: chat
header, bubbles, date chips, unread divider, message actions and `KitComposer`.
Own messages sit on the end side in direct and group conversations (the read
model's `is_own`); incoming group messages show the sender. It reads the generation-1 message read model,
redacts deleted senders and content, and renders an unknown kind as a placeholder
instead of failing the thread. Account, session and route fences precede publication.
Send, reply, edit, react and delete go through the conversation ABI. The repository
retains the operation id when the outcome is uncertain. A blocked action retains
the draft and creates no message row. While the conversation is open, the client
subscribes for new messages and typing. The draft lives in ViewModel memory only:
closing the session on background or lid close (owner decision, 2026-11-23) loses
it, and it is not written to saved instance state, which the system may persist.

Contacts (people, strangers area, sent requests, blocked) read through windowed pages
(`ContactDirectoryRepository.contactPage` / `requestPage`) into a bounded `PageWindow`
(six pages of 100 rows; pages dropped at one end reload through the cursor at that end).
`contact.list` and `contact.requests.list` are unpaged in the pinned SDK, so the data layer
wraps them in `SnapshotPager`: a read without a cursor takes one SDK snapshot, filters it by the
query off the main thread and serves offset cursors into it; the reply itself is bounded by the
1 MiB frame. While visible the screen follows `contact.changed`, `safety.changed` and the request
topics through the shared event hub (never `*`) and reloads the affected window. Remove
(`contact.remove`, scope `me` as the reference CLI), block and unblock a contact by link id, and
mark a safety number verified (`contact.verify` with the SDK's compare-and-set stamp from the last
`contact.safety_number` read, kept in the data layer and sent verbatim) all require the host
confirmation and bind the reply to the target. Reply, report and scan have no SDK method and are
not offered. Settings, "My contact card", shows this account's signed card from `contact.card.export`
(`ramflux-card:v3:` text through `LtrCode`, the `ramflux://contact/v3/` link as a `KitQrCode` when it fits, and
share as text). The card names the home node and its key fingerprint, so a peer that adds the phone by the card
pins that node; one that adds it by principal lookup alone has no home node pinned and cannot deliver media
(`authority_unavailable`).

Groups (list, invitations, roster, invite picker and group requests) read through windowed pages
(`GroupDirectoryRepository.groupPage` / `memberPage` / `invitationPage` / `requestPage`, and the contacts'
`contactPage` for the picker), each into its own bounded `PageWindow` of six 100-row pages. `group.list`,
`group.members` and both request lists are unpaged in the pinned SDK, so the data layer wraps them in
`SnapshotPager` as for contacts. The group header (name, epoch, own role) comes from a separate
`group.members` read of which only the own roster row and the member count are kept. While visible the
screen follows `group.changed` and `conversation.changed` (never `*`); an event revokes the management
controls and any open review until the header has been read again. Rename, add an accepted contact, change
a role and remove a member need the host confirmation and are re-validated against a fresh read in the data
layer. Ownership transfer needs a creator certificate that a phone cannot produce and the SDK has no leave
method; both rows explain this instead of acting. One unpaged reply is bounded by the
1 MiB frame, about 2,490 roster rows with real-width ids; see "Reply size limits" in [bridge](bridge.md).

Agents read `agent.list` (eight records per page: one agent reply is serialized into at most 8 KiB by the
SDK) into a bounded `PageWindow` of twelve pages. `agent.list` pages forward only, so the data layer remembers
the start cursor of each page of the current listing (an id-only index) and serves it as the page's previous
cursor. The name filter runs over the loaded rows off the main thread. The SDK projects no agent event topic:
a refresh (resume, retry, after an own change) asks `agent.watch` for the records changed after the list's
journal cursor and reloads only when something changed. The phone edits the persona (`agent.persona.edit`)
and deletes an active agent (`agent.delete`), both with `confirmed` after the host confirmation; the delete
review says that the agent's keys on the desktop and its node record are not removed. Creation stays on the
desktop. A creation waiting for this device (`agent.operation.get` with an empty id) is shown read only:
`agent.creation.decide` needs the plan hash, which the C ABI does not return. The access policy is read only:
`agent.access.set` needs a base identity the read result does not carry. "Message" opens the direct conversation
with the agent through `DirectConversations` (below).

One way addresses a direct conversation by root anchor: `DirectConversations.resolve` reads this account's root
(`account.status` `principal_commitment`) and derives the SDK's id with `DirectConversationId`
(`"dm:" + base64url(BLAKE3(lp(domain) || lp(lower anchor) || lp(higher anchor)))`, `message/dm_scope.rs`,
Bouncy Castle BLAKE3, tested against the SDK's own golden vector). Agents ("Message" on an agent) and contacts
("Message" on a contact profile) use it; only when the account root cannot be read does it fall back to the
directory row whose direct route names the peer. Message notifications open their conversation with the
group id from the directory row, as a list tap does.

Tasks read task conversations (`conversation.directory`), the tasks of one conversation (`task.list`) and the
permission list (`grant.list`: grants, approvals and decision receipts) through windowed sources
(`TaskDirectoryRepository.contextPage` / `taskPage` / `permissionPage`), each into a bounded `PageWindow` of six
pages; rows are projected once per page as it arrives. The two forward-only SDK cursors get their `previous`
cursor from `ForwardCursorIndex` (shared with agents); `grant.list` is unpaged and served through `SnapshotPager`.
While visible each page follows only its own topics through the shared event hub (never `*`): conversations on
the conversation list, `task.changed` on the task pages, and `task.changed` plus `mcp.approval.request`,
`mcp.approval.granted` and `mcp.approval.denied` on the permission pages. The task detail shows the steps; accept,
decline and cancel keep the original operation identity and the host confirmation. A pending approval is shown
with `KitApprovalCard`: approve or deny first re-reads the approval (`permission`), only an unchanged pending
approval reaches the host confirmation, and the data layer re-reads it again before `grant.approve` or
`grant.deny`. A `remote_app` approval needs a grant signed by the requesting app (`RemoteAppApprovalRequired`
otherwise) and the C ABI exposes no app signing, so its card says so and offers deny only.

## System services

A production manifest may declare a `<service>` only when `scripts/os-service-allowlist.json` names its exact
class, foreground service type and the permissions it brings; `scripts/check-os-permissions.py` enforces this for
every `src/main` manifest and the merged debug manifest (`scripts/test-os-permissions.py` holds its tests). A
`uses-permission` marked `tools:node="remove"` removes a library permission from the merged manifest and is not
counted as a declaration, so such removals (Media3's network-state and wake-lock permissions in `:core:media`)
live in `src/main`.

Declared today: `org.ramflux.feature.chat.MediaTransferService` (`dataSync`, not exported, with
`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`) in `:feature:chat:impl`, so media imports and uploads
continue when the chat closes inside the unlocked session (see [media](media.md)).

Not declared until calls carry media (request R28): the call foreground service and the self-managed Telecom
connection service. Their classes are in `:feature:calls:impl` (`CallSystem.kt`) and stay inert because
`CallTelecom.declared()` checks the declaration at run time and the call media engine refuses until R28. When R28 lands, these are the lines to
add to `feature/calls/impl/src/main/AndroidManifest.xml`, each with an allowlist entry:

```xml
<uses-permission android:name="android.permission.MANAGE_OWN_CALLS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<application>
    <service
        android:name="org.ramflux.feature.calls.CallForegroundService"
        android:exported="false"
        android:foregroundServiceType="phoneCall|microphone" />
    <service
        android:name="org.ramflux.feature.calls.RamfluxConnectionService"
        android:exported="true"
        android:permission="android.permission.BIND_TELECOM_CONNECTION_SERVICE">
        <intent-filter>
            <action android:name="android.telecom.ConnectionService" />
        </intent-filter>
    </service>
</application>
```

The connection service must be exported to the system's Telecom process (guarded by
`BIND_TELECOM_CONNECTION_SERVICE`), which the guard's "not exported" rule does not admit today; that rule needs a
reviewed exception for it when R28 lands.

