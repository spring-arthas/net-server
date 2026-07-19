# FileUploadHandler 功能分析文档

> 源文件路径：`com.alibaba.server.nio.service.file.handler.FileUploadHandler`
> 所属模块：文件上传通道处理层（端口 10087）

---

## 1. 类概述

`FileUploadHandler` 是 net-server 文件上传专属通道（端口 10087）的核心业务处理器，继承自 `AbstractChannelHandler`，运行在每连接独立的 `ChannelWorker` 线程中。

其核心职责是：

- 通过 `FrameUploadParser` 将 TCP 字节流解析为自定义二进制帧，从根本上解决粘包/半包问题
- 按帧类型分发到对应的业务处理逻辑（断点检查 → 元数据 → 数据传输 → 结束确认）
- 维护并发控制、双层限速、上传上下文生命周期及断连恢复等工程化能力

---

## 2. 静态成员与初始化

类加载时通过静态初始化块完成全局资源的创建：

| 成员 | 类型 | 说明 |
|------|------|------|
| `config` | `FileUploadConfig` | 上传配置（速率、并发数等），从 `server.properties` 读取 |
| `globalRateLimiter` | `TokenBucketRateLimiter` | 全局令牌桶限速器，控制服务器总上传带宽（默认 20 MB/s） |
| `uploadSemaphore` | `Semaphore` | 并发上传数信号量（默认最多 30 个并发） |
| `uploadContextMap` | `ConcurrentHashMap<taskId, FileUploadContext>` | 上传上下文缓存，每个任务对应一个上下文对象 |
| `parserMap` | `ConcurrentHashMap<remoteAddress, FrameUploadParser>` | 帧解析器缓存，每个连接对应一个解析器实例 |

---

## 3. 协议帧类型与处理流程

### 3.1 帧格式（统一 8 字节头）

```
+--------+--------+--------+--------+----------------+
| Magic  |  Type  | Flags  | Length |      Data      |
| 2字节  | 1字节  | 1字节  | 4字节  |    N字节       |
+--------+--------+--------+--------+----------------+
```

### 3.2 帧类型与处理方法对照

| 帧类型 | 方向 | 触发场景 | 处理方法 |
|--------|------|----------|----------|
| `RESUME_CHECK` | Client → Server | 每次上传前均发送，探测是否存在断点 | `handleResumeCheck()` |
| `META_FRAME` | Client → Server | 全新上传时，发送文件元数据 | `handleMetaFrame()` |
| `DATA_FRAME` | Client → Server | 文件内容分块传输 | `handleDataFrame()` |
| `END_FRAME` | Client → Server | 文件传输完毕通知服务端 | `handleEndFrame()` |
| `RESUME_ACK` | Server → Client | 对断点检查的应答 | `sendResumeAck()` |
| `ACK_FRAME` | Server → Client | 对 META/END 帧的应答 | `sendAckFrame()` |

### 3.3 完整业务流程

```
客户端                                     服务端
  │                                          │
  │─── RESUME_CHECK（携带 MD5、文件名等） ──→│ handleResumeCheck()
  │                                          │  ├─ 查 CheckpointManager（内存）
  │                                          │  └─ 查数据库 PAUSED 任务
  │                                          │
  │←── RESUME_ACK（status: new）  ──────────│  [无断点] → 告知客户端全新上传
  │←── RESUME_ACK（status: resume, offset）─│  [有断点] → 创建上下文，告知续传起点
  │                                          │
  │                                          │
  │─── META_FRAME（全新上传时发送）  ───────→│ handleMetaFrame()
  │                                          │  ├─ 校验目录合法性
  │                                          │  ├─ 创建 file_task 数据库记录
  │                                          │  ├─ 申请并发许可（Semaphore）
  │                                          │  ├─ 打开 FileChannel（覆盖模式）
  │                                          │  └─ 初始化单连接令牌桶限速器
  │←── ACK_FRAME（status: ready）  ─────────│
  │                                          │
  │─── DATA_FRAME × N  ─────────────────────→│ handleDataFrame()
  │                                          │  ├─ 定位活跃上传上下文
  │                                          │  ├─ FileChannel.write() 写入磁盘
  │                                          │  ├─ 实时速率统计
  │                                          │  └─ 每 60s 或 60MB 持久化一次进度
  │                                          │
  │─── END_FRAME（传输完毕）  ──────────────→│ handleEndFrame()
  │                                          │  ├─ 关闭 FileChannel（force 刷盘）
  │                                          │  ├─ 更新 file_task 状态为 UPLOAD_SUCCESS
  │                                          │  ├─ 在 file 主表插入文件记录
  │                                          │  ├─ 清除 CheckpointManager 断点记录
  │                                          │  └─ 释放信号量、清理上下文、关闭连接
  │←── ACK_FRAME（status: success）  ───────│
```

---

## 4. 核心方法说明

### 4.1 `handler()` — 入口方法

```java
public void handler(Object o, ChannelContext channelContext) throws IOException
```

- 校验 `handlerType` 是否为 `"UPLOAD"`，非上传连接直接放行给 Pipeline 下一个 Handler
- 为当前连接获取或创建 `FrameUploadParser`
- 遍历本次读取的所有 `GroupData`，解析出完整帧后逐帧调用 `handleFrame()`

### 4.2 `handleResumeCheck()` — 断点检查

处理客户端上传前的断点探测请求，执行两级断点查找：

1. **内存查找**：通过 `CheckpointManager.getCheckpoint(md5)` 在内存中检索
2. **数据库兜底**：若内存无记录（服务端可能重启），查询 `file_task` 表中 `PAUSED` 状态的任务

找到断点后调用通用上下文初始化方法，**以磁盘文件真实大小作为续传偏移的唯一权威来源**（而非断点对象中记录的值），有效防止内存计数器与磁盘数据不一致导致的文件损坏。

### 4.3 `handleMetaFrame()` — 元数据处理（全新上传）

- 解析 JSON 元数据（文件名、大小、类型、MD5、目录 ID 等）
- 调用 `createAndInitializeUploadContext()` 完成初始化
- 向客户端发送 `ready` 状态的 ACK

### 4.4 `createAndInitializeUploadContext()` — 上下文统一初始化

全新上传与断点续传的**公共工厂方法**，包含完整的初始化步骤：

| 步骤 | 操作 |
|------|------|
| 1 | 校验目标目录是否合法（`fileService.validateDirectory(dirId)`） |
| 2 | 全新上传时在 `file_task` 表创建 `WAIT_UPLOAD` 状态记录 |
| 3 | 根据模式（全新/续传）填充 `FileUploadContext` |
| 4 | `uploadSemaphore.tryAcquire()` 申请并发许可，超限时删除 DB 记录并返回 `null` |
| 5 | `uploadContext.openFileChannel()` 打开文件通道（含截断/续传偏移逻辑） |
| 6 | 将上下文写入 `uploadContextMap`，并将 taskId 绑定到 Channel 属性 |
| 7 | 为连接创建单连接令牌桶限速器（支持动态速率调整） |

### 4.5 `handleDataFrame()` — 数据写入

- 通过 `getActiveUploadContext()` 定位当前连接的活跃上传任务
- 调用 `uploadContext.writeData()` 循环写入直至数据全部落盘
- 实时更新速率统计
- **进度定期持久化**：每隔 60 秒或累计写入超过 60 MB，调用 `fileTaskService.updateProgress()` 更新数据库

### 4.6 `handleEndFrame()` — 上传完成处理

- 关闭文件通道（`markCompleted()`，内部调用 `FileChannel.force(true)` 强制刷盘）
- 更新 `file_task` 状态为 `UPLOAD_SUCCESS`
- 调用 `fileService.createByTask()` 在 `file` 主表创建文件记录
- 删除 `CheckpointManager` 中对应的断点记录
- 释放信号量、清理上下文 Map 和解析器 Map、关闭 TCP 连接

### 4.7 `getActiveUploadContext()` — 上下文精准定位

采用**两级查找**策略，修复了早期版本并发场景下可能写错文件的 Bug：

- **主路径（O(1)）**：从 Channel 属性中读取 `currentUploadTaskId`，直接 `Map.get()` 精确命中
- **兜底路径**：按 `remoteAddress` 遍历 Map，找到后将 taskId 回写 Channel 属性加速下次查找

### 4.8 `cleanupConnection()` — 连接断开清理（静态）

由连接关闭事件触发，处理客户端异常断连的情况：

- 移除对应 `remoteAddress` 的帧解析器
- 对所有未完成的上传上下文调用 `cleanupContext()`

### 4.9 `checkAndFreezeIdleTasks()` — 超时任务清理（静态）

由定时任务调用，清理长时间没有活跃数据的上传任务，防止资源泄漏。

### 4.10 `cleanupContext()` — 上下文清理与断点保存（核心逻辑）

中断清理的关键流程：

```
1. FileChannel.force(true) → 强制刷盘，确保数据一致性
2. 读取磁盘文件实际大小（作为断点偏移的权威来源）
3. 对比内存计数器与磁盘大小，不一致时打印告警
4. 构造 UploadCheckpoint 写入 CheckpointManager（内存）
5. 更新 file_task 状态为 PAUSED，currentOffset = 磁盘实际大小
6. 释放并发许可（Semaphore.release()）
```

若任务无 MD5 或未写入任何数据，则视为无效任务，调用 `markFailed()` 删除临时文件。

---

## 5. 限速机制

采用**双层令牌桶**设计，防止单个连接或全局流量失控：

| 层级 | 实现 | 控制范围 | 默认值 |
|------|------|----------|--------|
| 全局限速 | `globalRateLimiter`（静态） | 服务器总上传带宽 | 20 MB/s |
| 单连接限速 | `socketChannelContext.rateLimiter`（per-connection） | 每个客户端连接 | 2 MB/s |

支持**动态速率调整**（`enableDynamicRateAdjustment=true`）：单连接速率 = `min(perConnectionRate, globalRate / 当前并发数)`，最低保障 512 KB/s。

令牌桶容量 = 速率 × `bucketCapacityMultiplier`（默认 2），允许短时突发流量。

---

## 6. 配置参数（server.properties）

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `FILE.UPLOAD.PER.CONNECTION.RATE.BPS` | 2097152（2 MB/s） | 单连接上传速率上限 |
| `FILE.UPLOAD.GLOBAL.RATE.BPS` | 20971520（20 MB/s） | 全局总上传带宽上限 |
| `FILE.UPLOAD.MAX.CONCURRENT.UPLOADS` | 30 | 最大并发上传数 |
| `FILE.UPLOAD.ENABLE.DYNAMIC.RATE.ADJUSTMENT` | false | 是否启用动态速率调整 |
| `FILE.UPLOAD.BUCKET.CAPACITY.MULTIPLIER` | 2 | 令牌桶容量倍数（支持突发） |

---

## 7. 关键依赖类

| 类 | 职责 |
|----|------|
| `FrameUploadParser` | TCP 字节流 → 完整帧的状态机解析器，解决粘包半包 |
| `FileUploadContext` | 单次上传的全量状态（进度、通道、速率、任务 ID 等） |
| `FileUploadConfig` | 从 `server.properties` 加载上传相关配置 |
| `CheckpointManager` | 内存断点仓库（MD5 → UploadCheckpoint），跨连接恢复续传 |
| `UploadCheckpoint` | 断点数据对象（MD5、文件路径、已上传大小、任务 ID 等） |
| `TokenBucketRateLimiter` | 令牌桶限速实现 |
| `FileService` | 目录校验、file 主表创建 |
| `FileTaskService` | file_task 表的 CRUD 与进度更新 |
| `WriteQueueHelper` | NIO 事件驱动写队列，避免直接 `channel.write()` 阻塞 |

---

## 8. 并发安全设计

- `uploadContextMap` / `parserMap` 均使用 `ConcurrentHashMap`，无需外部加锁
- `Semaphore.tryAcquire()` 非阻塞，超限立即返回，快速失败
- 每连接由独立 `ChannelWorker` 串行处理，同一连接内不存在并发竞争
- `getActiveUploadContext()` 的主路径基于 Channel 属性绑定的 taskId，彻底避免多连接同 IP 时的上下文误匹配

---

## 9. 已知问题与注意事项

- `cleanupContext()` 中存在重复设置 `checkpoint.setMd5()` 的冗余代码（行 916），不影响正确性但可整理
- `handleDataFrame()` 中进度条相关的 `System.out.print` 代码已被注释，生产环境无副作用
- 断点信息仅存储在内存（`CheckpointManager`），服务端重启后内存记录丢失，但可通过查询数据库中 `PAUSED` 状态的 `file_task` 记录进行恢复，两级机制互补
- 上传完成后服务端主动关闭 TCP 连接（`NioServerContext.closedAndRelease()`），客户端需妥善处理连接关闭事件
