# Error Handling

> 统一异常处理与错误规范

---

## 核心原则

1. **禁止裸 `Exception`** — 必须使用具体业务异常类
2. **禁止空 catch 块** — 至少打印日志
3. **关键流程必须记录异常** — 入参 + 异常信息都要打印
4. **异常不在 Pipeline/Selector 层吞掉** — 必须向上传递或转换后响应客户端

---

## 异常类型

### 业务异常

使用项目内自定义业务异常类（继承 `RuntimeException`），按模块区分：

- `UserAuthException` — 用户认证/鉴权失败
- `FileOperationException` — 文件操作失败（上传/下载/移动/删除）
- `DirectoryException` — 目录操作失败
- `RateLimitException` — 限速触发

```java
// 错误
public void uploadFile(FileUploadParam param) throws Exception { ... }

// 正确
public void uploadFile(FileUploadParam param) throws FileOperationException { ... }
```

### 系统异常

- 底层 IO 异常（`IOException`）在 NIO 层捕获，转换为业务异常或记录后关闭连接
- 不允许将 `IOException`、`SQLException` 等直接暴露给上层业务逻辑

---

## 异常处理模式

### Service 层（标准写法）

```java
public FileInfoDTO getFileInfo(FileQueryParam param) {
    log.info("查询文件信息，入参：{}", param);
    try {
        FileDO fileDO = fileRepository.findById(param.getFileId());
        if (fileDO == null) {
            throw new FileOperationException("文件不存在，fileId=" + param.getFileId());
        }
        FileInfoDTO result = FileConverter.toDTO(fileDO);
        log.info("查询文件信息成功，出参：{}", result);
        return result;
    } catch (FileOperationException e) {
        log.warn("查询文件信息失败，入参：{}，原因：{}", param, e.getMessage());
        throw e;
    } catch (Exception e) {
        log.error("查询文件信息异常，入参：{}", param, e);
        throw new FileOperationException("查询文件信息失败", e);
    }
}
```

### NIO Handler 层（连接异常处理）

```java
// ChannelHandler 实现中，捕获异常后关闭连接并清理资源
try {
    processRequest(channelContext);
} catch (UserAuthException e) {
    log.warn("认证失败，关闭连接，channel={}, 原因：{}", channel, e.getMessage());
    closeChannel(channel);
} catch (Exception e) {
    log.error("处理请求异常，关闭连接，channel={}", channel, e);
    closeChannel(channel);
}
```

### 禁止写法

```java
// 禁止：空 catch 块
try {
    doSomething();
} catch (IOException e) {
    // 什么都不做 ← 严禁
}

// 禁止：catch 后只打印，但不处理/抛出（在需要上报的场景）
try {
    doSomething();
} catch (Exception e) {
    e.printStackTrace(); // 用 log.error，不用 printStackTrace
}

// 禁止：声明 throws Exception
public void doWork() throws Exception { ... } // ← 严禁
```

---

## 向客户端响应错误

本项目无 HTTP 层，错误通过自定义二进制协议帧返回客户端：

- 构造错误响应帧（`TransportDataModel`），设置错误码和错误消息
- 写入 Channel 的 Write 事件队列
- 关闭连接（视错误严重程度决定是否关闭）

```java
// 示例：认证失败响应
TransportDataModel errorResponse = TransportDataModel.buildError(
    ErrorCode.AUTH_FAILED, "用户名或密码错误");
channelContext.writeResponse(errorResponse);
channelContext.closeChannel();
```

---

## 常见错误

- 在事务方法内 catch 异常后吞掉（导致事务不回滚）
- 将底层 `IOException` 直接暴露给业务层
- 在 Selector 线程内抛出未捕获异常（导致整个 Selector 崩溃）
- 异常信息不带上下文（如 fileId、userId），导致排查困难


## Java Rules Overlay - Exception Strategy

- 领域异常优先使用自定义 `RuntimeException`（带关键业务上下文），避免到处传播通用异常。
- 禁止宽泛 `catch (Exception e)`（除顶层兜底）；禁止吞异常或只记录“失败”而不带主键上下文。
- 对外错误响应禁止泄漏堆栈、SQL 细节、内部路径；内部日志保留完整异常堆栈用于排查。
- 需要返回可空结果时优先 `Optional.orElseThrow(...)` 或显式分支，避免“空值继续流转”。
