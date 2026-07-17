# net-server + chat-storage 在线视频播放改造方案

## 1. 背景与结论

当前 macOS 项目 `chat-storage` 的在线播放链路是：

```text
AVPlayer
  -> macOS 本地 LocalMediaServer
  -> VideoStreamingService
  -> SocketManager 自定义帧协议
  -> net-server 10088 文件下载/拉流逻辑
  -> 文件系统
```

这条链路的问题是：AVPlayer 实际需要一个严格的标准 HTTP Range 视频源，但当前实现把 HTTP Range 转换为自定义 TCP 帧，再由本地代理转回 HTTP。大幅拖动进度条时，只要本地代理声明的 `Content-Length` 和实际发送字节数不一致，或者旧 Range 请求被取消，AVPlayer 就会触发播放失败。

推荐改造方向：

```text
上传：chat-storage -> net-server:10087 -> 文件存储
下载：chat-storage -> net-server:10088 -> 原文件下载协议
播放：AVPlayer -> net-server:10188 HTTP Range -> 文件系统
控制：chat-storage -> net-server:10086 -> 登录/文件列表/获取播放 URL
```

核心结论：

- 原上传 `10087` 不重写。
- 原下载 `10088` 不重写。
- 新增独立 HTTP Range 播放服务，建议端口 `10188`。
- macOS 端不再启动本地 HTTP 代理，播放时直接把后端播放 URL 交给 AVPlayer。
- 视频上传仍然是普通文件上传，上传完成后可选标记媒体信息；普通文件完全不进入播放链路。

## 2. 当前后端关键现状

当前仓库路径：

```text
/Users/hljy/IdeaProjects/net-server
```

当前相关文件：

```text
src/main/java/com/alibaba/server/nio/core/server/CoreServer.java
src/main/java/com/alibaba/server/nio/acceptor/MainFileUploadAcceptor.java
src/main/java/com/alibaba/server/nio/acceptor/MainFileDownloadAcceptor.java
src/main/java/com/alibaba/server/nio/service/file/handler/FileUploadHandler.java
src/main/java/com/alibaba/server/nio/service/file/handler/FileDownloadHandler.java
src/main/java/com/alibaba/server/nio/model/file/FileUploadContext.java
src/main/java/com/alibaba/server/nio/repository/file/service/FileService.java
src/main/java/com/alibaba/server/nio/repository/file/service/impl/FileServiceImpl.java
src/main/resources/server.properties
```

现状要点：

- `CoreServer.startupCoreServer()` 启动 Spring IOC、Selector、Acceptor。
- `MainFileUploadAcceptor` 监听 `10087`，设置 `handlerType=UPLOAD`。
- `MainFileDownloadAcceptor` 监听 `10088`，设置 `handlerType=DOWNLOAD`。
- `AbstractAcceptor.createModel()` 给文件连接同时挂载 `FileUploadHandler` 和 `FileDownloadHandler`，实际靠 `handlerType` 分流。
- `FileDownloadHandler.transferFile()` 是整文件顺序推送，最后关闭连接，不适合 AVPlayer seek。
- `FileUploadHandler.handleMetaFrame()` 当前有多用户风险：入库 `userName` 写死为 `"毒药"`，应该改为读取客户端上传元数据里的 `userName`。
- `FileUploadContext.FILE_STORAGE_ROOT` 当前硬编码为本机路径，应逐步切换到 `server.properties` 中的 `NIO.FILE.BASE.PATH.*`。

## 3. 后端目标架构

新增独立包：

```text
src/main/java/com/alibaba/server/nio/media/
  MediaStreamServer.java
  MediaStreamHandler.java
  MediaTokenService.java
  MediaAccessService.java
  MediaContentTypeResolver.java
  RangeHeaderParser.java
  SafeFileResolver.java
  model/ByteRange.java
  model/MediaPlayUrl.java
```

新增配置：

```properties
NIO.MEDIA.STREAM.PORT = 10188
MEDIA.STREAM.TOKEN.SECRET = change-me
MEDIA.STREAM.TOKEN.EXPIRE.SECONDS = 300
MEDIA.STREAM.BUFFER.SIZE = 262144
MEDIA.STREAM.MAX.THREADS = 64
MEDIA.STREAM.MAX.CONNECTIONS.PER.USER = 8
```

Java 8 下优先使用 JDK 自带：

```java
com.sun.net.httpserver.HttpServer
```

这样不需要引入 Spring Boot、Netty、Jetty，也不会干扰原 NIO Reactor 文件协议。

## 4. 后端播放服务行为

新增 HTTP 服务：

```text
HEAD /media/stream/{fileId}?token=xxx
GET  /media/stream/{fileId}?token=xxx
```

必须支持：

```text
Range: bytes=0-
Range: bytes=1048576-2097151
Range: bytes=-65536
无 Range 请求
```

返回示例：

```http
HTTP/1.1 206 Partial Content
Accept-Ranges: bytes
Content-Type: video/mp4
Content-Length: 1048576
Content-Range: bytes 1048576-2097151/123456789
Cache-Control: no-store
```

越界返回：

```http
HTTP/1.1 416 Range Not Satisfiable
Content-Range: bytes */123456789
```

读取策略：

- 根据 `Range` 只读取指定字节范围。
- 使用 `RandomAccessFile` 或 `FileChannel`。
- 固定 buffer，例如 `256KB`。
- 不把整文件读入内存。
- 客户端断开时停止读文件。
- 每个 HTTP Range 请求独立处理，不维护播放会话长状态。

## 5. 播放 URL 授权

macOS 端不应直接拼真实文件路径。应先向主控连接申请播放 URL。

建议新增一个文件操作帧：

```text
filePlayUrlReq  = 0x45
fileResponse    = 0x43
```

请求体：

```json
{
  "fileId": 123,
  "userName": "spring"
}
```

响应体：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "playUrl": "http://172.21.33.149:10188/media/stream/123?token=xxx",
    "expiresIn": 300,
    "fileSize": 123456789,
    "mimeType": "video/mp4"
  }
}
```

Token 内容建议：

```text
fileId
userName 或 userId
expiresAt
nonce
signature = HMAC_SHA256(fileId:userName:expiresAt:nonce, secret)
```

每个 HTTP Range 请求都校验 token，不依赖 Socket 长连接状态。

## 6. 多用户在线拉流设计

多用户同时播放时，不能设计成“一个用户一个长连接上下文”。AVPlayer 本身可能同时发多个 Range 请求，包括文件头、文件尾、seek 目标位置等。

推荐策略：

- 每个 Range 请求独立鉴权、独立打开文件、独立读取范围。
- 使用独立播放线程池，不复用原 `MainFileSelector`。
- 单用户并发 Range 数限制为 8 左右。
- 全局播放线程池最大 64 左右，超限返回 `429 Too Many Requests`。
- 权限第一阶段按文件归属控制：

```text
fileDto.userName == token.userName
```

后续可以扩展好友分享、公开链接、群共享。

## 7. 文件上传与视频可播放标记

视频上传不需要改成“拉流上传”。上传仍然是现有文件上传：

```text
chat-storage -> 10087 -> FileUploadHandler -> 文件系统 + file 表
```

上传完成后可选做媒体识别：

第一阶段最小可行：

- 根据扩展名判断是否可在线播放。
- `mp4/m4v/mov` 标记可播放。
- `mkv/avi/flv` 暂时可标记为不可播放或实验支持。
- 不强制转码。

第二阶段可扩展 file 表字段：

```text
media_type        video/audio/image/other
mime_type         video/mp4
duration_ms       nullable
width             nullable
height            nullable
playable          Y/N
play_path         nullable
transcode_status  NONE/PENDING/RUNNING/SUCCESS/FAILED
```

普通文件上传：

- 完全走原上传逻辑。
- `playable=false`。
- macOS 文件列表不展示播放按钮。
- 下载、删除、重命名不受影响。

视频文件但用户不在线播放：

- 仍只是普通文件。
- 只有点击播放时才申请 `playUrl`。
- 如果不可播放，后端返回“暂不支持在线播放，请下载后播放”。

## 8. macOS 端目标架构

当前 macOS 项目路径：

```text
/Users/hljy/macProjects/chat-storage
```

当前可保留：

```text
chat-storage/Services/VideoWindowManager.swift
chat-storage/Views/StreamingVideoPlayer.swift
```

当前应删除或默认关闭：

```text
chat-storage/Services/LocalMediaServer.swift
chat-storage/Services/VideoStreamingService.swift
chat-storage/Services/VideoStreamCache.swift
chat-storage/Services/VideoStreamLoaderDelegate.swift
```

新增：

```text
chat-storage/Services/VideoPlaybackService.swift
```

职责：

- 调用后端获取播放 URL。
- 解码 `playUrl/fileSize/mimeType/expiresIn`。
- 把 URL 交给 `StreamingVideoPlayer`。

播放流程：

```swift
let playInfo = try await VideoPlaybackService.shared.requestPlayUrl(fileId: fileId)
let item = AVPlayerItem(url: playInfo.playUrl)
let player = AVPlayer(playerItem: item)
```

播放器 UI 可以继续保留当前自定义进度条、播放/暂停、错误提示，但底层数据源从本地代理 URL 改为后端 HTTP Range URL。

## 9. 前后端接口约定

后端播放 URL 响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "playUrl": "http://host:10188/media/stream/123?token=xxx",
    "fileId": 123,
    "fileSize": 123456789,
    "mimeType": "video/mp4",
    "expiresIn": 300,
    "playable": true
  }
}
```

不可播放：

```json
{
  "code": 400,
  "message": "该文件暂不支持在线播放，请下载后播放",
  "data": {
    "playable": false
  }
}
```

无权限：

```json
{
  "code": 403,
  "message": "无权播放该文件"
}
```

文件不存在：

```json
{
  "code": 404,
  "message": "文件不存在"
}
```

## 10. 分阶段实施计划

### 阶段 1：后端 HTTP Range 最小闭环

目标：

- 新增 `10188` HTTP 服务。
- 手动访问播放 URL 能返回正确 `206/416/HEAD`。
- 暂时可以用简单 token 或本地测试 token。

任务：

1. 新增配置常量和 `server.properties` 配置。
2. 在 `CoreServer.startup()` 后启动 `MediaStreamServer`。
3. 实现 `RangeHeaderParser`。
4. 实现 `MediaStreamHandler`。
5. 实现文件安全路径解析。
6. 用 `curl -I` 和 `curl -H "Range: bytes=0-1023"` 验证。

验收：

```bash
curl -I "http://localhost:10188/media/stream/FILE_ID?token=xxx"
curl -v -H "Range: bytes=0-1023" "http://localhost:10188/media/stream/FILE_ID?token=xxx"
curl -v -H "Range: bytes=-65536" "http://localhost:10188/media/stream/FILE_ID?token=xxx"
```

### 阶段 2：播放 URL 授权

目标：

- macOS 不直接知道 token 规则。
- 通过 `10086` 主控连接申请播放 URL。

任务：

1. 新增或复用文件操作帧。
2. 增加 `filePlayUrlReq` 处理逻辑。
3. 实现 `MediaTokenService`。
4. 加入 `fileDto.userName == request.userName` 权限校验。
5. 返回 `playUrl`。

验收：

- 登录用户只能申请自己的文件播放 URL。
- token 过期后 HTTP Range 返回 `403`。
- 修改 token 任一字段后返回 `403`。

### 阶段 3：macOS 替换播放链路

目标：

- `StreamingVideoPlayer` 不再使用本地代理。
- AVPlayer 直接播放后端 HTTP Range URL。

任务：

1. 新增 `VideoPlaybackService.swift`。
2. `VideoWindowManager.show()` 或 `StreamingVideoViewModel.setupPlayer()` 改为先申请播放 URL。
3. 删除或默认关闭 `LocalMediaServer` 调用。
4. 保留原 UI 控件和错误提示。
5. 文件列表中仅对可播放视频展示播放按钮。

验收：

- mp4/mov 文件可起播。
- 大幅前后 seek 不再触发本地代理错误。
- 普通下载、上传不受影响。

### 阶段 4：上传归属与媒体元数据修正

目标：

- 多用户权限可靠。
- 文件归属不再写死。

任务：

1. `FileUploadHandler.handleMetaFrame()` 使用客户端上传元数据中的 `userName`。
2. `FileUploadContext` 存储根路径改为读取配置。
3. 可选新增媒体字段或先在 DTO 层根据扩展名计算 `playable`。

验收：

- A 用户上传的视频，B 用户无法申请播放 URL。
- 原上传断点续传仍能工作。
- 原普通下载仍能工作。

## 11. 风险点与规避

风险 1：HTTP Range 实现不标准
规避：优先写 `RangeHeaderParser` 单测或独立 main 测试，覆盖 `0-`、`100-200`、`-65536`、越界。

风险 2：路径穿越
规避：所有文件路径 `normalize()` 后必须在允许的根目录下。

风险 3：token 泄露
规避：短期有效，默认 5 分钟；播放 URL 不长期存储。

风险 4：多用户并发压垮磁盘
规避：独立线程池、单用户并发限制、全局并发限制。

风险 5：影响原下载
规避：不改 `FileDownloadHandler`，播放服务独立端口。

风险 6：旧上传 userName 错误
规避：先兼容旧数据，新增上传从客户端 metadata 写入真实 userName；播放授权以新数据为准。

## 12. 如何用 Codex 主对话完成这个功能

由于当前这是 side conversation，建议不要在这里直接开始大改。回到 Codex 主对话 session 后，按阶段给 Codex 下指令。

推荐主对话第一条指令：

```text
请读取 /Users/hljy/IdeaProjects/net-server/docs/media-streaming-refactor-plan.md，
按照方案先实现阶段 1：在 net-server 中新增独立 HTTP Range 在线播放服务。
要求：
1. 不修改现有 10087 上传协议和 10088 下载协议；
2. 新增 NIO.MEDIA.STREAM.PORT 配置，默认 10188；
3. 使用 Java 8 可用方案，优先 JDK HttpServer；
4. 支持 HEAD/GET、Range: bytes=0-、bytes=start-end、bytes=-suffix；
5. 返回标准 200/206/416；
6. 文件读取必须分块，不能整文件读入内存；
7. 完成后用 curl 给出验证方法。
```

阶段 1 完成并验证后，再发：

```text
继续读取 /Users/hljy/IdeaProjects/net-server/docs/media-streaming-refactor-plan.md，
实现阶段 2：播放 URL 授权。
要求：
1. 增加短期 token 生成和校验；
2. 增加通过主控连接获取播放 URL 的接口或帧处理；
3. 校验 fileId 存在、文件未删除、用户有权限；
4. token 过期或篡改时 HTTP 播放接口返回 403；
5. 不影响原上传下载。
```

后端阶段 1 和 2 稳定后，再切到 macOS 项目：

```text
请在 /Users/hljy/macProjects/chat-storage 中修改在线播放实现，
读取 /Users/hljy/IdeaProjects/net-server/docs/media-streaming-refactor-plan.md 的 macOS 部分，
实现阶段 3：使用后端 HTTP Range URL 直接交给 AVPlayer 播放。
要求：
1. 新增 VideoPlaybackService 获取 playUrl；
2. StreamingVideoPlayer 不再依赖 LocalMediaServer/VideoStreamingService/VideoStreamCache；
3. 保留现有播放器窗口和播放控制 UI；
4. 普通上传下载不受影响；
5. 大幅 seek 要走 AVPlayer -> 后端 HTTP Range。
```

最后处理上传归属和媒体元数据：

```text
继续按照方案实现阶段 4：
1. 修正 net-server FileUploadHandler 上传入库 userName 写死的问题；
2. 文件存储根路径读取 server.properties；
3. 文件列表或详情中返回 playable/mimeType；
4. 普通文件不展示播放按钮，视频文件可申请播放 URL。
```

主对话执行建议：

- 每次只做一个阶段。
- 每个阶段完成后先构建/验证，再进入下一阶段。
- 后端改完阶段 1 后，可以先不动 macOS，用 curl 直接验证 Range。
- macOS 改造前，确保后端播放 URL 已经能被浏览器或 curl 正常访问。
- 不要让 Codex 同时大改 `net-server` 和 `chat-storage`，否则上下文和回归风险会变高。
