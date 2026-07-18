# Upload Directory Self-Healing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow file uploads to repair a missing physical directory hierarchy when, and only when, the authenticated user's database directory chain is complete and valid.

**Architecture:** Add an upload-specific `FileService` operation that validates the database chain from target directory to user root, reconstructs the canonical path from the configured storage root and authenticated username, creates missing directories under a per-directory lock, and repairs stale directory `file_path` values. Keep the existing read-only directory validation and all upload frame formats unchanged.

**Tech Stack:** Java 8, Spring 5, MyBatis, JUnit 4, `java.nio.file`.

---

### Task 1: Specify directory recovery behavior

**Files:**
- Create: `src/test/java/com/alibaba/server/nio/repository/file/service/impl/FileServiceUploadDirectoryTest.java`

1. Add failing tests for missing parent/leaf creation and stale database path repair.
2. Add failing tests for broken chains, wrong ownership, invalid status, file placeholders, symlinks, and concurrent calls.
3. Run the focused test with Zulu JDK 8 and confirm it fails because the upload-specific API is absent.

### Task 2: Implement the upload-specific service operation

**Files:**
- Modify: `src/main/java/com/alibaba/server/nio/repository/file/service/FileService.java`
- Modify: `src/main/java/com/alibaba/server/nio/repository/file/service/impl/FileServiceImpl.java`

1. Add `ensureUploadDirectory(Long dirId, Integer userId, String userName)`.
2. Validate every database node, ownership, root identity, parent continuity, and cycles.
3. Build normalized paths from the configured storage root, rejecting unsafe names and path escape.
4. Reject symbolic links and non-directory path occupants, then create missing directories with `Files.createDirectories`.
5. Repair stale database `file_path` values after successful filesystem recovery.
6. Serialize recovery by `dirId` with a reference-counted lock that is removed after the final caller exits.
7. Run the focused tests until green.

### Task 3: Connect upload handling without protocol changes

**Files:**
- Modify: `src/main/java/com/alibaba/server/nio/service/file/handler/FileUploadHandler.java`
- Modify: `src/test/java/com/alibaba/server/nio/media/MediaStreamHandlerIntegrationTest.java`

1. Replace both upload-time read-only validations with the authenticated recovery operation.
2. Preserve current ACK/RESUME_ACK frame types and JSON fields; return validation failures through the existing error response.
3. Extend the existing test fake for the new service interface method.

### Task 4: Verify compatibility

1. Run the focused directory recovery test under Zulu JDK 8.
2. Run all Maven tests under Zulu JDK 8.
3. Run `mvn -DskipTests package` under Zulu JDK 8.
4. Inspect the final diff to confirm no client protocol, frame type, or unrelated project file changed.
