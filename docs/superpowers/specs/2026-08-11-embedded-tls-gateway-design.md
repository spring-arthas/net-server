# net-server 内置 TLS Gateway 设计

## 目标

把当前 HAProxy 承担的 TLS 终止和四端口回环转发能力移入 `net-server`，最终只启动一个 Java 进程。

客户端可见契约保持不变：

- iOS、macOS、Android 继续连接原来的 IP 和 `10086`、`10087`、`10088`、`10188` 端口。
- 三端继续使用 TLS，不改成明文 Socket。
- 继续使用当前严格 CA 和服务端证书，不改变证书链。
- `10086`、`10087`、`10088` 解密后的 `FA CE` 帧原样交给现有 NIO Pipeline。
- `10188` 解密后的 HTTP/1.1 数据原样交给现有 `MediaStreamServer`。

## 已确认的根因

三端客户端发起连接时先发送 TLS 握手数据，现有 `net-server` 的 NIO Acceptor 只识别明文业务帧。
客户端直接连接明文 NIO 端口时，TLS 握手数据会被当成业务帧，登录请求无法进入
`TextTransmissionHandler`。HAProxy 当前只负责解密和转发，不参与登录、上传、下载或媒体业务。

## 方案比较

### 方案一：JVM 内置独立 TLS Gateway，推荐

在 `net-server` 内增加一个独立网络层。它使用 JDK 8 的 `SSLServerSocket` 接收 TLS，解密后通过
`127.0.0.1` 转发到现有服务。原有 NIO、帧解析、登录处理、上传下载和媒体处理代码不改。

优点：改动边界清楚，客户端零改动，业务协议回归风险最低，可以完整替代 HAProxy。

代价：TLS Gateway 使用阻塞式双向转发，每个活跃连接占用两个转发线程。线程按实际连接按需创建，
默认并发上限保持 HAProxy 当前的 512，并允许通过配置下调。

### 方案二：把 `SSLEngine` 接入现有 NIO Selector

优点：线程数少，连接规模更高。

缺点：必须改造握手、半包、读写队列、背压和关闭流程；`10086`、`10087`、`10088` 的现有协议链路
都会受到影响，三端回归风险最高。

### 方案三：三端改成明文 Socket

优点：服务端改动少。

缺点：安全降级，需要同时修改 iOS、macOS、Android，且违背当前严格 TLS 设计，不采用。

## 最终架构

```text
iOS / macOS / Android
        |
        | TLS 1.2，现有证书
        v
net-server JVM
  EmbeddedTlsGateway
        |
        | 解密后的本机 TCP
        +--> 127.0.0.1:10086  TextTransmissionAcceptor
        +--> 127.0.0.1:10087  MainFileUploadAcceptor
        +--> 127.0.0.1:10088  MainFileDownloadAcceptor
        +--> 127.0.0.1:10188  MediaStreamServer
```

这里仍有一层回环 Socket，但它和业务服务在同一个 JVM、同一个启动脚本、同一个生命周期内，
不再存在第二个 HAProxy 进程。

## 组件设计

新增包 `com.alibaba.server.nio.tls`：

### `TlsGatewayConfig`

负责读取和校验配置，生成四个端口映射。所有数值必须在启动阶段完成范围校验。

- 公网监听 IP：环境变量 `NET_SERVER_PUBLIC_IP`。
- PKCS12 路径：环境变量 `NET_SERVER_TLS_KEYSTORE`。
- PKCS12 密码：环境变量 `NET_SERVER_TLS_KEYSTORE_PASSWORD`，不写入 `server.properties`，不打印日志。
- 后端 IP：固定为 `127.0.0.1`。
- 后端端口：复用现有 `NIO.*.PORT` 和 `NIO.MEDIA.STREAM.PORT`。
- TLS 握手超时：10 秒。
- 后端连接超时：10 秒。
- 双向空闲超时：5 分钟。
- 最大并发连接：默认 512，与当前 HAProxy `maxconn` 一致。
- 单次转发缓冲区：64 KiB。

`NET_SERVER_TLS_KEYSTORE_PASSWORD` 未设置时按空密码加载，兼容现有
`/Users/hljy/.net-server/tls-strict-20260810/net-server.p12`。无论密码是否为空，都不得写入日志。

### `TlsContextFactory`

使用 JDK 8 `KeyStore`、`KeyManagerFactory` 和 `SSLContext` 加载 PKCS12。只启用 TLS 1.2，
服务端不要求客户端证书。证书文件不存在、格式错误、密码错误或没有私钥条目时抛出启动异常。

### `EmbeddedTlsGateway`

负责整个 Gateway 生命周期：

- 在指定公网 IP 上预绑定四个 `SSLServerSocket`。
- 每个端口使用一个独立 Acceptor 线程。
- TLS 握手在线程池执行，避免一个慢客户端阻塞整个监听端口。
- 统一维护活动连接，停止时关闭监听 Socket 和全部连接。
- 任意端口绑定失败时关闭已经绑定的其他端口，并让 Java 进程启动失败。

### `TlsSocketRelay`

每条连接建立一个 TLS 客户端 Socket 和一个回环后端 Socket。两个方向分别复制字节：

- 客户端到后端：TLS 解密后写入回环 Socket。
- 后端到客户端：读取明文响应，经 TLS 加密后写回客户端。

Gateway 不解析、不拼接、不修改业务数据。任一方向出现 EOF、超时或 IO 异常时，原子关闭这一对
Socket，避免只关闭一侧形成僵尸连接。

## 启动和停止流程

启动顺序：

1. `BasicServer` 加载现有配置。
2. 校验公网 IP、PKCS12、密码和四个端口，并预绑定公网 TLS 监听 Socket。
3. 启动 Spring XML 容器。
4. 启动三个明文 NIO 后端和媒体 HTTP 后端，它们继续只监听 `127.0.0.1`。
5. 确认四个回环端口已经可连接。
6. 启动 TLS Acceptor，开始对外服务。
7. 注册 JVM shutdown hook，统一关闭 Gateway。

启动过程中任一步失败，都关闭已创建的 Gateway 资源并让进程以非零状态退出，不保留“Java 还在，
但外部端口不可用”的半启动状态。

停止时先关闭公网 TLS 监听，拒绝新连接，再关闭活动连接和线程池。现有 NIO、IOC 和媒体服务随 JVM
一起退出。

## 配置和启动脚本

`server.properties` 增加非敏感配置：

```properties
TLS.GATEWAY.ENABLED = true
TLS.GATEWAY.HANDSHAKE.TIMEOUT.MILLIS = 10000
TLS.GATEWAY.CONNECT.TIMEOUT.MILLIS = 10000
TLS.GATEWAY.IDLE.TIMEOUT.MILLIS = 300000
TLS.GATEWAY.MAX.CONNECTIONS = 512
TLS.GATEWAY.BUFFER.SIZE = 65536
```

`scripts/run-net-server-zulu8.sh` 在启动前检查：

```bash
NET_SERVER_PUBLIC_IP=172.21.32.64
NET_SERVER_TLS_KEYSTORE=/Users/hljy/.net-server/tls-strict-20260810/net-server.p12
NET_SERVER_TLS_KEYSTORE_PASSWORD=
```

脚本只执行 Java，不再检查或启动 HAProxy。缺少公网 IP 或 PKCS12 时输出明确错误并退出。

## 兼容性保证

- 不修改 iOS、macOS、Android 客户端代码。
- 不修改三端服务器地址和端口。
- 不修改 `FA CE` 帧格式、命令字、登录参数或响应格式。
- 不修改现有 `TextTransmissionHandler`、`FileUploadHandler`、`FileDownloadHandler` 业务逻辑。
- 不修改数据库结构和数据。
- `10188` 对外仍是 HTTPS。JDK 8 Gateway 不声明 HTTP/2 ALPN，三端网络栈会回退到现有
  `MediaStreamServer` 支持的 HTTP/1.1；媒体接口、URL 和响应内容不变。
- 继续使用当前证书，证书 SHA-256 保持
  `AC25C5012DC32C0A3731FE9B1F8284E3A4A87F0144380024C5509A05A8817373`。

## 异常和日志

- TLS 握手失败：记录远端 IP、端口和异常类型，不打印原始握手数据。
- 后端不可连接：关闭当前连接并记录目标端口。
- 达到连接上限：立即关闭新连接并按时间窗口输出 WARN，避免日志刷屏。
- 空闲超时：关闭连接并记录连接持续时间。
- 日志禁止输出 PKCS12 密码、私钥、Token 和完整业务帧。
- 单连接错误只影响当前连接，不终止其他端口的 Acceptor。

## 测试设计

先写失败测试，再实现：

1. 配置测试：缺少公网 IP、证书不存在、密码错误、端口重复时必须失败。
2. TLS 上下文测试：加载现有 PKCS12，验证服务端证书指纹和 TLS 1.2。
3. 单端口集成测试：客户端通过 TLS 发送随机字节，回环后端收到完全一致的数据并原样返回。
4. 四端口测试：`10086`、`10087`、`10088`、`10188` 映射全部正确。
5. 分片测试：覆盖小包、超过 64 KiB 的大包和多次读写，证明 Gateway 不破坏半包。
6. 关闭测试：客户端断开、后端断开、读超时和 Gateway 停止都能关闭双向 Socket。
7. 容量测试：达到最大连接数后拒绝新连接，已有连接继续工作。
8. 启动脚本测试：只启动 Java，不包含 HAProxy 命令，缺少 TLS 参数时快速失败。
9. 回归测试：执行 `net-server` 全量测试和强制 Java 8 编译。
10. 真实验证：停止 HAProxy 后启动新 Jar，检查四个公网端口只由 Java 监听，执行 TLS 握手、登录、
    上传、下载、媒体请求，并分别验证 iOS、macOS、Android。

## 验收标准

- 系统中不再运行 HAProxy。
- `lsof` 显示公网四端口和回环四端口都由同一个 Java PID 持有。
- 三端无需改代码即可完成登录。
- 上传、下载和媒体播放通过。
- 证书链和服务端证书指纹不变。
- Java 8 编译通过，现有测试和新增测试全部通过。

## 非目标

- 本次不把 TLS 深度接入现有 NIO Selector。
- 本次不修改客户端证书固定逻辑。
- 本次不调整登录、文件传输和媒体业务协议。
- 本次不执行数据库迁移。
