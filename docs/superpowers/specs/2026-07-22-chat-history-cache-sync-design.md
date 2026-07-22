# Chat History Cache and Incremental Sync Design

## Goal

Improve per-friend chat history so a previously opened conversation renders from local data immediately, missed and new messages are persisted locally, and older messages load without increasingly expensive `OFFSET` scans. Cache operations must never close, reset, or otherwise mutate the chat socket connection.

## Scope

This change covers the `chat-storage` macOS client, the `net-server` history frame handler/service/mapper, the history response contract, and the client Core Data model. It does not change authentication, friend-list ownership, attachment transport, or socket connection lifecycle.

## Current Boundary

- The client sends history frame `0x53` and currently uses `friendId`, `offset`, and `limit`.
- The server queries `user_friend_message`, enriches every history page with both users' avatar Base64 strings, and returns frame `0x54`.
- The client keeps history only in `SocketManager.chatHistory`; Core Data currently persists transfer-task and attachment recovery metadata, not messages.
- Existing text-frame partial-write and large-frame receive fixes remain independent prerequisites. This design does not introduce a new transport.

## Proposed Protocol

The history request keeps the existing `friendId` and `limit` fields and adds two optional cursors:

```json
{
  "friendId": 42,
  "limit": 20,
  "beforeMessageId": 900,
  "afterMessageId": null
}
```

Exactly one cursor may be present. Both absent means the latest page. `beforeMessageId` requests older messages; `afterMessageId` requests messages newer than the local high-water mark. The server continues accepting legacy `offset` requests for older clients during rollout.

The response remains frame `0x54`, but its `data` adds pagination metadata:

```json
{
  "list": [],
  "hasMore": true,
  "nextBeforeMessageId": 860,
  "latestMessageId": 900
}
```

Messages are returned in ascending display order. The server fetches at most `limit + 1` rows internally so `hasMore` does not require an extra empty-page request. The response no longer contains `avatars`; avatar data comes from the friend/profile cache and is not repeated in every history page.

## Server Query and Indexing

Cursor queries use the monotonic message primary key and retain the existing two-party conversation predicate:

```sql
-- Older page
WHERE del = 'N'
  AND ((sender_id = :userId AND receiver_id = :friendId)
    OR (sender_id = :friendId AND receiver_id = :userId))
  AND id < :beforeMessageId
ORDER BY id DESC
LIMIT :limitPlusOne
```

The result is reversed before serialization. Newer-page queries use `id > :afterMessageId ORDER BY id ASC`. A migration adds an index covering the equality predicates, deletion flag, and cursor ordering: `(sender_id, receiver_id, del, id)`. The live table name and existing indexes must be checked before applying the migration; the checked-in legacy DDL uses a different table name.

The legacy `offset` path remains available but is not used by the new client. History queries explicitly exclude `del = 'Y'` records.

## Client Storage and State

Add a `ChatMessageEntity` to the existing Core Data model. It stores account identity, friend identity, server message ID, optional client message ID, sender/receiver IDs, content, type, status, quote fields, server time/group labels, and retraction/deletion state. Avatar Base64 is intentionally excluded. A deterministic `recordKey = accountId + friendId + messageId` is unique for upsert and deduplication.

`ChatHistoryStore` owns persistence operations:

- fetch the newest local page for one account/friend;
- upsert server messages by `recordKey`;
- delete local message rows for one account/friend when the user explicitly clears cached history;
- return the oldest and newest stored message IDs for synchronization.

`SocketManager.chatHistory` remains the observable in-memory projection used by SwiftUI. It is hydrated from Core Data before a network request, then updated from the same merged records. Core Data operations use the existing persistence stack and do not touch `SocketManager` streams or connection state.

## Loading Flow

1. Selecting a friend creates or reuses its conversation state without clearing the existing array.
2. The client fetches the newest local page and renders it immediately.
3. If local data is absent, it requests the latest server page and persists the result.
4. If local data exists, it requests `afterMessageId = localLatestId` to recover missed messages, upserts them, and merges by server message ID.
5. Realtime `0x51` pushes and successful send receipts are upserted into Core Data before updating the in-memory projection.
6. Reaching the top requests `beforeMessageId = localOldestId`, prepends the returned page, persists it, and restores the existing top-message scroll anchor.
7. A conversation is marked exhausted only from the server's `hasMore = false`; an exact multiple of the page size must not cause repeated empty requests.

## Error and Compatibility Rules

- A network failure does not discard local messages; the UI remains usable and exposes retry state.
- Duplicate pushes, history pages, and send receipts are idempotent by `recordKey` and client message ID.
- Account identity is part of every local query so another login on the same Mac cannot see the previous account's messages.
- Clearing cached history deletes only that account/friend's Core Data message rows and in-memory message projection. It must not call `disconnect`, clear receive buffers, close streams, or remove socket handlers.
- Pending unsent attachment batches continue using their existing Core Data entities and are restored independently of server history.

## Performance and Logging Rules

- Do not log complete history JSON or Base64 payloads on either side. Log request mode, friend ID, row count, serialized byte count, and elapsed stages.
- Keep the initial page at 20 messages until measurements show a different value is useful; increasing page size is not the primary optimization.
- Keep `LazyVStack` and stable message IDs. Avoid rebuilding unrelated conversations and avoid recomputing all date groups when only one page changes.

## Verification

Server tests cover cursor ordering, `hasMore`, deletion filtering, legacy-offset compatibility, and mapper SQL parameters. Client tests cover Core Data upsert/deduplication, account isolation, cache-first loading, after-cursor recovery, before-cursor prepend, push persistence, and clear-cache/socket isolation. Manual verification covers switching between friends, relaunching the client, loading a conversation with more than one page, receiving a message while another friend is open, and clearing cached history without disconnecting the socket.
