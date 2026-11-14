# Client capability boundary

This bridge exposes the SDK's current supervisor operations. Availability comes
from ABI negotiation and typed repository results, never from fixture data.
The native shell must present failures, missing capabilities and unknown mutation
outcomes explicitly. It must retain the original operation ID for reconciliation.

| Client flow | Repository surface |
| --- | --- |
| Startup, nodes, identity create/recover/unlock | Accounts and Nodes; SDK-owned creation/recovery ceremonies |
| Conversation list/history/send/receipts, attachments | Conversations; object transfers retain SDK bounds and ownership |
| Message edit/reaction/reply | Requires the SDK's unified message operations before activation |
| Group directory, membership, governance | Groups; channel/thread/post flows are deferred |
| Agent roster, tasks, permissions and connected services | Agents, Tasks, Permissions; SDK authority is decisive |
| Dedicated Agent create/configure | AgentConfiguration returns typed unavailable |
| Contacts, strangers, verification | Contacts; replying never implies accepting a request |
| Stranger reporting and batch decisions | StrangerReporting returns typed unavailable |
| Local full-text search | LocalSearch returns typed unavailable |
| Devices, pairing, backup, settings and sync | Devices, Accounts, Settings and Nodes |
| Cards and surfaces | Surfaces; renderer and security chrome are separate |
| Voice calls | Calls; video, group calls and screen sharing are deferred |
| Theme, language, OS permissions and navigation | Native-shell responsibilities; this module has no UI |

The Android Keystore wrapper is the host custody provider: records are sealed
with a non-exportable hardware key and the client opens through that provider.
Microphone/Telecom integration, notification delivery and physical-device
verification remain separate from these bridge compile checks.
