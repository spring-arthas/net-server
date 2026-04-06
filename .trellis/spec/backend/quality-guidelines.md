# Quality Guidelines

> 后端代码质量标准（严格遵守）, 编码规范，禁止写法，代码风格等

---

## 构建与验证

```bash
# 构建（无测试框架，跳过测试）
mvn clean package -DskipTests

# 运行（主类 com.alibaba.server.NetServer，含远程调试端口 18181）
java -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=18181 -jar target/net-server-1.0-SNAPSHOT.jar
```

> 无 `mvn test` 可用测试。提交前须手动验证核心流程。

---

## 命名规范

| 类型 | 规则 |
|------|------|
| 包名 | 小写点分隔，如 `com.alibaba.server.nio.service.file` |
| 类名 | UpperCamelCase |
| 方法名 | lowerCamelCase，动词开头 |
| 常量 | UPPER_SNAKE_CASE |

---

## 代码格式

- 缩进：**4 空格**（禁止 tab）
- 大括号：**不换行（K&R 风格）**
- 行宽：**≤ 120 字符**

---

## 注释规范

- Service 层所有 `public` 方法必须写 **JavaDoc**
- 复杂业务逻辑写行内注释
- 所有注释使用**中文**

```java
/**
 * 查询用户的文件列表
 *
 * @param userId 用户ID
 * @return 文件DTO列表
 */
public List<FileInfoDTO> listFilesByUser(Long userId) {
    // 先校验用户是否存在
    ...
}
```

---

## 禁止写法（Forbidden Patterns）

### 1. 禁止裸 Exception

```java
// 错误
catch (Exception e) { ... }
throws Exception

// 正确：使用具体业务异常类
catch (FileNotFoundException e) { ... }
catch (UserAuthException e) { ... }
```

### 2. 禁止空 catch 块

```java
// 错误
try {
    doSomething();
} catch (IOException e) {
    // 什么都不做
}

// 正确：至少打印日志
try {
    doSomething();
} catch (IOException e) {
    log.error("操作失败，原因：{}", e.getMessage(), e);
    throw new BusinessException("操作失败", e);
}
```

### 3. 禁止直接在 Selector 线程执行耗时操作

- Selector 线程只做 I/O 事件分发
- 所有业务逻辑必须提交到 Worker 线程池执行

### 4. 禁止跨层直接调用 Mapper

- Service 层不得直接调用 Mapper，必须经过 Repository 层

### 5. 禁止在 DO 类中写业务方法

- DO 只存放数据库字段映射，转换逻辑放在 converter

---

## 必须遵守的写法（Required Patterns）

### 1. 关键流程必须打印入参/出参/异常

```java
public FileInfoDTO uploadFile(FileUploadParam param) {
    log.info("上传文件，入参：{}", param);
    try {
        FileInfoDTO result = doUpload(param);
        log.info("上传文件成功，出参：{}", result);
        return result;
    } catch (Exception e) {
        log.error("上传文件失败，入参：{}，原因：{}", param, e.getMessage(), e);
        throw e;
    }
}
```

### 2. Service 公有方法写 JavaDoc（中文）

### 3. 常量统一定义，禁止魔法数字/字符串

```java
// 错误
if (status == 1) { ... }

// 正确
private static final int STATUS_ACTIVE = 1;
if (status == STATUS_ACTIVE) { ... }
```

### 4. 使用 Lombok 减少样板代码

- DO/DTO/Param 类使用 `@Data`、`@Builder`、`@NoArgsConstructor`、`@AllArgsConstructor`

---

## 提交规范

```bash
git commit -m "type(scope): description"
```

**type**: `feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `perf`
**scope**: 模块名，如 `auth`、`file`、`upload`、`TextTransmissionHandler`

**示例**:
```
feat(file): 新增断点续传上传功能
fix(auth): 修复二次登录丢失历史数据问题
perf(TextTransmissionHandler): 优化好友列表N+1查询
```

---

## Code Review 检查清单

- [ ] 无裸 `Exception`，无空 catch 块
- [ ] Service 公有方法有 JavaDoc（中文）
- [ ] 关键流程打印了入参/出参/异常日志
- [ ] 无魔法数字/字符串
- [ ] 未在 Selector 线程执行耗时操作
- [ ] Service 层未直接调用 Mapper
- [ ] 行宽 ≤ 120 字符，缩进 4 空格
