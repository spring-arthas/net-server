# Embedded TLS Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Remove the standalone HAProxy process and let one Java 8 net-server process terminate TLS on ports 10086, 10087, 10088, and 10188 without changing any client protocol.

**Architecture:** Add a focused com.alibaba.server.nio.tls package. JDK 8 SSLServerSocket listeners decrypt the existing TLS connections and relay bytes unchanged to the existing loopback NIO/HTTP backends. NioServerContext owns the gateway lifecycle so certificate or port failures terminate startup.

**Tech Stack:** Java 8, JSSE, PKCS12, blocking relay sockets isolated from the existing Java NIO pipeline, JUnit 4, Maven.

**Working-tree rule:** The repository already contains user WIP. Do not stage, commit, reset, checkout, or revert. End tasks with tests and git diff --check.

---

## File map

Create:

- src/main/java/com/alibaba/server/nio/tls/TlsGatewayConfigurationException.java
- src/main/java/com/alibaba/server/nio/tls/TlsGatewayStartupException.java
- src/main/java/com/alibaba/server/nio/tls/TlsGatewayEndpoint.java
- src/main/java/com/alibaba/server/nio/tls/TlsGatewayConfig.java
- src/main/java/com/alibaba/server/nio/tls/TlsContextFactory.java
- src/main/java/com/alibaba/server/nio/tls/TlsSocketRelay.java
- src/main/java/com/alibaba/server/nio/tls/TlsBackendReadinessProbe.java
- src/main/java/com/alibaba/server/nio/tls/EmbeddedTlsGateway.java
- src/test/java/com/alibaba/server/nio/tls/TlsTestKeyStore.java
- src/test/java/com/alibaba/server/nio/tls/TlsGatewayConfigTest.java
- src/test/java/com/alibaba/server/nio/tls/TlsContextFactoryTest.java
- src/test/java/com/alibaba/server/nio/tls/TlsSocketRelayTest.java
- src/test/java/com/alibaba/server/nio/tls/TlsBackendReadinessProbeTest.java
- src/test/java/com/alibaba/server/nio/tls/EmbeddedTlsGatewayIntegrationTest.java
- deploy/tls/README.md

Modify:

- src/main/java/com/alibaba/server/common/BasicConstant.java
- src/main/java/com/alibaba/server/nio/core/server/NioServerContext.java
- src/main/java/com/alibaba/server/NetServer.java
- src/main/java/com/alibaba/server/nio/acceptor/AbstractAcceptor.java
- src/main/resources/server.properties
- scripts/run-net-server-zulu8.sh
- deploy/tls/generate-local-certs.sh
- src/test/java/com/alibaba/server/nio/media/TlsDeploymentConfigurationTest.java
- src/test/java/com/alibaba/server/scripts/NetServerStartScriptTest.java

Delete:

- deploy/haproxy/haproxy.cfg
- deploy/haproxy/README.md

Do not modify handlers, parsers, database code, or any iOS/macOS/Android source.

---

### Task 1: Validated TLS Gateway configuration

**Files:** BasicConstant.java, TlsGatewayConfigurationException.java, TlsGatewayEndpoint.java, TlsGatewayConfig.java, TlsGatewayConfigTest.java

- [ ] **Step 1: Write failing tests**

Cover:

1. Four mappings: control 10086, upload 10087, download 10088, media 10188.
2. Public bind address comes from NET_SERVER_PUBLIC_IP.
3. PKCS12 path comes from NET_SERVER_TLS_KEYSTORE.
4. Missing IP, missing file, invalid/duplicate ports, non-loopback backends, invalid timeouts, invalid connection limits, and invalid buffer size fail.
5. Password is never present in exception messages or toString.

The production test setup must contain:

~~~java
Map<String, Object> values = new HashMap<>();
values.put(BasicConstant.NIO_BIND_IP, "127.0.0.1");
values.put(BasicConstant.NIO_MEDIA_STREAM_BIND_IP, "127.0.0.1");
values.put(BasicConstant.NIO_TEXT_PORT, "10086");
values.put(BasicConstant.NIO_FILE_UPLOAD_PORT, "10087");
values.put(BasicConstant.NIO_FILE_DOWNLOAD_PORT, "10088");
values.put(BasicConstant.NIO_MEDIA_STREAM_PORT, "10188");
values.put(BasicConstant.TLS_GATEWAY_ENABLED, "true");
values.put(BasicConstant.TLS_GATEWAY_HANDSHAKE_TIMEOUT_MILLIS, "10000");
values.put(BasicConstant.TLS_GATEWAY_CONNECT_TIMEOUT_MILLIS, "10000");
values.put(BasicConstant.TLS_GATEWAY_IDLE_TIMEOUT_MILLIS, "300000");
values.put(BasicConstant.TLS_GATEWAY_MAX_CONNECTIONS, "512");
values.put(BasicConstant.TLS_GATEWAY_BUFFER_SIZE, "65536");
~~~

- [ ] **Step 2: Verify RED**

~~~bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home \
  mvn -Dtest=TlsGatewayConfigTest test
~~~

Expected: compilation failure because the TLS configuration types do not exist.

- [ ] **Step 3: Add constants and implementation**

Add these BasicConstant values:

~~~java
TLS_GATEWAY_ENABLED = "TLS.GATEWAY.ENABLED",
TLS_GATEWAY_HANDSHAKE_TIMEOUT_MILLIS = "TLS.GATEWAY.HANDSHAKE.TIMEOUT.MILLIS",
TLS_GATEWAY_CONNECT_TIMEOUT_MILLIS = "TLS.GATEWAY.CONNECT.TIMEOUT.MILLIS",
TLS_GATEWAY_IDLE_TIMEOUT_MILLIS = "TLS.GATEWAY.IDLE.TIMEOUT.MILLIS",
TLS_GATEWAY_MAX_CONNECTIONS = "TLS.GATEWAY.MAX.CONNECTIONS",
TLS_GATEWAY_BUFFER_SIZE = "TLS.GATEWAY.BUFFER.SIZE",
~~~

Use this public factory:

~~~java
public static TlsGatewayConfig load(
        Map<String, Object> values,
        Function<String, String> environment)
~~~

Rules:

- Disabled config does not require TLS environment values.
- Enabled config requires NET_SERVER_PUBLIC_IP and a regular NET_SERVER_TLS_KEYSTORE file.
- NET_SERVER_TLS_KEYSTORE_PASSWORD defaults to an empty char array.
- NIO.BIND.IP and NIO.MEDIA.STREAM.BIND.IP must both resolve to loopback.
- Port range is 1..65535 and all four ports are unique.
- Timeouts are positive.
- Max connections is 1..512.
- Buffer size is 4096..1048576.
- Endpoint objects are immutable and expose name, publicPort, backendHost, backendPort.
- copyKeyStorePassword returns a clone.

- [ ] **Step 4: Verify GREEN**

Expected: all TlsGatewayConfigTest methods pass.

---

### Task 2: PKCS12 and SSLContext

**Files:** TlsGatewayStartupException.java, TlsContextFactory.java, TlsTestKeyStore.java, TlsContextFactoryTest.java

- [ ] **Step 1: Add deterministic test certificate helper**

TlsTestKeyStore must invoke the current JDK keytool:

~~~java
List<String> command = Arrays.asList(
        Paths.get(System.getProperty("java.home"), "bin", "keytool").toString(),
        "-genkeypair",
        "-alias", "net-server",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-storetype", "PKCS12",
        "-keystore", keyStore.toString(),
        "-storepass", password,
        "-keypass", password,
        "-dname", "CN=127.0.0.1",
        "-validity", "365",
        "-ext", "SAN=ip:127.0.0.1,dns:localhost",
        "-noprompt");
~~~

It must also build a client SSLContext whose trust store contains the generated server certificate.

- [ ] **Step 2: Write failing tests**

~~~java
@Test
public void loadsPrivateKeyEntry() throws Exception {
    Path keyStore = TlsTestKeyStore.create(temporaryFolder, "changeit");
    SSLContext context = new TlsContextFactory().create(
            keyStore, "changeit".toCharArray());
    assertEquals("TLSv1.2", context.getProtocol());
}

@Test
public void wrongPasswordDoesNotLeak() throws Exception {
    Path keyStore = TlsTestKeyStore.create(temporaryFolder, "changeit");
    try {
        new TlsContextFactory().create(keyStore, "private-value".toCharArray());
        fail("expected startup exception");
    } catch (TlsGatewayStartupException exception) {
        assertFalse(exception.getMessage().contains("private-value"));
    }
}
~~~

- [ ] **Step 3: Verify RED**

~~~bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home \
  mvn -Dtest=TlsContextFactoryTest test
~~~

- [ ] **Step 4: Implement the factory**

~~~java
public SSLContext create(Path keyStorePath, char[] password) {
    char[] privatePassword = password == null ? new char[0] : password.clone();
    try (InputStream input = Files.newInputStream(keyStorePath)) {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(input, privatePassword);
        requirePrivateKeyEntry(keyStore);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, privatePassword);
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(keyManagers.getKeyManagers(), null, new SecureRandom());
        return context;
    } catch (GeneralSecurityException | IOException exception) {
        throw new TlsGatewayStartupException("加载 TLS PKCS12 失败", exception);
    } finally {
        Arrays.fill(privatePassword, '\0');
    }
}
~~~

- [ ] **Step 5: Verify GREEN**

Expected: success and wrong-password tests pass.

---

### Task 3: Byte-preserving relay

**Files:** TlsSocketRelay.java, TlsSocketRelayTest.java

- [ ] **Step 1: Write failing copy tests**

~~~java
@Test
public void copiesLargeFragmentedPayloadExactly() throws Exception {
    byte[] payload = new byte[196_733];
    new Random(47L).nextBytes(payload);
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    long copied = TlsSocketRelay.copy(
            new ByteArrayInputStream(payload), output, 65_536);

    assertEquals(payload.length, copied);
    assertArrayEquals(payload, output.toByteArray());
}
~~~

Also test that buffer size zero throws IllegalArgumentException.

- [ ] **Step 2: Verify RED**

Run mvn -Dtest=TlsSocketRelayTest test with Zulu 8.

- [ ] **Step 3: Implement exact copy and atomic close**

~~~java
static long copy(InputStream input, OutputStream output, int bufferSize)
        throws IOException {
    if (bufferSize <= 0) {
        throw new IllegalArgumentException("bufferSize 必须大于 0");
    }
    byte[] buffer = new byte[bufferSize];
    long copied = 0;
    int length;
    while ((length = input.read(buffer)) >= 0) {
        if (length == 0) {
            continue;
        }
        output.write(buffer, 0, length);
        copied += length;
    }
    return copied;
}
~~~

The connection method must set handshake timeout, call startHandshake, connect the loopback backend with the ten-second timeout, set keepAlive/tcpNoDelay, use separate forward/reverse copy tasks, and close both sockets once through AtomicBoolean. It must never inspect or modify FA CE bytes.

- [ ] **Step 4: Verify GREEN**

Expected: byte-integrity tests pass.

---

### Task 4: Backend readiness without fixed startup sleep

**Files:** TlsBackendReadinessProbe.java, TlsBackendReadinessProbeTest.java

- [ ] **Step 1: Write failing tests**

Use two live loopback ServerSockets and assert await returns. Then select a closed ephemeral port and assert timeout includes endpoint name and port.

~~~java
TlsBackendReadinessProbe.await(endpoints, 1_000);
~~~

- [ ] **Step 2: Verify RED**

Run mvn -Dtest=TlsBackendReadinessProbeTest test.

- [ ] **Step 3: Implement condition polling**

Use System.nanoTime deadline, 100 ms maximum Socket.connect attempt, and 50 ms polling between failed rounds. Remove ready endpoints immediately. Throw TlsGatewayStartupException when the deadline expires.

- [ ] **Step 4: Verify GREEN**

Expected: both tests pass in under two seconds.

---

### Task 5: Four embedded TLS listeners

**Files:** EmbeddedTlsGateway.java, EmbeddedTlsGatewayIntegrationTest.java

- [ ] **Step 1: Write failing integration tests**

Generate a test PKCS12, start a plain echo backend, prepare a gateway endpoint on public port zero, then connect through a trusted SSLSocket:

~~~java
gateway.prepare();
gateway.start();
int publicPort = gateway.getBoundPort("control");
byte[] request = new byte[]{(byte) 0xFA, (byte) 0xCE, 0x01, 0x02};

try (SSLSocket client = (SSLSocket) clientContext.getSocketFactory()
        .createSocket("127.0.0.1", publicPort)) {
    client.setEnabledProtocols(new String[]{"TLSv1.2"});
    client.startHandshake();
    client.getOutputStream().write(request);
    client.getOutputStream().flush();
    byte[] response = new byte[request.length];
    new DataInputStream(client.getInputStream()).readFully(response);
    assertArrayEquals(request, response);
}
~~~

Add tests for four mappings, rollback when a later bind fails, connection-limit rejection, stop closing active sockets, and repeated start/stop without leaked threads.

- [ ] **Step 2: Verify RED**

Run mvn -Dtest=EmbeddedTlsGatewayIntegrationTest test.

- [ ] **Step 3: Implement lifecycle**

prepare creates and binds every SSLServerSocket before accepting:

~~~java
SSLServerSocket listener =
        (SSLServerSocket) socketFactory.createServerSocket();
listener.setReuseAddress(true);
listener.setNeedClientAuth(false);
listener.setEnabledProtocols(new String[]{"TLSv1.2"});
listener.bind(new InetSocketAddress(
        bindAddress, endpoint.getPublicPort()), 128);
~~~

Use one named Acceptor thread per endpoint, SynchronousQueue ThreadPoolExecutors bounded by maxConnections, concurrent active-pair tracking, idempotent prepare/start/stop, rate-limited rejection WARN logs, and full gateway stop for an unexpected listener failure.

- [ ] **Step 4: Verify GREEN three times**

~~~bash
for run_number in 1 2 3; do
  JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home \
    mvn -Dtest=EmbeddedTlsGatewayIntegrationTest test || exit 1
done
~~~

---

### Task 6: Integrate lifecycle and remove HAProxy files

**Files:** NioServerContext.java, NetServer.java, AbstractAcceptor.java, server.properties, TlsDeploymentConfigurationTest.java, deploy/haproxy files

- [ ] **Step 1: Replace HAProxy tests with failing embedded tests**

Assert loopback-only backends, embedded gateway properties, strict certificate profile, and absence of deploy/haproxy/haproxy.cfg.

- [ ] **Step 2: Verify RED**

Run mvn -Dtest=TlsDeploymentConfigurationTest test.

- [ ] **Step 3: Add server properties**

~~~properties
# [修改] 同一 Java 进程负责公网 TLS，明文后端仅允许本机访问。
TLS.GATEWAY.ENABLED = true
TLS.GATEWAY.HANDSHAKE.TIMEOUT.MILLIS = 10000
TLS.GATEWAY.CONNECT.TIMEOUT.MILLIS = 10000
TLS.GATEWAY.IDLE.TIMEOUT.MILLIS = 300000
TLS.GATEWAY.MAX.CONNECTIONS = 512
TLS.GATEWAY.BUFFER.SIZE = 65536
~~~

Update the AbstractAcceptor comment to say the built-in TLS Gateway owns idle timeout.

- [ ] **Step 4: Integrate startup in this exact order**

~~~java
BasicServer.startupBasicServer();
TlsGatewayConfig tlsConfig =
        TlsGatewayConfig.load(BasicServer.getMap(), System::getenv);
EmbeddedTlsGateway preparingGateway = null;
if (tlsConfig.isEnabled()) {
    SSLContext tlsContext = new TlsContextFactory().create(
            tlsConfig.getKeyStorePath(),
            tlsConfig.copyKeyStorePassword());
    preparingGateway = new EmbeddedTlsGateway(tlsConfig, tlsContext);
    preparingGateway.prepare();
}
startupIocContainer();
CoreServer.startupCoreServer();
if (preparingGateway != null) {
    TlsBackendReadinessProbe.await(
            tlsConfig.getEndpoints(), tlsConfig.getConnectTimeoutMillis());
    preparingGateway.start();
    tlsGateway = preparingGateway;
}
~~~

On failure, stop prepared listeners, log one top-level ERROR, rethrow IllegalStateException, and let NetServer exit non-zero. Register one shutdown hook calling shutdownTlsGateway.

- [ ] **Step 5: Delete exactly the HAProxy deployment files**

Delete deploy/haproxy/haproxy.cfg and deploy/haproxy/README.md with apply_patch. Keep all certificate files.

- [ ] **Step 6: Verify GREEN**

~~~bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home \
  mvn -Dtest='TlsDeploymentConfigurationTest,com.alibaba.server.nio.tls.*Test' test
~~~

---

### Task 7: Single-process startup script and certificate docs

**Files:** run-net-server-zulu8.sh, generate-local-certs.sh, deploy/tls/README.md, NetServerStartScriptTest.java

- [ ] **Step 1: Write failing script tests**

Successful test runs set NET_SERVER_PUBLIC_IP, NET_SERVER_TLS_KEYSTORE, and an empty NET_SERVER_TLS_KEYSTORE_PASSWORD. Add tests proving missing IP and missing/nonexistent keystore exit non-zero before fake Java runs. Keep exact JDWP and -jar argument assertions.

- [ ] **Step 2: Verify RED**

Run mvn -Dtest=NetServerStartScriptTest test.

- [ ] **Step 3: Add shell validation**

~~~bash
NET_SERVER_PUBLIC_IP="${NET_SERVER_PUBLIC_IP:-}"
NET_SERVER_TLS_KEYSTORE="${NET_SERVER_TLS_KEYSTORE:-}"
NET_SERVER_TLS_KEYSTORE_PASSWORD="${NET_SERVER_TLS_KEYSTORE_PASSWORD:-}"

if [[ -z "$NET_SERVER_PUBLIC_IP" ]]; then
  echo "缺少 NET_SERVER_PUBLIC_IP，无法监听 TLS 公网端口" >&2
  exit 1
fi

if [[ -z "$NET_SERVER_TLS_KEYSTORE" ]]; then
  echo "缺少 NET_SERVER_TLS_KEYSTORE，无法加载 TLS 证书" >&2
  exit 1
fi

if [[ ! -f "$NET_SERVER_TLS_KEYSTORE" ]]; then
  echo "TLS PKCS12 不存在: $NET_SERVER_TLS_KEYSTORE" >&2
  exit 1
fi

export NET_SERVER_PUBLIC_IP
export NET_SERVER_TLS_KEYSTORE
export NET_SERVER_TLS_KEYSTORE_PASSWORD
~~~

Never print the password. Preserve Bash 3.2 behavior.

- [ ] **Step 4: Update certificate generation**

~~~sh
NET_SERVER_TLS_KEYSTORE_PASSWORD=${NET_SERVER_TLS_KEYSTORE_PASSWORD-}
export NET_SERVER_TLS_KEYSTORE_PASSWORD

openssl pkcs12 -export \
    -passout env:NET_SERVER_TLS_KEYSTORE_PASSWORD \
    -inkey "$server_key" \
    -in "$server_certificate" \
    -certfile "$ca_certificate" \
    -out "$output_directory/net-server.p12"
~~~

Keep PEM output for inspection and rollback.

- [ ] **Step 5: Add deploy/tls/README.md**

Document the current IP, PKCS12 path, empty local-development password, and one Java startup command. State that HAProxy is no longer started.

- [ ] **Step 6: Verify scripts**

~~~bash
bash -n scripts/run-net-server-zulu8.sh
sh -n deploy/tls/generate-local-certs.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home \
  mvn -Dtest=NetServerStartScriptTest test
~~~

---

### Task 8: Full verification and live cutover

- [ ] **Step 1: Full tests**

~~~bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home \
  mvn test
~~~

Expected: existing 172 tests plus new TLS tests, all passing.

- [ ] **Step 2: Mandatory Java 8 build**

~~~bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home \
  mvn clean package -DskipTests -Dmaven.compiler.release=8
~~~

Expected: BUILD SUCCESS.

- [ ] **Step 3: Security and diff checks**

~~~bash
rg -n "System\.out\.print|printStackTrace\(" \
  src/main/java/com/alibaba/server/nio/tls
rg -n "NET_SERVER_TLS_KEYSTORE_PASSWORD.*log|password.*log" \
  src/main/java/com/alibaba/server/nio/tls scripts deploy/tls
git diff --check
~~~

- [ ] **Step 4: Stop the old deployment only after verification**

Use lsof on 10086, 10087, 10088, and 10188. Send TERM only to the exact old HAProxy and Java PIDs. Do not use pkill.

- [ ] **Step 5: Start one Java process**

~~~bash
export NET_SERVER_PUBLIC_IP=172.21.32.64
export NET_SERVER_TLS_KEYSTORE=/Users/hljy/.net-server/tls-strict-20260810/net-server.p12
export NET_SERVER_TLS_KEYSTORE_PASSWORD=
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home \
  scripts/run-net-server-zulu8.sh
~~~

- [ ] **Step 6: Verify one PID and zero HAProxy**

lsof must show one Java PID owning public TLS listeners and loopback backends, with no HAProxy process.

- [ ] **Step 7: Verify TLS on all four ports**

~~~bash
for port in 10086 10087 10088 10188; do
  openssl s_client \
    -connect "172.21.32.64:$port" \
    -CAfile /Users/hljy/.net-server/tls-strict-20260810/chat-storage-local-ca.crt \
    -verify_return_error \
    -tls1_2 </dev/null
done
~~~

Expected: Verify return code 0 on every port and certificate SHA-256 AC25C5012DC32C0A3731FE9B1F8284E3A4A87F0144380024C5509A05A8817373.

- [ ] **Step 8: Client smoke tests without code changes**

Verify iOS login/heartbeat/upload/download/media, then macOS login/upload/download, then Android login/upload/download. On failure, retain logs and restore the exact previous Java and HAProxy commands without changing certificates or pinning.

- [ ] **Step 9: Final report**

Report test count, build result, four TLS handshakes, listener PID evidence, three client smoke tests, changed files, and confirmation that no database operation, staging, or commit occurred.
