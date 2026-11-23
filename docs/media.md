# Media on Android

Images, animated images, videos and files travel as SDK attachments (`attachment.prepare.*`,
`conversation.send` with `prepares`, `attachment.import.*`, `attachment.revoke`). The design is
`MOBILE-MEDIA-DESIGN-01` in the planning workspace; this page states what the Android client does.

## Modules

- `:core:data` owns the SDK calls (`DefaultMediaRepository` over the generated `AttachmentsRepository`
  forwarders), the store, the auto-download policy, refusal mapping and the application transfer queue
  (`MediaTransfers`). Attachment references and the `next_before` paging cursor come from the SDK read-model
  reader (`MessageHistoryMetadataReader.page`); the data layer does not parse the page a second time.
- Drafts and media register with `AccountScopedStores` (Hilt set contributions in `ChatDataModule`), so
  both are erased before an account leaves the device.
- `:core:media` owns decoding and encoding on the device: `ImageDecoder` for still and animated images,
  Media3 Transformer for video transcoding, Media3 ExoPlayer for playback, BLAKE3 for the prepare digest.
- `:feature:chat` renders bubbles, albums, the viewer, the send preview, the gallery, forwarding and
  the storage screen (`MediaStorageRoute`, also shown from the chat menu).

## Sending

| Kind | What leaves the device |
|---|---|
| Still image | Pixels re-encoded (JPEG 82, PNG with alpha), long edge 2,560 px, no EXIF, GPS or name |
| GIF, animated WebP | The bytes, up to 15 MB; larger ones go as files |
| Video | MP4 (H.264, AAC), long edge 1,280 px, about 2.5 Mbit/s, no container metadata; refused above 10 minutes or 200 MB after compression |
| File | The bytes, up to 2 GiB |
| Camera photo | As a still image; the capture file is deleted after re-encoding |

Up to 16 items form one message (album). Forwarding imports each item if needed and prepares it again
for the new conversation; keys and grants are never reused. Group conversations are
offered when their directory row carries `group_id`, which the attachment prepare needs.

## Receiving and playback

Nothing from untrusted senders is downloaded or decoded until tapped. Videos download completely before
they play (the SDK has no range reads) and never play in the timeline. Animated images play only while
at least half visible, at most two at once, and not with animations removed or battery saver on.
Transfers show an indeterminate mark: the SDK reports no byte progress (requests R4, R5).

## Storage and eviction

Decrypted files live under `noBackupFilesDir/media/<account>/media/<conversation>/<message>-<index>`
(every part hashed). The automatic policy (`MediaRetention`):

- decrypted originals are never removed silently; after the 24-hour relay window the local copy may be
  the only one. They go when the person clears them on the storage screen (with confirmation), the
  message is erased or revoked, or the account is removed;
- staged outgoing files older than 24 hours (the prepare hard deadline) are removed;
- decoded images and poster frames live only in a memory cache bounded by bytes and are dropped on lock;
- hand-over copies for sharing and camera captures are removed the next time those folders are used.

## Background transfer

Imports and uploads run over the SDK's live gateway session inside the app process. WorkManager cannot
continue them after the session closes, because nothing can open the account without the person.
Downloads and uploads therefore run in the application queue (`MediaTransfers`): they continue when the
chat closes and end when the session locks or closes. `MediaTransferService` (a `dataSync` foreground
service, declared in `:feature:chat:impl` and allowlisted in `scripts/os-service-allowlist.json`) keeps
the process in the foreground with a notification naming no file or person while the queue is busy.
