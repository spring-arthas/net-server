# Account-Synced Friend Pinning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an account-synced Android friend pin/unpin action whose canonical state and deterministic ordering are stored and returned by `net-server`.

**Architecture:** The directional `user_friends` row owns `is_pinned` and `pinned_at`. A setter frame (`0x5C/0x5D`) updates only the logged-in user's relationship row and returns canonical state; both server and Android share the same ordering rules, while Android applies a per-row optimistic reducer with rollback.

**Tech Stack:** Java 8, Java NIO, Fastjson, Spring transactions, MyBatis annotations, MySQL 8, JUnit 4, Kotlin, kotlinx.serialization, coroutines, Jetpack Compose Material 3, Gradle.

---

### Task 1: Backend schema and frame contract

**Files:**
- Create: `sql/user_friend_pinning_migration_20260730.sql`
- Modify: `src/main/java/com/alibaba/server/nio/model/file/FileUploadFrame.java`
- Test: `src/test/java/com/alibaba/server/nio/model/file/FriendPinFrameTypeTest.java`
- Test: `src/test/java/com/alibaba/server/nio/repository/user/mapper/UserFriendsPinMigrationContractTest.java`

- [ ] **Step 1: Write failing frame and migration contract tests**

```java
assertEquals(0x5C, FileUploadFrame.FrameType.USER_FRIEND_PIN_UPDATE_REQ.getCode());
assertEquals(0x5D, FileUploadFrame.FrameType.USER_FRIEND_PIN_UPDATE_RESPONSE.getCode());
assertTrue(migration.contains("is_pinned TINYINT(1) NOT NULL DEFAULT 0"));
assertTrue(migration.contains("pinned_at DATETIME(3) NULL"));
assertTrue(migration.contains("user_id, del, is_pinned, pinned_at"));
```

- [ ] **Step 2: Run tests and confirm missing enum constants and migration fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn -Dtest=FriendPinFrameTypeTest,UserFriendsPinMigrationContractTest test`

Expected: compilation or assertion failure because `0x5C/0x5D` and the migration do not exist.

- [ ] **Step 3: Add compatible frame constants and idempotent MySQL 8 migration**

```java
USER_FRIEND_PIN_UPDATE_REQ(0x5C, "更新好友置顶请求"),
USER_FRIEND_PIN_UPDATE_RESPONSE(0x5D, "更新好友置顶回执"),
```

The migration checks `information_schema.COLUMNS` separately for `is_pinned` and `pinned_at`, checks `information_schema.STATISTICS` for `idx_user_friends_pin_order`, and executes prepared `ALTER TABLE` statements only when each object is absent.

- [ ] **Step 4: Re-run focused tests and confirm PASS**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn -Dtest=FriendPinFrameTypeTest,UserFriendsPinMigrationContractTest test`

Expected: all focused tests pass.

### Task 2: Backend owned, idempotent persistence

**Files:**
- Modify: `src/main/java/com/alibaba/server/nio/repository/user/repository/dataobject/UserFriendsDo.java`
- Modify: `src/main/java/com/alibaba/server/nio/repository/user/service/dto/UserFriendsDTO.java`
- Create: `src/main/java/com/alibaba/server/nio/repository/user/service/dto/FriendPinUpdateResult.java`
- Modify: `src/main/java/com/alibaba/server/nio/repository/user/mapper/UserFriendsRepository.java`
- Modify: `src/main/java/com/alibaba/server/nio/repository/user/service/UserFriendsService.java`
- Modify: `src/main/java/com/alibaba/server/nio/repository/user/service/impl/UserFriendsServiceImpl.java`
- Modify: `src/main/java/com/alibaba/server/nio/repository/user/service/FriendshipService.java`
- Modify: `src/main/java/com/alibaba/server/nio/repository/user/service/impl/FriendshipServiceImpl.java`
- Modify: `src/test/java/com/alibaba/server/nio/repository/user/service/impl/FriendshipServiceImplTest.java`
- Test: `src/test/java/com/alibaba/server/nio/repository/user/mapper/UserFriendsRepositoryPinContractTest.java`

- [ ] **Step 1: Write failing service and SQL contract tests**

```java
FriendPinUpdateResult result = service.updatePinned(7, 12L, true);
assertEquals(Long.valueOf(12L), result.getRelationshipId());
assertTrue(result.isPinned());
assertEquals(pinnedAt, result.getPinnedAt());
```

The repository contract asserts the update `WHERE` contains `id`, `user_id`, and `del = 'N'`, uses `COALESCE(pinned_at, NOW(3))` for repeated pin idempotency, clears `pinned_at` on unpin, and re-reads the same owned row.

- [ ] **Step 2: Run focused tests and confirm missing API/SQL failures**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn -Dtest=FriendshipServiceImplTest,UserFriendsRepositoryPinContractTest test`

Expected: compilation or assertion failure because pin persistence is absent.

- [ ] **Step 3: Implement the minimal owned setter and canonical result**

```java
@Update("UPDATE user_friends SET is_pinned = #{pinned}, "
        + "pinned_at = CASE WHEN #{pinned} = 1 THEN COALESCE(pinned_at, NOW(3)) ELSE NULL END, "
        + "gmt_modified = NOW() WHERE id = #{id} AND user_id = #{userId} AND del = 'N'")
int updateOwnedPin(@Param("id") Long id, @Param("userId") Integer userId,
        @Param("pinned") boolean pinned);
```

`FriendshipService.updatePinned` validates non-null positive IDs, requires exactly one owned update, re-reads the row, and maps `is_pinned/pinned_at` to `FriendPinUpdateResult`. `UserFriendsServiceImpl` carries the two new fields into friend-list DTOs.

- [ ] **Step 4: Re-run focused tests and confirm PASS**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn -Dtest=FriendshipServiceImplTest,UserFriendsRepositoryPinContractTest test`

Expected: owned update, not-owned rejection, pin, repeated pin, unpin, and canonical result tests pass.

### Task 3: Backend handler and authoritative ordering

**Files:**
- Create: `src/main/java/com/alibaba/server/nio/service/file/handler/FriendListItemComparator.java`
- Modify: `src/main/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandler.java`
- Test: `src/test/java/com/alibaba/server/nio/service/file/handler/FriendListItemComparatorTest.java`
- Test: `src/test/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandlerFriendPinContractTest.java`

- [ ] **Step 1: Write failing comparator and handler boundary tests**

```java
Collections.sort(items, FriendListItemComparator.INSTANCE);
assertEquals(Arrays.asList(3L, 2L, 1L), relationshipIds(items));
```

The comparator test separately proves pin, `pinnedAt`, unread, online, case-insensitive display name, and `friendId` ties. The handler contract proves authentication comes from `loggedInUserId`, the body only reads `relationshipId/pinned`, and the response uses `USER_FRIEND_PIN_UPDATE_RESPONSE` without payload logging.

- [ ] **Step 2: Run focused tests and confirm failures**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn -Dtest=FriendListItemComparatorTest,TextTransmissionHandlerFriendPinContractTest test`

Expected: missing comparator and handler dispatch failures.

- [ ] **Step 3: Implement handler, response fields, and final sorting**

```java
item.put("pinned", Boolean.TRUE.equals(friend.getPinned()));
item.put("pinnedAt", friend.getPinnedAt() == null ? null : friend.getPinnedAt().getTime());
resultList.sort(FriendListItemComparator.INSTANCE);
```

The new handler validates authentication and request fields, calls `updatePinned`, returns `relationshipId/pinned/pinnedAt`, maps invalid/not-owned input to `INVALID_REQUEST`, and maps unexpected persistence failures to `FRIEND_PIN_UPDATE_FAILED` without serializing the request.

- [ ] **Step 4: Re-run focused tests and confirm PASS**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn -Dtest=FriendListItemComparatorTest,TextTransmissionHandlerFriendPinContractTest test`

Expected: comparator and handler contract tests pass.

### Task 4: Android protocol and API

**Files:**
- Modify: `/Users/hljy/androidProjects/chat-storage-android/core/protocol/src/main/kotlin/com/alibaba/chatstorage/protocol/FrameType.kt`
- Modify: `/Users/hljy/androidProjects/chat-storage-android/core/protocol/src/main/kotlin/com/alibaba/chatstorage/protocol/model/ChatModels.kt`
- Modify: `/Users/hljy/androidProjects/chat-storage-android/core/protocol/src/main/kotlin/com/alibaba/chatstorage/protocol/model/ProtocolJson.kt`
- Modify: `/Users/hljy/androidProjects/chat-storage-android/core/protocol/src/test/kotlin/com/alibaba/chatstorage/protocol/FrameCodecTest.kt`
- Modify: `/Users/hljy/androidProjects/chat-storage-android/core/protocol/src/test/kotlin/com/alibaba/chatstorage/protocol/model/ProtocolJsonTest.kt`
- Modify: `/Users/hljy/androidProjects/chat-storage-android/app/src/main/kotlin/com/alibaba/chatstorage/android/data/chat/ChatApi.kt`
- Modify: `/Users/hljy/androidProjects/chat-storage-android/app/src/test/kotlin/com/alibaba/chatstorage/android/data/chat/ChatApiTest.kt`

- [ ] **Step 1: Write failing protocol parsing and API tests**

```kotlin
assertThat(FrameType.FRIEND_PIN_UPDATE_REQ.code).isEqualTo(0x5Cu.toUByte())
assertThat(legacyFriend.pinned).isFalse()
assertThat(pinnedFriend.pinnedAt).isEqualTo(1785360000123L)
```

The API test captures the outgoing frame, asserts `relationshipId` and `pinned`, responds on `0x5D`, and verifies the canonical result.

- [ ] **Step 2: Run protocol and API tests and confirm failures**

Run: `./gradlew :core:protocol:test --tests '*FrameCodecTest' --tests '*ProtocolJsonTest' :app:testDebugUnitTest --tests '*ChatApiTest'`

Expected: missing frame/model/parser/API failures.

- [ ] **Step 3: Add backward-compatible models, parser, and setter API**

```kotlin
@Serializable data class FriendPinUpdateRequest(val relationshipId: Long, val pinned: Boolean)
data class FriendPinUpdateResult(val relationshipId: Long, val pinned: Boolean, val pinnedAt: Long?)
```

`ChatFriend` gains defaulted `pinned = false` and `pinnedAt = null`; `ProtocolJson` defaults absent fields; `ChatApi.updateFriendPin` requires a successful canonical response from `0x5D`.

- [ ] **Step 4: Re-run focused Android tests and confirm PASS**

Run: `./gradlew :core:protocol:test --tests '*FrameCodecTest' --tests '*ProtocolJsonTest' :app:testDebugUnitTest --tests '*ChatApiTest'`

Expected: protocol and API tests pass.

### Task 5: Android ordering and optimistic state

**Files:**
- Create: `/Users/hljy/androidProjects/chat-storage-android/app/src/main/kotlin/com/alibaba/chatstorage/android/data/chat/FriendListOrdering.kt`
- Create: `/Users/hljy/androidProjects/chat-storage-android/app/src/main/kotlin/com/alibaba/chatstorage/android/data/chat/FriendPinState.kt`
- Modify: `/Users/hljy/androidProjects/chat-storage-android/app/src/main/kotlin/com/alibaba/chatstorage/android/data/chat/ChatUiModels.kt`
- Modify: `/Users/hljy/androidProjects/chat-storage-android/app/src/main/kotlin/com/alibaba/chatstorage/android/data/chat/ChatRepository.kt`
- Test: `/Users/hljy/androidProjects/chat-storage-android/app/src/test/kotlin/com/alibaba/chatstorage/android/data/chat/FriendListOrderingTest.kt`
- Test: `/Users/hljy/androidProjects/chat-storage-android/app/src/test/kotlin/com/alibaba/chatstorage/android/data/chat/FriendPinStateTest.kt`

- [ ] **Step 1: Write failing pure ordering and reducer tests**

```kotlin
assertThat(sortFriends(friends).map(ChatFriend::friendId)).containsExactly(3L, 2L, 1L).inOrder()
assertThat(begin.state.pinUpdatesInFlight).containsExactly(relationshipId)
assertThat(rollback.friends.single { it.relationshipId == relationshipId }).isEqualTo(previous)
```

Tests cover all six sort keys, optimistic pin/unpin, canonical success, target-only rollback, and duplicate same-row prevention.

- [ ] **Step 2: Run focused reducer tests and confirm failures**

Run: `./gradlew :app:testDebugUnitTest --tests '*FriendListOrderingTest' --tests '*FriendPinStateTest'`

Expected: missing helpers and state fields fail.

- [ ] **Step 3: Centralize ordering and wire repository mutation**

```kotlin
val friendComparator = compareByDescending<ChatFriend> { it.pinned }
    .thenByDescending { if (it.pinned) it.pinnedAt ?: Long.MIN_VALUE else Long.MIN_VALUE }
    .thenByDescending { it.unreadCount > 0 }
    .thenByDescending(ChatFriend::online)
    .thenBy { it.displayName.lowercase(Locale.ROOT) }
    .thenBy(ChatFriend::friendId)
```

Every friend-list mutation calls `sortFriends`. `updateFriendPin` begins one per-row optimistic mutation, calls the setter, applies canonical state, and on failure restores only the target snapshot before rethrowing; cancellation also rolls back and propagates.

- [ ] **Step 4: Re-run focused reducer tests and confirm PASS**

Run: `./gradlew :app:testDebugUnitTest --tests '*FriendListOrderingTest' --tests '*FriendPinStateTest'`

Expected: all ordering and reducer tests pass.

### Task 6: Android ViewModel and long-press UI

**Files:**
- Modify: `/Users/hljy/androidProjects/chat-storage-android/app/src/main/kotlin/com/alibaba/chatstorage/android/ui/messages/ChatViewModel.kt`
- Modify: `/Users/hljy/androidProjects/chat-storage-android/app/src/main/kotlin/com/alibaba/chatstorage/android/ui/messages/MessagesScreen.kt`
- Test: `/Users/hljy/androidProjects/chat-storage-android/app/src/test/kotlin/com/alibaba/chatstorage/android/ui/messages/FriendPinUiContractTest.kt`

- [ ] **Step 1: Write failing UI contract tests**

```kotlin
assertThat(friendPinActionLabel(false)).isEqualTo("置顶好友")
assertThat(friendPinActionLabel(true)).isEqualTo("取消置顶")
assertThat(source).contains("ModalBottomSheet(")
assertThat(source).contains("onLongClick")
assertThat(source).contains("Icons.Outlined.PushPin")
```

- [ ] **Step 2: Run focused UI tests and confirm failures**

Run: `./gradlew :app:testDebugUnitTest --tests '*FriendPinUiContractTest'`

Expected: missing label helper and UI affordances fail.

- [ ] **Step 3: Add ViewModel action, per-row disable, pin icon, and compact sheet**

`ChatViewModel.updateFriendPin` reports `已置顶好友` or `已取消置顶` on success and existing retryable errors on failure. `FriendRow` uses `combinedClickable`; tap still opens chat, long press opens the sheet, the action is disabled only while that relationship ID is in flight, and the pin icon is shown independently of the unread badge.

- [ ] **Step 4: Re-run focused UI tests and confirm PASS**

Run: `./gradlew :app:testDebugUnitTest --tests '*FriendPinUiContractTest'`

Expected: UI contract tests pass.

### Task 7: Full verification and packaging

**Files:**
- Verify: both repository worktrees and generated build artifacts
- Copy: `/Users/hljy/androidProjects/chat-storage-android/app/build/outputs/apk/debug/app-debug.apk`
- Create: `/Users/hljy/Downloads/chat-storage-android-friend-pinning-debug.apk`

- [ ] **Step 1: Run backend tests on Java 8**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn test`

Expected: all backend tests pass.

- [ ] **Step 2: Package backend fat JAR on Java 8**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn clean package`

Expected: `target/net-server-1.0-SNAPSHOT.jar` exists and is non-empty.

- [ ] **Step 3: Run Android unit tests and build Debug APK**

Run: `./gradlew :core:protocol:test :app:testDebugUnitTest :app:assembleDebug`

Expected: Gradle reports `BUILD SUCCESSFUL` and `app-debug.apk` exists.

- [ ] **Step 4: Copy and checksum APK without installing it**

Run: `cp app/build/outputs/apk/debug/app-debug.apk /Users/hljy/Downloads/chat-storage-android-friend-pinning-debug.apk && shasum -a 256 /Users/hljy/Downloads/chat-storage-android-friend-pinning-debug.apk`

Expected: the Downloads APK is non-empty and a SHA-256 checksum is printed.

- [ ] **Step 5: Inspect final scoped diffs**

Run backend: `git status --short && git diff --check`

Run Android: `git status --short && git diff --check`

Expected: no whitespace errors; all unrelated pre-existing changes remain present. Do not create a commit.

## Self-Review

- Spec coverage: schema, ownership, idempotency, canonical response, server ordering, Android compatibility, optimistic rollback, per-row in-flight state, long press, pin icon, search order, tests, JAR, and APK are each assigned to a task.
- Placeholder scan: the plan contains no deferred implementation markers.
- Type consistency: `relationshipId`, `pinned`, and nullable epoch-millisecond `pinnedAt` are used consistently across Java JSON, Kotlin models, API, reducer, and UI.
