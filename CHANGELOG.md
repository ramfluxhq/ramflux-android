# Changelog

## [Unreleased]

- A message that arrives while the app is in front is announced by an in-app banner drawn in the activity's own window (sender name and a one-line preview of at most 80 sanitised characters; fixed texts for calls, system events and media; about four seconds, tap opens the conversation, swipe up dismisses, a newer message replaces it). There is none for the open conversation, a muted conversation or the user's own message, and nothing is posted to the system in front. The banner host is kept out of autofill, content capture and (API 34+) non-tool accessibility services and announces through a polite live region; its state is never saved or logged. Every build turns off the Overview screenshot (API 33+) and content capture for the activity before content is set.
- A refused request no longer shows a bare number: one shared mapping turns the SDK's typed status name and `reason_code` into the text of every screen (the code, then the name and reason token, for example "Read failed (code 16, UsernameDirectoryRefused: directory_unavailable)"), and a reason the app has words for is shown in words (a node without a username directory says "Usernames are not available on this server yet.").
- SDK pin moved to the core's `next` head `5d117366` (runtime expiry and preemption, backup restore keeps attachment ownership, a read receipt emits one typed `message.updated` update so the sender's row carries Read): the username directory trust pin is passed in the open config (provisioned by the build from `ramflux.usernameDirectory.*` Gradle properties, and by the real-run script from the node facts), refusals use the SDK's typed `name` and `reason_code` and every status 0-31 is named from the generated header table, a group chat never asks for typing focus (a refusal is tolerated silently), the debug and real-run builds turn on the SDK diagnostic log and the real-run script copies it from the phone after each invocation, and a failed suspend is still followed by a resume (the SDK now rolls it back as a whole).
- The real-run script exits non-zero whenever the flow table holds a FAIL row or the instrumentation did not run to its end, and the table states an overall verdict.
- SDK pin moved to the core's `next` head `f2f466be2`: a contact re-added from its card keeps the pinned node fingerprint and a receive error no longer stops the apply pass (media send, later events and group invitations after a card exchange), the attachment contract is documented (the client already enables, prepares, streams and sends `prepares` as it describes), no plaintext attachment copy stays in the SDK after a send, agents call like people, and the app-lock methods enter the policy (not used by the app yet).
- SDK pin moved to the core's `next` head `14e38cda6`: suspend and resume, prompt-free cold open through the device-bound key, read receipts with a derived route, conversation names, leave group, the group invitation topic, username methods on phones and the SDK-verified AI marker.
- Returning from the background or relaunching asks for nothing: the client is suspended in the background and resumed on return (same session, screens and draft kept), and a new process waits while the SDK reopens the account through its device-bound key. The code is still asked after an explicit lock or sign-out, at set-up, restore and to change it. The SDK data root is a dedicated private directory.
- The AI marker appears wherever a person is shown (contacts and requests, the conversation list, the chat header, group members and the authors of group messages); agents are otherwise shown exactly like people.
- Direct conversations take their names from the SDK; group members and the authors of group messages are named from contacts instead of showing a device id. Group service rows (member added, name changed, ...) are centred lines, not undisplayable bubbles.
- Groups: leave a group (every member but the owner); invitations arrive live through `group.invitation`; the invitation buttons and the create actions carry test tags.
- Usernames: Privacy shows this account's username or the directory's typed refusal, and Contacts adds a contact by `@name`.
- Settings keeps the account and its actions usable while it refreshes; a tap on Lock right after opening the page was dropped before.
- Real run: the flow table and the instrumentation agree (a FAIL fails the test, a dead process or failed test is recorded as FAIL), a failing flow leaves a UI dump, every invocation starts from a stopped app, flows 03 and 07 fail on any prompt, and new flows cover the read receipt, an invitation from a non-contact with leaving, and usernames. Flow 09 waits for the create action that is laid out.

- SDK pin moved to the core's `next` head `e39111de5`: phone attachment send, own replies and the invitation list fixed in the SDK; account list with locked accounts, the unlock wait, sign-out and local removal; card session methods in the policy.
- Groups: a contact is added by its principal id alone (an explicit root made the SDK read the principal id as a device id, code 16); a new group opens the conversation its creation reply names, and opening a listed group asks the group's metadata before history.
- Accounts come from the SDK's `account.list` (locked ones included); the app's own account registry is removed. Settings signs the account out and removes it from this phone.
- The wait after wrong account codes is read from the SDK's `retry_after_ms`.
- Contact verification reads the SDK's `verificationState` reply field, so a successful verify is no longer reported as an unknown outcome.
- The composer draft survives background and lock: a session change while the draft was still being restored no longer erases the sealed draft.
- Own replies use the SDK's `is_own`; the client-side author-device workaround is removed.
- Debug trace: one start line per process, the sink is volatile, the suite marks flows under the trace tag directly, and the confirmation trace logs at warning level.
- Debug and realrun builds also write the trace to a private file that `scripts/realrun.sh` copies to `app-trace.txt`, because the test phone keeps only part of an app's own log lines.
- Groups: after an add the member list is shown again, and a finished change always clears the busy state.
- Chat: a send refused for a missing contact card (`authority_unavailable`) is shown as not sent with a retry and a hint to exchange contact cards, not as offline; the keyboard closes before the attach menu opens.
- Real run: each invocation exits non-zero when one of its own flows fails; new flows cover the group image, the invitation list, sign-out and removal from the phone.
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
