# Text Frame Delivery Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure large responses on the 10086 text connection are fully written by the server and fully drained by the macOS client.

**Architecture:** Preserve the existing framed TCP protocol and pending-write queue. The server selects the owning Selector from the connection handler type before registering `OP_WRITE`; the client drains all currently available stream bytes into the existing incremental frame buffer.

**Tech Stack:** Java 8 NIO, JUnit 4, Swift/Foundation InputStream, XCTest

---

### Task 1: Server text Selector regression

**Files:**
- Create: `src/test/java/com/alibaba/server/nio/handler/event/concret/WriteQueueHelperTest.java`
- Modify: `src/main/java/com/alibaba/server/nio/handler/event/concret/WriteQueueHelper.java`

- [ ] Write a failing test that registers a nonblocking socket on the text Selector, forces a partial write, and asserts that `OP_WRITE` is enabled on the text SelectionKey.
- [ ] Run `mvn -q -Dtest=WriteQueueHelperTest test` and confirm the assertion fails with the current file-Selector-only implementation.
- [ ] Select the text Selector for `handlerType=TEXT` and retain the file Selector for file-transfer handlers.
- [ ] Re-run `mvn -q -Dtest=WriteQueueHelperTest test` and confirm it passes.

### Task 2: Client stream draining regression

**Files:**
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storageTests/chat_storageTests.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/SocketManager.swift`

- [ ] Write a failing XCTest using an `InputStream` containing a frame larger than 4096 bytes and assert that the read helper returns the complete byte sequence.
- [ ] Run the focused XCTest and confirm the current single-read implementation fails or the required helper is absent.
- [ ] Add the minimal loop that reads until no bytes are currently available, preserving EOF and error handling.
- [ ] Re-run the focused XCTest and confirm it passes.

### Task 3: Cross-repository verification

**Files:**
- Verify only; no additional production changes.

- [ ] Run the server targeted tests and full Maven test suite.
- [ ] Build the server package with the configured Java 8 runtime.
- [ ] Run the client focused XCTest and full test target.
- [ ] Build the macOS application.
- [ ] Review diffs to confirm no unrelated user changes were overwritten.
