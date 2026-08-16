# Local Session Secret Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow a single-node local server to start securely without manual session-secret configuration while preserving stable session-token validation across restarts.

**Architecture:** Environment and explicit configuration remain authoritative. When both are absent, a focused local secret store creates a 32-byte `SecureRandom` value once under the current user's home directory and reuses it on later starts.

**Tech Stack:** Java 8, `java.nio.file`, `SecureRandom`, JUnit 4, Maven

---

### Task 1: Persistent local secret store

**Files:**
- Create: `src/test/java/com/alibaba/server/nio/service/file/security/LocalSessionSecretStoreTest.java`
- Create: `src/main/java/com/alibaba/server/nio/service/file/security/LocalSessionSecretStore.java`

- [ ] Write tests proving a missing file creates a nonblank secret and a second read returns the same value.
- [ ] Write a test proving an existing secret is trimmed and reused rather than replaced.
- [ ] Run `mvn -q -Dtest=LocalSessionSecretStoreTest test` with Java 8 and confirm compilation fails because the store does not exist.
- [ ] Implement `loadOrCreate(Path)` with `SecureRandom`, `CREATE_NEW`, UTF-8 storage, concurrent-creation recovery, and best-effort owner-only permissions.
- [ ] Re-run the focused test and confirm it passes.

### Task 2: Factory fallback

**Files:**
- Modify: `src/test/java/com/alibaba/server/nio/service/file/security/SessionTokenFactoryTest.java`
- Modify: `src/main/java/com/alibaba/server/nio/service/file/security/SessionTokenFactory.java`
- Modify: `src/main/resources/server.properties`

- [ ] Replace the missing-secret rejection test with a test that injects a local-secret supplier and validates a generated token.
- [ ] Run the focused factory test and confirm it fails because the factory does not accept a fallback supplier.
- [ ] Add a package-private factory overload using a `Supplier<String>` and use `LocalSessionSecretStore::loadOrCreateDefault` from production.
- [ ] Keep environment and explicit property precedence unchanged.
- [ ] Update the property comment to document automatic local generation.
- [ ] Re-run factory, service, and startup bootstrap tests.

### Task 3: Verification and package

**Files:**
- Produce: `target/net-server-1.0-SNAPSHOT.jar`

- [ ] Run the full Maven test suite with Java 8.
- [ ] Run `git diff --check`.
- [ ] Package the jar with Java 8 and verify the artifact timestamp and checksum.
- [ ] Do not start the backend or perform Android/manual login testing.
- [ ] Do not create a Git commit unless requested.
