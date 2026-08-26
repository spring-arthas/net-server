# Windows Deployment Storage and IP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Windows net-server use its configured Windows storage root for downloads and Range pulls, while allowing the service IP passed to `java -jar` to control generated media URLs without changing the existing macOS or mobile protocol.

**Architecture:** Add one shared storage-root resolver based on `os.name` and the existing Windows/macOS configuration keys. Route HTTP media, normal downloads, Range pulls, and file-service root resolution through that resolver. Parse a positional IP or `--server-ip` argument in `NetServer`, expose it as a runtime system property, and let media/TLS endpoint resolution use it after environment variables but before static configuration.

**Tech Stack:** Java 8, Maven, JUnit 4, Spring XML, Java NIO, `com.sun.net.httpserver.HttpServer`.

---

### Task 1: Add failing tests for Windows root selection and command-line IP parsing

**Files:**
- Create: `src/test/java/com/alibaba/server/nio/service/file/StorageRootResolverTest.java`
- Create: `src/test/java/com/alibaba/server/NetServerCommandLineOptionsTest.java`

- [x] **Step 1: Write tests for Windows and macOS root selection.**

  Assert that `Windows 11` selects `NIO.FILE.BASE.PATH.WINDOWS`, while `Mac OS X` selects `NIO.FILE.BASE.PATH.LINUX.MAC`.

- [x] **Step 2: Write tests for positional and named IP arguments.**

  Assert that `192.168.1.20` and `--server-ip=192.168.1.20` return the IP, and missing/blank arguments return no override.

- [x] **Step 3: Run the focused tests and confirm they fail because the new APIs do not exist.**

  Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home mvn -Dtest=StorageRootResolverTest,NetServerCommandLineOptionsTest test`

  Expected: compilation failure for the missing resolver and command-line method.

### Task 2: Implement shared Windows/macOS storage-root resolution

**Files:**
- Create: `src/main/java/com/alibaba/server/nio/service/file/StorageRootResolver.java`
- Modify: `src/main/java/com/alibaba/server/nio/media/MediaServiceFactory.java:132-138`
- Modify: `src/main/java/com/alibaba/server/nio/service/file/handler/FileDownloadHandler.java:211-213`
- Modify: `src/main/java/com/alibaba/server/nio/service/file/handler/FileRangePullHandler.java:173-175`
- Modify: `src/main/java/com/alibaba/server/nio/repository/file/service/impl/FileServiceImpl.java:1401-1405`

- [x] **Step 1: Implement the resolver using the existing configuration keys.**

  Select the Windows key when `os.name` contains `win`, otherwise select the existing Linux/macOS key. Preserve `.` as the existing fallback.

- [x] **Step 2: Replace direct storage-root reads in media, normal download, Range pull, and file service code.**

  Do not change database `file_path` values or client protocol fields.

- [x] **Step 3: Run the focused resolver and existing media/path tests.**

  Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home mvn -Dtest=StorageRootResolverTest,FileDownloadPathResolverTest,MediaServiceFactoryEndpointTest,MediaAccessServiceEndpointTest,MediaStreamHandlerIntegrationTest test`

  Expected: all tests pass.

### Task 3: Implement command-line service IP override

**Files:**
- Modify: `src/main/java/com/alibaba/server/NetServer.java:29-39`
- Modify: `src/main/java/com/alibaba/server/nio/media/MediaServiceFactory.java:83-104`
- Modify: `src/main/java/com/alibaba/server/nio/tls/TlsGatewayConfig.java:209-222`

- [x] **Step 1: Add a package-visible argument parser in `NetServer`.**

  Support both `java -jar net-server.jar 192.168.1.20` and `java -jar net-server.jar --server-ip=192.168.1.20`. Set system property `NET_SERVER_PUBLIC_IP` before server startup when present.

- [x] **Step 2: Make media public-host resolution use environment first, command-line system property second, then static configuration.**

  This preserves existing `NET_SERVER_PUBLIC_IP` behavior and keeps macOS `MEDIA.STREAM.PUBLIC.HOST` behavior unchanged when no override is supplied.

- [x] **Step 3: Apply the same precedence to TLS public binding when TLS is enabled.**

  This keeps the command-line IP consistent across HTTP media and future TLS deployments.

- [x] **Step 4: Run command-line, media endpoint, and TLS configuration tests.**

  Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home mvn -Dtest=NetServerCommandLineOptionsTest,MediaServiceFactoryEndpointTest,TlsGatewayConfigTest test`

### Task 4: Full verification and Windows deployment checklist

**Files:**
- Verify only; no additional source changes unless a test exposes a regression.

- [x] **Step 1: Run the complete Java test suite.**

  Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home mvn test`

- [x] **Step 2: Run syntax and diff checks.**

  Run: `bash -n scripts/run-net-server-zulu8.sh && git diff --check`

- [x] **Step 3: Review the final diff for scope.**

  Confirm no database data, mobile client source, macOS path configuration, or unrelated user changes were modified.
