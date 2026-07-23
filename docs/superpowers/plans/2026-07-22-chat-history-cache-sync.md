# Chat History Cache and Incremental Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make per-friend chat history render from local Core Data immediately, persist network updates, and page older/newer server messages with message-ID cursors while retaining legacy offset compatibility.

**Architecture:** `net-server` exposes latest, before, after, and legacy-offset history modes through the existing `0x53/0x54` frames and removes avatar payloads from history pages. `chat-storage` adds a focused Core Data history store and keeps `SocketManager.chatHistory` as the observable in-memory projection; cache-first hydration, after-cursor catch-up, before-cursor pagination, pushes, and receipts all use one deterministic merge/upsert policy.

**Tech Stack:** Java 8, Java NIO, Spring XML, MyBatis annotations, MySQL, JUnit 4, Swift 5, SwiftUI, Core Data, XCTest, custom TCP frames.

---

## File Map

### net-server

- Create `src/main/java/com/alibaba/server/nio/repository/chat/service/ChatHistoryPage.java`: immutable paging result and cursor metadata.
- Modify `src/main/java/com/alibaba/server/nio/repository/chat/mapper/UserFriendMessageRepository.java`: latest/before/after queries and deletion filtering.
- Modify `src/main/java/com/alibaba/server/nio/repository/chat/service/UserFriendMessageService.java`: cursor-aware history contract while preserving the legacy method.
- Modify `src/main/java/com/alibaba/server/nio/repository/chat/service/impl/UserFriendMessageServiceImpl.java`: mode selection, over-fetching, ordering, and metadata.
- Modify `src/main/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandler.java`: parse cursors, serialize metadata, omit avatars, and stop logging complete history payloads.
- Create `sql/chat_history_cursor_migration_20260722.sql`: idempotent conversation cursor index migration.
- Create `src/test/java/com/alibaba/server/nio/repository/chat/service/impl/UserFriendMessageServiceImplHistoryTest.java`: service paging tests.
- Create `src/test/java/com/alibaba/server/nio/repository/chat/mapper/UserFriendMessageRepositoryHistoryQueryTest.java`: SQL annotation contract tests.
- Create `src/test/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandlerHistoryContractTest.java`: response/logging contract tests.

### chat-storage

- Modify `chat-storage/Services/TransferModels.swift`: optional cursor request fields, paging metadata, and server timestamp decoding.
- Create `chat-storage/Services/Chat/ChatHistoryStore.swift`: Core Data fetch/upsert/delete API and deterministic record mapping.
- Modify `chat-storage/Services/Chat/ChatMessageModels.swift`: merge policy and per-friend cursor state.
- Modify `chat-storage/chat_storage.xcdatamodeld/chat_storage.xcdatamodel/contents`: add `ChatMessageEntity`.
- Modify `chat-storage.xcodeproj/project.pbxproj`: add `ChatHistoryStore.swift` to the application target.
- Modify `chat-storage/SocketManager.swift`: return page metadata, persist pushes/receipts, remove raw history logs, and keep per-friend state.
- Modify `chat-storage/MainChatStorage.swift`: cache-first load, after-cursor catch-up, before-cursor pagination, and local-only clear.
- Modify `chat-storageTests/chat_storageTests.swift`: DTO, merge, Core Data, account isolation, and socket-isolation tests.

## Task 1: Server Cursor Page Model and Service Routing

**Files:**
- Create: `src/main/java/com/alibaba/server/nio/repository/chat/service/ChatHistoryPage.java`
- Modify: `src/main/java/com/alibaba/server/nio/repository/chat/service/UserFriendMessageService.java`
- Modify: `src/main/java/com/alibaba/server/nio/repository/chat/service/impl/UserFriendMessageServiceImpl.java`
- Test: `src/test/java/com/alibaba/server/nio/repository/chat/service/impl/UserFriendMessageServiceImplHistoryTest.java`

- [ ] **Step 1: Write failing service tests**

Add JUnit 4 tests using a dynamic-proxy `UserFriendMessageRepository` injected into `UserFriendMessageServiceImpl`. Cover these exact behaviors:

```java
@Test
public void latestPageOverfetchesAndReturnsAscendingDisplayOrder() {
    ChatHistoryPage page = service.getChatHistoryPage(1, 2, null, null, null, 2);
    assertEquals(Arrays.asList(8L, 9L), ids(page.getMessages()));
    assertTrue(page.isHasMore());
    assertEquals(Long.valueOf(8L), page.getNextBeforeMessageId());
    assertEquals(Long.valueOf(9L), page.getLatestMessageId());
}

@Test
public void beforePageUsesOldestCursorAndTracksOlderAvailability() {
    ChatHistoryPage page = service.getChatHistoryPage(1, 2, 8L, null, null, 2);
    assertEquals(Arrays.asList(5L, 6L), ids(page.getMessages()));
    assertTrue(page.isHasMore());
    assertEquals(Long.valueOf(5L), page.getNextBeforeMessageId());
}

@Test
public void afterPageReturnsAscendingRowsWithoutChangingOlderCursorMeaning() {
    ChatHistoryPage page = service.getChatHistoryPage(1, 2, null, 6L, null, 2);
    assertEquals(Arrays.asList(7L, 8L), ids(page.getMessages()));
    assertTrue(page.isHasMore());
    assertEquals(Long.valueOf(8L), page.getLatestMessageId());
}

@Test(expected = IllegalArgumentException.class)
public void rejectsBeforeAndAfterTogether() {
    service.getChatHistoryPage(1, 2, 8L, 6L, null, 20);
}
```

The proxy returns three rows so the service must trim to the requested limit. The latest and before proxy responses are descending; the after response is ascending.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn -Dtest=UserFriendMessageServiceImplHistoryTest test
```

Expected: compilation failure because `ChatHistoryPage` and `getChatHistoryPage(...)` do not exist.

- [ ] **Step 3: Implement the page model and service routing**

`ChatHistoryPage` must expose:

```java
public final class ChatHistoryPage {
    private final List<UserFriendMessageDO> messages;
    private final boolean hasMore;
    private final Long nextBeforeMessageId;
    private final Long latestMessageId;

    public ChatHistoryPage(List<UserFriendMessageDO> messages, boolean hasMore,
            Long nextBeforeMessageId, Long latestMessageId) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        this.hasMore = hasMore;
        this.nextBeforeMessageId = nextBeforeMessageId;
        this.latestMessageId = latestMessageId;
    }

    public List<UserFriendMessageDO> getMessages() { return messages; }
    public boolean isHasMore() { return hasMore; }
    public Long getNextBeforeMessageId() { return nextBeforeMessageId; }
    public Long getLatestMessageId() { return latestMessageId; }
}
```

Add this service method while retaining `getChatHistory(...)`:

```java
ChatHistoryPage getChatHistoryPage(Integer userId1, Integer userId2,
        Long beforeMessageId, Long afterMessageId, Integer legacyOffset, int limit);
```

Clamp `limit` to `1...100`, request `limit + 1` rows, trim the extra row, reverse latest/before rows into ascending display order, and reject simultaneous before/after cursors.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run `mvn -Dtest=UserFriendMessageServiceImplHistoryTest test`.

Expected: four tests pass with zero failures.

- [ ] **Step 5: Commit only Task 1 paths**

```bash
git commit --only src/main/java/com/alibaba/server/nio/repository/chat/service/ChatHistoryPage.java src/main/java/com/alibaba/server/nio/repository/chat/service/UserFriendMessageService.java src/main/java/com/alibaba/server/nio/repository/chat/service/impl/UserFriendMessageServiceImpl.java src/test/java/com/alibaba/server/nio/repository/chat/service/impl/UserFriendMessageServiceImplHistoryTest.java -m "feat: add cursor-aware chat history service"
```

## Task 2: Server Mapper Queries and Index Migration

**Files:**
- Modify: `src/main/java/com/alibaba/server/nio/repository/chat/mapper/UserFriendMessageRepository.java`
- Create: `src/test/java/com/alibaba/server/nio/repository/chat/mapper/UserFriendMessageRepositoryHistoryQueryTest.java`
- Create: `sql/chat_history_cursor_migration_20260722.sql`

- [ ] **Step 1: Write failing SQL annotation tests**

Reflect the mapper annotations and assert the contracts:

```java
@Test
public void beforeQueryFiltersDeletedRowsAndUsesDescendingIdCursor() throws Exception {
    String sql = selectSql("getChatHistoryBefore", Integer.class, Integer.class, Long.class, int.class);
    assertTrue(sql.contains("del = 'N'"));
    assertTrue(sql.contains("id < #{beforeMessageId}"));
    assertTrue(sql.contains("ORDER BY id DESC"));
    assertTrue(sql.contains("LIMIT #{limit}"));
}

@Test
public void afterQueryFiltersDeletedRowsAndUsesAscendingIdCursor() throws Exception {
    String sql = selectSql("getChatHistoryAfter", Integer.class, Integer.class, Long.class, int.class);
    assertTrue(sql.contains("id > #{afterMessageId}"));
    assertTrue(sql.contains("ORDER BY id ASC"));
}

@Test
public void legacyQueryNowFiltersDeletedRows() throws Exception {
    String sql = selectSql("getChatHistory", Integer.class, Integer.class, int.class, int.class);
    assertTrue(sql.contains("del = 'N'"));
}
```

- [ ] **Step 2: Run the mapper test and verify RED**

Run `mvn -Dtest=UserFriendMessageRepositoryHistoryQueryTest test`.

Expected: reflection failure because cursor mapper methods do not exist.

- [ ] **Step 3: Add latest, before, and after mapper methods**

Use parameterized MyBatis annotations. Every query must include the two-party predicate and `del = 'N'`. Latest and before order by `id DESC`; after orders by `id ASC`. Keep the legacy method, add deletion filtering, and use the existing outer ascending sort for legacy compatibility.

- [ ] **Step 4: Add an idempotent migration**

The migration must query `information_schema.statistics` for `idx_chat_pair_del_id` and conditionally execute:

```sql
ALTER TABLE user_friend_message
    ADD INDEX idx_chat_pair_del_id (sender_id, receiver_id, del, id);
```

Include read-only preflight statements for `SHOW INDEX FROM user_friend_message` and `EXPLAIN` examples for latest, before, and after queries. Do not apply the migration automatically from application startup.

- [ ] **Step 5: Run mapper and service tests**

Run:

```bash
mvn -Dtest=UserFriendMessageRepositoryHistoryQueryTest,UserFriendMessageServiceImplHistoryTest test
```

Expected: all focused tests pass.

- [ ] **Step 6: Commit only Task 2 paths**

```bash
git commit --only src/main/java/com/alibaba/server/nio/repository/chat/mapper/UserFriendMessageRepository.java src/test/java/com/alibaba/server/nio/repository/chat/mapper/UserFriendMessageRepositoryHistoryQueryTest.java sql/chat_history_cursor_migration_20260722.sql -m "feat: add indexed chat history cursor queries"
```

## Task 3: Server History Contract and Payload Slimming

**Files:**
- Modify: `src/main/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandler.java`
- Create: `src/test/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandlerHistoryContractTest.java`

- [ ] **Step 1: Write failing history contract tests**

Add package-visible static helpers in the handler for request validation and response assembly, then test their intended API:

```java
@Test
public void historyResponseContainsCursorMetadataWithoutAvatars() {
    JSONObject data = TextTransmissionHandler.buildChatHistoryResponseData(page);
    assertTrue(data.containsKey("list"));
    assertTrue(data.containsKey("hasMore"));
    assertTrue(data.containsKey("nextBeforeMessageId"));
    assertTrue(data.containsKey("latestMessageId"));
    assertFalse(data.containsKey("avatars"));
}

@Test
public void historyItemKeepsMessageStateAndServerTimestamp() {
    JSONObject item = TextTransmissionHandler.buildChatHistoryItem(message, 0L, 0L);
    assertEquals(message.getId(), item.getLong("id"));
    assertEquals(message.getGmtCreated().getTime(), item.getLongValue("gmtCreated"));
    assertTrue(item.containsKey("retracted"));
}
```

Also add a source-contract assertion that the history path does not call `getAvatarBase64` and `getChatHistory` no longer prints a complete response payload.

- [ ] **Step 2: Run the handler test and verify RED**

Run `mvn -Dtest=TextTransmissionHandlerHistoryContractTest test`.

Expected: helper methods are missing.

- [ ] **Step 3: Implement the cursor-aware handler**

Parse `beforeMessageId`, `afterMessageId`, legacy `offset`, and `limit`. Call `getChatHistoryPage(...)`. Serialize `list`, `hasMore`, `nextBeforeMessageId`, and `latestMessageId`. Preserve all available message fields, add `gmtCreated` epoch milliseconds, omit the `avatars` object, and do not query/read avatar files in this path.

For `CHAT_MSG_HISTORY_RESPONSE`, log only remote address, mode, row count, serialized bytes, and elapsed milliseconds. Other frame logging behavior remains outside this task.

- [ ] **Step 4: Run all server history tests**

Run:

```bash
mvn -Dtest='*History*Test' test
```

Expected: service, mapper, and handler history tests pass.

- [ ] **Step 5: Commit only Task 3 paths**

```bash
git commit --only src/main/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandler.java src/test/java/com/alibaba/server/nio/service/file/handler/TextTransmissionHandlerHistoryContractTest.java -m "perf: slim chat history responses"
```

## Task 4: Client DTO and Deterministic Merge Policy

**Files:**
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/TransferModels.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/Chat/ChatMessageModels.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storageTests/chat_storageTests.swift`

- [ ] **Step 1: Write failing DTO and merge tests**

Add XCTest coverage:

```swift
func testHistoryRequestEncodesOnlySelectedCursor() throws {
    let request = ChatHistoryRequestDto(friendId: 7, beforeMessageId: 99, limit: 20)
    let json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as? [String: Any])
    XCTAssertEqual(json["beforeMessageId"] as? Int64, 99)
    XCTAssertNil(json["afterMessageId"])
}

func testHistoryResponseDecodesCursorMetadataWithoutAvatars() throws {
    let page = try JSONDecoder().decode(ChatHistoryResponseDataDto.self, from: historyPageJSON)
    XCTAssertTrue(page.hasMore)
    XCTAssertEqual(page.nextBeforeMessageId, 80)
    XCTAssertEqual(page.latestMessageId, 100)
}

func testHistoryMergeDeduplicatesAndKeepsAscendingServerIds() {
    let merged = ChatHistoryMergePolicy.merge(existing: [message(2), message(3)], incoming: [message(1), message(2)])
    XCTAssertEqual(merged.compactMap(\.messageId), [1, 2, 3])
}
```

Also test that a local optimistic message with `clientMsgId` is replaced rather than duplicated when the server page contains the same client ID.

- [ ] **Step 2: Run focused client tests and verify RED**

Run:

```bash
xcodebuild -project /Users/hljy/macProjects/chat-storage/chat-storage.xcodeproj -scheme chat-storage -destination 'platform=macOS' test -only-testing:chat-storageTests/chat_storageTests/testHistoryRequestEncodesOnlySelectedCursor -only-testing:chat-storageTests/chat_storageTests/testHistoryMergeDeduplicatesAndKeepsAscendingServerIds
```

Expected: compile failure because cursor initializers and merge policy are missing.

- [ ] **Step 3: Implement backward-compatible DTOs and merge rules**

`ChatHistoryRequestDto` has optional `beforeMessageId`, `afterMessageId`, and `offset`; omit nil values through synthesized `Encodable`. `ChatHistoryResponseDataDto` has defaults for absent metadata so old server responses still decode:

```swift
public struct ChatHistoryResponseDataDto: Codable {
    public let list: [ChatHistoryItemDto]
    public let hasMore: Bool
    public let nextBeforeMessageId: Int64?
    public let latestMessageId: Int64?
    public let avatars: [String: String]?

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        list = try values.decodeIfPresent([ChatHistoryItemDto].self, forKey: .list) ?? []
        hasMore = try values.decodeIfPresent(Bool.self, forKey: .hasMore)
            ?? (list.count >= 20)
        nextBeforeMessageId = try values.decodeIfPresent(Int64.self, forKey: .nextBeforeMessageId)
            ?? list.first?.id
        latestMessageId = try values.decodeIfPresent(Int64.self, forKey: .latestMessageId)
            ?? list.last?.id
        avatars = try values.decodeIfPresent([String: String].self, forKey: .avatars)
    }
}
```

Keep `avatars` decode-only during rollout, but new code must not depend on it. Add `gmtCreated: Int64?` to `ChatHistoryItemDto`. `ChatHistoryMergePolicy` deduplicates server messages by `messageId`, reconciles optimistic messages by `clientMsgId`, and sorts server-backed messages ascending without losing still-pending local messages.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the same `xcodebuild` command. Expected: focused tests pass.

- [ ] **Step 5: Commit only Task 4 paths in chat-storage**

```bash
git -C /Users/hljy/macProjects/chat-storage commit --only chat-storage/Services/TransferModels.swift chat-storage/Services/Chat/ChatMessageModels.swift chat-storageTests/chat_storageTests.swift -m "feat: add chat history cursor models"
```

## Task 5: Client Core Data History Store

**Files:**
- Create: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/Chat/ChatHistoryStore.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/chat_storage.xcdatamodeld/chat_storage.xcdatamodel/contents`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage.xcodeproj/project.pbxproj`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storageTests/chat_storageTests.swift`

- [ ] **Step 1: Write failing in-memory Core Data tests**

Use `PersistenceController(inMemory: true)` and inject its container:

```swift
func testChatHistoryStoreUpsertsByAccountFriendAndMessageId() async throws {
    let store = ChatHistoryStore(container: PersistenceController(inMemory: true).container)
    try await store.upsert(accountId: 1, friendId: 2, items: [historyItem(id: 10, content: "old")])
    try await store.upsert(accountId: 1, friendId: 2, items: [historyItem(id: 10, content: "new")])
    let records = try await store.fetchLatest(accountId: 1, friendId: 2, limit: 20)
    XCTAssertEqual(records.map(\.content), ["new"])
}

func testChatHistoryStoreIsolatesAccounts() async throws {
    let store = ChatHistoryStore(container: PersistenceController(inMemory: true).container)
    try await store.upsert(accountId: 1, friendId: 2, items: [historyItem(id: 10)])
    XCTAssertTrue(try await store.fetchLatest(accountId: 9, friendId: 2, limit: 20).isEmpty)
}

func testDeletingConversationOnlyDeletesSelectedAccountAndFriend() async throws {
    try await store.deleteConversation(accountId: 1, friendId: 2)
    XCTAssertTrue(try await store.fetchLatest(accountId: 1, friendId: 2, limit: 20).isEmpty)
    XCTAssertFalse(try await store.fetchLatest(accountId: 1, friendId: 3, limit: 20).isEmpty)
}
```

- [ ] **Step 2: Run Core Data tests and verify RED**

Run:

```bash
xcodebuild -project /Users/hljy/macProjects/chat-storage/chat-storage.xcodeproj -scheme chat-storage -destination 'platform=macOS' test -only-testing:chat-storageTests/chat_storageTests/testChatHistoryStoreUpsertsByAccountFriendAndMessageId -only-testing:chat-storageTests/chat_storageTests/testChatHistoryStoreIsolatesAccounts -only-testing:chat-storageTests/chat_storageTests/testDeletingConversationOnlyDeletesSelectedAccountAndFriend
```

Expected: compile failure because `ChatHistoryStore` and `ChatMessageEntity` do not exist.

- [ ] **Step 3: Add `ChatMessageEntity`**

Add fields: `recordKey`, `accountId`, `friendId`, `messageId`, `clientMsgId`, `senderId`, `receiverId`, `content`, `msgType`, `status`, `quoteMsgId`, `quoteMsgContent`, `quoteMsgSenderName`, `gmtCreated`, `groupTime`, `msgTimeStr`, `retracted`, and `deleted`. Use class code generation and indexed attributes for `recordKey`, `(accountId, friendId)`, and `messageId`. Do not store avatar Base64 and do not add CloudKit-dependent relationships.

- [ ] **Step 4: Implement `ChatHistoryStore`**

Use a private background context from the injected container. All methods execute through `context.perform`, save only when changed, and throw persistence errors. `recordKey` is `"\(accountId):\(friendId):\(messageId)"`. `fetchLatest` sorts `messageId DESC`, limits the fetch, then reverses to ascending display order. Expose `fetchLatest`, `upsert`, `deleteConversation`, and `bounds`.

- [ ] **Step 5: Add the source file to the app target and run tests**

Run the focused Core Data tests, then:

```bash
xcodebuild -project /Users/hljy/macProjects/chat-storage/chat-storage.xcodeproj -scheme chat-storage -destination 'platform=macOS' build
```

Expected: tests pass and the application target builds.

- [ ] **Step 6: Commit only Task 5 paths**

```bash
git -C /Users/hljy/macProjects/chat-storage commit --only chat-storage/Services/Chat/ChatHistoryStore.swift chat-storage/chat_storage.xcdatamodeld/chat_storage.xcdatamodel/contents chat-storage.xcodeproj/project.pbxproj chat-storageTests/chat_storageTests.swift -m "feat: persist chat history locally"
```

## Task 6: Cache-First UI, Incremental Sync, Push Persistence, and Local Clear

**Files:**
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/SocketManager.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/MainChatStorage.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/Chat/ChatMessageModels.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storageTests/chat_storageTests.swift`

- [ ] **Step 1: Write failing integration-policy tests**

Add tests for per-friend state and source boundaries:

```swift
func testHistoryStateKeepsIndependentOlderAvailabilityPerFriend() {
    var states: [Int64: ChatHistoryCursorState] = [:]
    states[2] = .init(oldestMessageId: 10, latestMessageId: 20, hasOlder: false, isHydrated: true)
    states[3] = .init(oldestMessageId: 30, latestMessageId: 40, hasOlder: true, isHydrated: true)
    XCTAssertFalse(states[2]!.hasOlder)
    XCTAssertTrue(states[3]!.hasOlder)
}

func testInitialHistorySourceDoesNotClearExistingConversation() throws {
    let source = try sourceFileContents("chat-storage/MainChatStorage.swift")
    let method = try sourceSlice(source, from: "private func loadInitialHistory() async", to: "private func loadMoreHistory() async")
    XCTAssertFalse(method.contains("history[friend.id] = []"))
    XCTAssertTrue(method.contains("ChatHistoryStore.shared.fetchLatest"))
}

func testHistoryRawPayloadLoggingIsRemoved() throws {
    let source = try sourceFileContents("chat-storage/SocketManager.swift")
    XCTAssertFalse(source.contains("[getChatHistory] Raw JSON"))
}

func testConversationClearDoesNotUseSocketLifecycle() throws {
    let source = try sourceFileContents("chat-storage/MainChatStorage.swift")
    let clearMethod = try sourceSlice(source, from: "private func clearLocalHistory", to: "private func loadInitialHistory")
    XCTAssertTrue(clearMethod.contains("deleteConversation"))
    XCTAssertFalse(clearMethod.contains("disconnect"))
    XCTAssertFalse(clearMethod.contains("receiveBuffer"))
}
```

- [ ] **Step 2: Run the four tests and verify RED**

Run:

```bash
xcodebuild -project /Users/hljy/macProjects/chat-storage/chat-storage.xcodeproj -scheme chat-storage -destination 'platform=macOS' test -only-testing:chat-storageTests/chat_storageTests/testHistoryStateKeepsIndependentOlderAvailabilityPerFriend -only-testing:chat-storageTests/chat_storageTests/testInitialHistorySourceDoesNotClearExistingConversation -only-testing:chat-storageTests/chat_storageTests/testHistoryRawPayloadLoggingIsRemoved -only-testing:chat-storageTests/chat_storageTests/testConversationClearDoesNotUseSocketLifecycle
```

Expected: missing cursor state/store calls and current clear/reload source violate assertions.

- [ ] **Step 3: Update `SocketManager.getChatHistory`**

Change it to accept optional before/after cursors and return `ChatHistoryResponseDataDto`. Remove raw JSON printing and avatar injection. Maintain `@Published var chatHistoryStates: [Int64: ChatHistoryCursorState]` so view recreation does not reset exhaustion state.

- [ ] **Step 4: Implement cache-first initial loading**

`loadInitialHistory()` must:

1. retain an existing in-memory list;
2. otherwise fetch the latest local page and render it;
3. request latest server data when local is empty;
4. request `afterMessageId` repeatedly when local data exists until the response has no more newer rows;
5. upsert every page before publishing the merged array;
6. keep unread clearing as a separate `0x55` operation after visible history is available.

Network failure sets retry/error state but never empties local history.

- [ ] **Step 5: Implement before-cursor older loading**

Use `chatHistoryStates[friend.id].oldestMessageId` as `beforeMessageId`. Persist, merge, prepend through the deterministic merge policy, update `hasOlder` only from the before response, and preserve `topMessageIdBeforeLoad`. Remove `currentOffset` from the new path.

- [ ] **Step 6: Persist pushes and successful receipts**

When a `0x51` push is accepted, upsert it for `currentUserId` and its sender friend before publishing the merged array. When a send receipt supplies a positive server message ID, upsert the reconciled message for the receiver friend. Persistence failure is logged without discarding the in-memory message.

- [ ] **Step 7: Implement local-only conversation clear**

Change the menu command to call `clearLocalHistory()`, which deletes only `ChatHistoryStore` rows for the current account/friend, clears only `chatHistory[friend.id]`, and resets only that friend's cursor state. It must not call `CacheCleanupService`, `disconnect`, stream cleanup, receive-buffer cleanup, or handler cleanup.

- [ ] **Step 8: Run focused and full client tests**

Run the focused tests first, then:

```bash
xcodebuild -project /Users/hljy/macProjects/chat-storage/chat-storage.xcodeproj -scheme chat-storage -destination 'platform=macOS' test
```

Expected: all `chat-storageTests` pass with zero failures.

- [ ] **Step 9: Commit only Task 6 paths**

```bash
git -C /Users/hljy/macProjects/chat-storage commit --only chat-storage/SocketManager.swift chat-storage/MainChatStorage.swift chat-storage/Services/Chat/ChatMessageModels.swift chat-storageTests/chat_storageTests.swift -m "feat: load chat history from local cache first"
```

## Task 7: Cross-Repository Verification and Runtime Smoke Test

**Files:**
- Verify all modified files in both repositories.

- [ ] **Step 1: Run the complete server test suite**

```bash
mvn test
```

Expected: build success with zero test failures.

- [ ] **Step 2: Build the server fat JAR**

```bash
mvn -DskipTests package
```

Expected: `target/net-server-1.0-SNAPSHOT.jar` is produced successfully.

- [ ] **Step 3: Run the complete client test suite and build**

```bash
xcodebuild -project /Users/hljy/macProjects/chat-storage/chat-storage.xcodeproj -scheme chat-storage -destination 'platform=macOS' test
xcodebuild -project /Users/hljy/macProjects/chat-storage/chat-storage.xcodeproj -scheme chat-storage -destination 'platform=macOS' build
```

Expected: test and build commands exit zero.

- [ ] **Step 4: Inspect migration and repository state**

Run `git diff --check` and `git status --short` separately in each repository. Confirm only intended task paths changed; do not clean or revert unrelated user changes.

- [ ] **Step 5: Perform manual smoke verification when credentials are available**

Verify these observable behaviors against a restarted server and freshly built client:

1. open a friend, switch away, and switch back with immediate cached display;
2. restart the client and see the latest local page before network completion;
3. receive missed messages through `afterMessageId` without duplicates;
4. scroll upward through multiple pages without offset requests;
5. receive a realtime message while another friend is open, then open that friend and see it persisted;
6. clear one conversation locally and confirm port `10086` remains connected and other conversations remain cached;
7. verify history responses do not contain `avatars` and logs do not contain full history JSON.

- [ ] **Step 6: Report deployment boundary**

Report the generated migration path separately from code completion. Do not claim the live database index exists unless `SHOW INDEX FROM user_friend_message` was run after applying the migration. Do not claim end-to-end runtime success unless the manual smoke test was performed with an authenticated client.
