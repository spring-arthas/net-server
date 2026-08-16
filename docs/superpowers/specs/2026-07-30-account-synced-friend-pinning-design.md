# Account-Synced Friend Pinning Design

## Goal

Add an Android friend-list action that lets the logged-in user pin or unpin friends. Pin state is stored by the backend on that user's own friendship row, survives logout and reinstall, and is restored on other devices after the friend list is refreshed.

## Confirmed Product Behavior

- Pinning is account-level, not device-local.
- A user may pin multiple friends.
- Pinned friends always appear before unpinned friends.
- Within the pinned group, the most recently pinned friend appears first.
- Unpinned friends retain the current ordering: unread first, then online, then display name.
- Pinning one side of a friendship does not change the other user's friend list.
- The first version does not support drag-and-drop ordering.
- The first version synchronizes through the authoritative friend-list response; simultaneous devices do not require a new realtime pin push.

## Scope

This change covers:

- the `net-server` `user_friends` schema, friendship repository/service, frame registry, handler, and friend-list response;
- the Android protocol model/parser, chat API/repository/view model, and conversation-list UI;
- backend and Android automated tests;
- backend JAR and Android debug APK packaging.

It does not change friend creation, aliases, online-state ownership, unread counters, chat history, message delivery, or the macOS client.

## Current State

- `user_friends` is directional: each user owns a separate row for the same friendship. It already stores user-specific `alias`, making it the natural owner of user-specific pin state.
- The backend friend-list response currently contains relationship ID, friend identity, alias, avatar, online state, and unread data.
- Android currently stores the returned friends only in `ChatRepositoryState`; it does not persist a separate friend table.
- Android currently sorts friends by unread state, online state, and display name.
- A friend row currently opens the conversation on tap and has no contextual action.

## Database Design

Add two columns to `user_friends`:

```sql
is_pinned TINYINT(1) NOT NULL DEFAULT 0
pinned_at DATETIME(3) NULL
```

Add an index beginning with the relationship owner and active-state fields:

```text
(user_id, del, is_pinned, pinned_at)
```

The migration must be idempotent and follow the existing MySQL 8 migration style: inspect `information_schema` before adding each column or index. Existing rows remain unpinned because `is_pinned` defaults to `0`.

Pin updates must preserve request idempotency. Repeating `pinned=true` for an already pinned friendship must not refresh `pinned_at`, because a network retry must not silently reorder the pinned group. The update semantics are:

- transition `false -> true`: set `is_pinned=1` and `pinned_at=NOW(3)`;
- repeated `true -> true`: preserve the existing `pinned_at`;
- transition or repeated request to `false`: set `is_pinned=0` and `pinned_at=NULL`.

## Protocol Design

Reserve the next unused compatible chat/friend frame codes:

| Frame | Code | Purpose |
| --- | --- | --- |
| `FRIEND_PIN_UPDATE_REQ` | `0x5C` | Set one owned friendship's pin state |
| `FRIEND_PIN_UPDATE_RESP` | `0x5D` | Return the canonical stored state |

Request payload:

```json
{
  "relationshipId": 123,
  "pinned": true
}
```

Successful response data:

```json
{
  "relationshipId": 123,
  "pinned": true,
  "pinnedAt": 1785360000123
}
```

The request is a setter, not a toggle. This makes retries safe and lets the server return the canonical state. Stable errors cover unauthenticated access, invalid input, relationship not found or not owned, and database failure.

The existing friend-list response adds two backward-compatible fields to every item:

```json
{
  "pinned": false,
  "pinnedAt": null
}
```

Older clients ignore the new JSON fields. The backend must be deployed before the new Android client so the new frame codes are understood when the user invokes pinning.

## Backend Components

### Persistence Model

Extend `UserFriendsDo` and `UserFriendsDTO` with `pinned` and `pinnedAt`. The generic query conversion must carry both values without mutating aliases or the reverse friendship row.

Add an owned update to `UserFriendsRepository`. The `WHERE` clause must include:

- friendship `id`;
- current logged-in `user_id`;
- `del = 'N'`.

An update count other than one is treated as not found or not owned.

### Domain Service

Add `FriendshipService.updatePinned(userId, relationshipId, pinned)`. It validates non-null input, performs the owned idempotent update, then returns the canonical relationship pin state needed by the response.

### Frame Handler

Register `0x5C` and `0x5D` in the backend and Android registries. `TextTransmissionHandler` dispatches the request, obtains the user ID only from the authenticated channel context, calls the domain service, and returns a standard success/error envelope.

The handler must never accept `userId` from the request body and must not log complete request payloads.

### Friend-List Ordering

The backend friend-list response is the authoritative ordering. After enrichment with online and unread state, sort using:

1. `pinned == true` descending;
2. `pinnedAt` descending for pinned friends;
3. `unreadCount > 0` descending;
4. `online` descending;
5. effective display name ascending, case-insensitive;
6. `friendId` ascending as a deterministic final tie-breaker.

The response includes `pinned` and epoch-millisecond `pinnedAt` values.

## Android Components

### Protocol and API

Extend `ChatFriend` with:

```text
pinned: Boolean
pinnedAt: Long?
```

The parser defaults missing `pinned` to `false`, so the new APK can still display a friend list returned by an older backend. Add a serializable pin request model and `ChatApi.updateFriendPin(relationshipId, pinned)` using `0x5C/0x5D`.

### Repository State

Add `ChatRepository.updateFriendPin(friend, pinned)`. It uses an optimistic update so the row moves immediately, sends the setter request, then applies the canonical response. On failure it restores the previous friend snapshot and ordering and reports the error through the existing UI event path.

Centralize friend ordering in one pure comparator/helper shared by:

- initial friend-list refresh;
- optimistic pin/unpin updates;
- unread-count updates after opening a conversation;
- online-state refreshes.

This prevents later state mutations from accidentally losing pin ordering.

### View Model and UI

Expose `ChatViewModel.updateFriendPin(friend, pinned)` and use the existing event channel for success or failure feedback.

Interaction behavior:

- tapping a row continues to open the conversation;
- long-pressing a row opens a compact `ModalBottomSheet`;
- the action label is `置顶好友` or `取消置顶` according to current state;
- pinned rows display a small pin icon without replacing the unread badge;
- the row is disabled only while its own pin request is in flight, preventing conflicting rapid taps while leaving the rest of the list usable;
- search filters the already ordered list, so matching pinned friends remain above matching unpinned friends.

The first version does not add a separate pinned section header or drag handle.

## Cross-Device Semantics

The backend database is authoritative. A second device sees the new state on login, periodic friend refresh, manual refresh, or another operation that refreshes the friend list. No local DataStore or Room copy is required for correctness.

Realtime propagation to multiple simultaneously connected devices is intentionally excluded because the current server online-user registry keeps one active text connection per user. A future multi-device connection registry can add a `FRIEND_PIN_CHANGED` push without changing the persisted schema.

## Error Handling

- Missing authentication returns the existing not-logged-in error.
- Blank or invalid relationship IDs return an invalid-request error.
- A relationship belonging to another user is indistinguishable from a missing relationship.
- Backend database errors leave the stored value unchanged and return a stable friend-pin error code.
- Android restores its previous list when an update fails and shows a concise retryable message.
- Cancellation must propagate normally and must not be converted into a user-visible server error.

## Verification

Backend tests cover:

- exact frame compatibility for `0x5C/0x5D`;
- migration default behavior and expected columns/index contract;
- ownership enforcement;
- pin, repeated pin, unpin, and repeated unpin idempotency;
- `pinned_at` preservation on retry;
- friend-list response fields and the full deterministic comparator;
- error envelopes without sensitive payload logging.

Android tests cover:

- frame codes and request/response parsing;
- missing response fields defaulting to unpinned;
- pure sorting for pinned, pin time, unread, online, name, and friend-ID ties;
- optimistic pin and unpin success;
- rollback after server failure;
- prevention of conflicting repeated actions for the same row;
- pin icon and long-press action labels;
- search preserving relative pin order.

Build verification uses Java 8 for the backend and the existing Gradle Android toolchain. It produces the backend JAR and a debug APK. Emulator and manual login testing are not required unless explicitly requested.

## Rollout

1. Apply the idempotent `user_friends` migration.
2. Build and restart the backend with the new frame registry and handler.
3. Build the Android debug APK.
4. Install the APK and verify pin, unpin, restart persistence, and second-device refresh manually.

Rolling out the backend first avoids the new Android client sending an unknown frame to an older server. Existing Android clients remain compatible with the enriched friend-list JSON.
