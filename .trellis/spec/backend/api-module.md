# API Module Guidelines

> 协议设计、消息格式与通信规范（自实现 NIO 二进制协议，非 HTTP REST）

---

## 架构说明

本项目**无 HTTP Controller 层**，采用自实现 Reactor 模型 + 自定义二进制协议进行客户端/服务端通信。

每个功能模块对应独立端口：

| 端口  | 功能             | Acceptor                    |
|-------|-----------------|-----------------------------|
| 10086 | 文本/聊天通道    | TextTransmissionAcceptor    |
| 10087 | 文件上传         | MainFileUploadAcceptor      |
| 10088 | 文件下载         | MainFileDownloadAcceptor    |
| 10089 | 断点续传上传     | ResumeUploadAcceptor        |
| 10090 | 断点续传下载     | ResumeDownloadAcceptor      |

---

## 传输帧模型（TransportDataModel）

所有通信数据封装为 `TransportDataModel`，含以下核心字段：

- **命令类型**（cmd/type）：标识操作（登录、上传、下载、目录操作等）
- **数据载荷**（payload/data）：具体业务数据（序列化后的 JSON 或二进制）
- **状态码**：成功/失败标识
- **消息**：错误描述（失败时填写）

```java
// 构建成功响应
TransportDataModel response = TransportDataModel.buildSuccess(cmd, data);

// 构建错误响应
TransportDataModel errorResponse = TransportDataModel.buildError(ErrorCode.AUTH_FAILED, "用户名或密码错误");
```

---

## ChannelHandler 规范

### 接口约定

所有业务处理器实现 `ChannelHandler` 接口，继承 `AbstractChannelHandler`：

```java
public class FileUploadHandler extends AbstractChannelHandler {
    @Override
    public void handle(SocketChannelContext channelContext) {
        // 1. 解析请求帧
        // 2. 调用 Service 层处理业务
        // 3. 构建响应帧写回 Channel
    }
}
```

### Pipeline 责任链

每个 Channel 绑定一个 `DefaultChannelPipeLine`，Handler 按顺序执行：

1. **协议解析 Handler** — 解析二进制帧为 `TransportDataModel`
2. **认证 Handler** — 验证连接是否已登录
3. **业务 Handler** — 执行具体业务逻辑
4. **响应 Handler** — 将结果写回 Channel

### 耗时操作规范

- **禁止**在 Selector 线程（Pipeline 执行线程）内执行耗时操作
- 文件 IO、数据库查询等必须提交到 **Worker 线程池**异步执行

```java
// 正确：提交到 Worker 线程池
workerExecutor.submit(() -> {
    FileInfoDTO result = fileService.uploadFile(param);
    channelContext.writeResponse(TransportDataModel.buildSuccess(CMD_UPLOAD, result));
});
```

---

## 响应规范

### 成功响应

```json
{
  "code": 0,
  "message": "success",
  "data": { ... }
}
```

### 失败响应

```json
{
  "code": 1001,
  "message": "用户名或密码错误",
  "data": null
}
```

### 错误码规范

| 范围 | 类型 |
|------|------|
| 0 | 成功 |
| 1000-1999 | 用户认证相关错误 |
| 2000-2999 | 文件操作相关错误 |
| 3000-3999 | 目录操作相关错误 |
| 9000-9999 | 系统内部错误 |

---

## 限速规范

限速通过 `TokenBucketRateLimiter`（令牌桶）控制：

- `FILE.UPLOAD.PER.CONNECTION.RATE.BPS` — 单连接上传限速（字节/秒）
- `FILE.UPLOAD.GLOBAL.RATE.BPS` — 全局上传限速（字节/秒）

超出限速时记录 WARN 日志，并暂停当前连接的数据读取，不得直接关闭连接。

---

## 调试客户端

`nio/client/` 下内置调试客户端，可直接运行：

| 客户端 | 用途 |
|--------|------|
| `UserAuthClient` | 测试用户认证流程 |
| `FileUploadClient` | 测试文件上传 |
| `FileDownloadClient` | 测试文件下载 |
| `DirectoryClient` | 测试目录操作 |

这些客户端**仅用于调试**，不参与生产逻辑。


## Java Rules Overlay - API Envelope

- 建议统一响应包裹结构（如 `success/data/error`），减少多端分支判断成本。
- DTO 映射放在边界层（Controller/Service 出口），避免内部领域对象直接透传到 API 层。
- 错误信息对外保持通用、可读，不暴露内部实现细节；详细信息保留在服务端日志。
