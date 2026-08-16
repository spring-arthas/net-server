# Login Session Contract Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make missing session-token configuration fail during server startup and make Android reject incomplete login responses before publishing authenticated state.

**Architecture:** Keep the existing signed session-token protocol and background connection manager. Add one eager server bootstrap call and one atomic Android login-response validation before persistence/state publication.

**Tech Stack:** Java 8, Maven, JUnit 4, Kotlin, coroutines, Gradle, Truth

---

### Task 1: Server startup validation

**Files:**
- Create: `src/test/java/com/alibaba/server/nio/core/server/BasicServerSessionTokenBootstrapContractTest.java`
- Modify: `src/main/java/com/alibaba/server/nio/core/server/BasicServer.java`

- [ ] Add a contract test that asserts `BasicServer.startupBasicServer()` initializes `SessionTokenFactory` after configuration loading and before enum/core startup.
- [ ] Run `mvn -q -Dtest=BasicServerSessionTokenBootstrapContractTest test` with Java 8 and confirm it fails because the bootstrap call is absent.
- [ ] Import `SessionTokenFactory` and invoke `SessionTokenFactory.getInstance()` immediately after `loadConfigProperties()`.
- [ ] Re-run the focused Java test and the existing session-token tests.

### Task 2: Android atomic login validation

**Files:**
- Modify: `/Users/hljy/androidProjects/chat-storage-android/app/src/test/kotlin/com/alibaba/chatstorage/android/data/auth/AuthRepositoryTest.kt`
- Modify: `/Users/hljy/androidProjects/chat-storage-android/app/src/main/kotlin/com/alibaba/chatstorage/android/data/auth/AuthRepository.kt`

- [ ] Add a test using a successful `USER_RESPONSE` whose user has a blank `sessionToken`.
- [ ] Assert login throws `AuthException` with `SESSION_TOKEN_MISSING`, remains `LoggedOut`, clears persistence, and closes the authenticated control connection.
- [ ] Run the focused Android test and confirm it fails because current code publishes the user.
- [ ] Add the minimal pre-persistence validation and connection cleanup.
- [ ] Re-run `AuthRepositoryTest` and `ControlConnectionManagerTest`.

### Task 3: Build artifacts

**Files:**
- Verify: `target/net-server-1.0-SNAPSHOT.jar`
- Produce: `/Users/hljy/Downloads/chat-storage-android-debug.apk`

- [ ] Run the selected backend regression suite and package with Java 8.
- [ ] Run the Android protocol/auth/connection tests and `assembleDebug`.
- [ ] Copy the generated APK to the Downloads directory with the stable requested filename.
- [ ] Do not run an emulator, install the APK, or perform manual login testing.
- [ ] Do not create Git commits unless the user requests them.
