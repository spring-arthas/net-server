# Chat Attachment Batch Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reuse one `10087` connection for every physical file in one chat message, retain per-attachment retry and restart recovery, and provide independent on-demand downloads without changing cloud-drive transfer behavior.

**Architecture:** The server enables connection reuse only for requests marked `CHAT_ATTACHMENT` and splits per-file cleanup from connection cleanup. The macOS client adds a chat-only upload session, chat-only persistent transfer store, and attachment-level UI actions; existing cloud-drive upload and download entry points retain their defaults.

**Tech Stack:** Java 8 NIO, Fastjson, Swift 5, SwiftUI, Foundation streams, Core Data.

---

### Task 1: Add backward-compatible upload reuse metadata

**Files:**
- Modify: `src/main/java/com/alibaba/server/nio/model/file/request/FileUploadRequest.java`
- Modify: `src/main/java/com/alibaba/server/nio/model/file/FileUploadContext.java`

- [ ] Add nullable `Boolean connectionReuse` and `String batchId` to `FileUploadRequest`.
- [ ] Add `boolean connectionReuse` and `String batchId` to `FileUploadContext`.
- [ ] Populate the context fields in both new-upload and resume branches of `createAndInitializeUploadContext`.
- [ ] Treat missing `connectionReuse` as `false`, preserving old clients and cloud-drive behavior.

### Task 2: Split server task cleanup from connection cleanup

**Files:**
- Modify: `src/main/java/com/alibaba/server/nio/service/file/handler/FileUploadHandler.java`

- [ ] Replace the monolithic `realeaseResource` implementation with `releaseUploadTask` and `closeUploadConnection`.
- [ ] In `releaseUploadTask`, release the semaphore, remove task maps, rebalance limiters, close only the file resource, and remove `currentUploadTaskId` from the channel.
- [ ] In `closeUploadConnection`, remove the parser and channel data, then call `WriteQueueHelper.closeAfterPendingWrites`.
- [ ] After successful final ACK, retain the connection only when the completed context has `uploadPurpose=CHAT_ATTACHMENT` semantics through `connectionReuse=true`.
- [ ] Keep connection closure for cloud uploads, invalid requests, integrity failures, protocol failures and disconnect cleanup.
- [ ] Leave `needStop` as a pipeline-short-circuit only; do not use it as the connection-reuse decision.

### Task 3: Carry reuse metadata through the Swift upload protocol

**Files:**
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/DirectoryService.swift`

- [ ] Extend `FileMetaRequest` with optional `connectionReuse` and `batchId`.
- [ ] Extend `FileTransferService.uploadFile` with defaulted parameters:

```swift
connectionReuse: Bool = false,
batchId: String? = nil
```

- [ ] Include the values in both `RESUME_CHECK` JSON and `META_FRAME` payloads.
- [ ] Keep defaults false/nil so every existing cloud-drive call is unchanged.

### Task 4: Add a chat-only reusable upload session

**Files:**
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/Chat/ChatAttachmentUploadService.swift`

- [ ] Add `ChatAttachmentUploadSession`, owning one dedicated `SocketManager` and `FileTransferService`.
- [ ] Connect once to `host:10087`, expose sequential `upload(prepared:)`, and disconnect in `close()`/`deinit`.
- [ ] Pass `uploadPurpose: "CHAT_ATTACHMENT"`, `connectionReuse: true`, and the message `clientMsgId` as `batchId` for every physical file.
- [ ] Make `ChatAttachmentUploadService` accept a session so original, preview, thumbnail, and later attachments reuse the same connection.
- [ ] Preserve task IDs for retries and remap thumbnails after successful upload.

### Task 5: Stabilize pending attachment source files

**Files:**
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/Chat/ChatAttachmentModels.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/Chat/ChatAttachmentUploadService.swift`

- [ ] Write pasted screenshots as lossless PNG under Application Support before they enter a pending message.
- [ ] Copy selected files into a batch-safe Application Support directory so sandbox bookmarks or temporary clipboard memory are not required after restart.
- [ ] Add a restore initializer that rebuilds `PendingChatAttachment` from the stable path and metadata.
- [ ] Keep local negative attachment IDs stable across retries.

### Task 6: Add chat-only persistent batch, upload and download records

**Files:**
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/chat_storage.xcdatamodeld/chat_storage.xcdatamodel/contents`
- Create: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/Chat/ChatAttachmentTransferStore.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage.xcodeproj/project.pbxproj`

- [ ] Add `ChatAttachmentBatchEntity`, `ChatAttachmentTransferEntity`, and `ChatAttachmentDownloadEntity` with optional/defaulted attributes suitable for lightweight migration and CloudKit.
- [ ] Implement `ChatAttachmentTransferStore` as an observable singleton backed by those entities.
- [ ] Persist batch content, stable file path, attachment metadata, task ID, progress, state, errors, and successful file IDs.
- [ ] Convert persisted `uploading`/`downloading` records to `paused` during restore.
- [ ] Keep these records separate from `TransferTaskEntity` and never submit them to `TransferTaskManager`.

### Task 7: Upload every attachment independently and retain partial success

**Files:**
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/MainChatStorage.swift`

- [ ] Persist the batch before appending its local pending message.
- [ ] Replace the fail-fast loop with a session-owned loop that catches errors per attachment and continues to later attachments.
- [ ] Update the local `MIXED` payload after each success so successful attachments immediately retain server `fileId` values.
- [ ] If any retained attachment fails, keep the message local and do not send through `10086`.
- [ ] Add attachment-level retry and removal callbacks; retry only the selected record and send the message when every retained attachment has succeeded.
- [ ] If all files are uploaded but chat send fails, retry only `sendChatMessage`.
- [ ] Restore unsent batches for the active conversation after history loading.

### Task 8: Render outgoing attachment progress and retry controls

**Files:**
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Views/Chat/ChatMessageRow.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/MainChatStorage.swift`

- [ ] Observe the chat attachment transfer store in message rows.
- [ ] Show per-attachment waiting, uploading, paused, failed, and succeeded state.
- [ ] Add independent retry/remove actions for failed or paused outgoing attachments.
- [ ] Keep successful attachment cards and images visible when siblings fail.

### Task 9: Add independent receiver download state and retry

**Files:**
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/Chat/ChatAttachmentTransferStore.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Views/Chat/ChatMessageRow.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/MainChatStorage.swift`

- [ ] Create/update a download record when the user chooses a destination.
- [ ] Route the transfer through the existing `10088` download service while mirroring progress and terminal state into the chat-only store.
- [ ] Show independent download, progress, failure/retry, and downloaded state on ordinary attachment cards.
- [ ] Add retry for failed image preview fetches; automatically fetch only thumbnail/preview resources, not original files.
- [ ] Restore interrupted downloads as paused and retry only the selected attachment.

### Task 10: Static verification without compilation or tests

**Files:**
- Review all files above.

- [ ] Search every cloud upload call and confirm it relies on `connectionReuse=false` and `batchId=nil` defaults.
- [ ] Search server cleanup calls and confirm old/non-chat requests still close their connection.
- [ ] Search for placeholders and unmatched new type names.
- [ ] Run `git diff --check` independently in both repositories.
- [ ] Do not run Maven, Xcode build, unit tests, or UI tests per user instruction.
