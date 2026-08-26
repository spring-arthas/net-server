# Logging Guidelines

> 日志规范（SLF4J + Log4j）

---

## 技术选型

- 接口：**SLF4J**（`org.slf4j.Logger`）
- 实现：**Log4j**（配置文件：`src/main/resources/log4j.properties`）
- 声明方式：使用 **Lombok `@Slf4j`** 注解（推荐）或手动声明

```java
// 推荐：Lombok 自动注入
@Slf4j
public class FileServiceImpl implements FileService { ... }

// 备用：手动声明
private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);
```

---

## 日志级别使用规范

| 级别 | 使用场景 |
|------|---------|
| `ERROR` | 未预期的系统异常、需要立即处理的错误（如连接池耗尽、数据库不可达） |
| `WARN` | 可预期的业务失败（认证失败、文件不存在、限速触发）、降级处理 |
| `INFO` | 关键业务节点：方法入参、出参、重要状态变更（连接建立/关闭、上传完成）|
| `DEBUG` | 详细内部流程（帧解析、buffer 读写细节），生产环境关闭 |

---

## 必须打印日志的场景

### 1. Service 公有方法：入参 + 出参 + 异常

```java
public FileInfoDTO uploadFile(FileUploadParam param) {
    log.info("上传文件，入参：{}", param);
    try {
        FileInfoDTO result = doUpload(param);
        log.info("上传文件成功，fileId={}", result.getFileId());
        return result;
    } catch (Exception e) {
        log.error("上传文件失败，入参：{}，原因：{}", param, e.getMessage(), e);
        throw e;
    }
}
```

### 2. NIO 连接生命周期

```java
// 连接建立
log.info("新连接建立，channel={}, remoteAddr={}", channel, channel.getRemoteAddress());

// 连接关闭
log.info("连接关闭，channel={}, 原因={}", channel, reason);
```

### 3. 文件传输关键节点

```java
log.info("文件上传开始，userId={}, fileName={}, fileSize={}", userId, fileName, fileSize);
log.info("文件上传完成，userId={}, fileId={}, 耗时={}ms", userId, fileId, costMs);
log.info("断点续传恢复，userId={}, fileId={}, checkpoint={}", userId, fileId, checkpoint);
```

### 4. 限速触发

```java
log.warn("触发限速，channel={}, 当前速率={}bps, 限制={}bps", channel, currentRate, limitRate);
```

---

## 日志格式规范

- 使用 **占位符 `{}`**，禁止字符串拼接
- 消息使用**中文**，便于快速理解
- 带上关键 ID（`userId`、`fileId`、`channel`）方便排查

```java
// 正确
log.info("查询用户文件列表，userId={}, 文件数={}", userId, count);

// 错误：字符串拼接（性能差，且 DEBUG 关闭时仍会拼接）
log.info("查询用户文件列表，userId=" + userId + ", 文件数=" + count);
```

---

## 禁止打印的内容

- **密码/密码哈希** — 禁止在任何级别打印
- **Token/Session 完整值** — 最多打印前 8 位用于调试
- **文件完整内容** — 只打印文件名、大小、ID
- **用户敏感信息** — 手机号、身份证等脱敏后才可打印

```java
// 错误
log.info("用户登录，username={}, password={}", username, password);

// 正确
log.info("用户登录，username={}", username);
```

---

## 禁止用法

```java
// 禁止：使用 System.out.println
System.out.println("调试信息"); // ← 严禁，用 log.debug()

// 禁止：使用 e.printStackTrace()
catch (Exception e) {
    e.printStackTrace(); // ← 严禁，用 log.error("", e)
}

// 禁止：在循环内打印 INFO 级别日志（大量数据会刷爆日志）
for (FileDO file : files) {
    log.info("处理文件 {}", file.getId()); // ← 改为 DEBUG 或循环外汇总
}
```

---

## 异常日志写法

```java
// 正确：传入异常对象，SLF4J 自动打印堆栈
log.error("操作失败，userId={}, 原因：{}", userId, e.getMessage(), e);

// 错误：只打印 message，丢失堆栈
log.error("操作失败：" + e.getMessage());
```


## Java Rules Overlay - Logging and Sensitive Data

- 日志中禁止输出密码、token、密钥、完整个人敏感信息；必要时仅打印脱敏标识与主键。
- 错误日志最少包含：方法/链路名、关键业务 ID、失败原因；可定位优先于“仅一句失败”。
- 远程调用失败需记录：接口名、请求摘要、返回码/异常摘要；但不得落库敏感载荷全文。
