# Database Guidelines

> MyBatis-Plus + MySQL 数据库操作约定

---

## 技术选型

- ORM：**MyBatis-Plus**（继承 `BaseMapper<T>`）
- 连接池：**Druid**
- 数据库：**MySQL**
- 事务管理：Spring XML 声明式事务（`applicationContext.xml`）

---

## 四层结构（必须遵守）

```
repository/{module}/
  ├── mapper/       MyBatis Mapper 接口（继承 BaseMapper<DO>）
  ├── dataobject/   DO 类（与数据库表一一对应）
  ├── repository/   Repository 层（封装 Mapper + DO↔BO converter）
  └── service/      Service 层（param / dto / impl）
```

---

## 各层规范

### Mapper 层

- 继承 `BaseMapper<XxxDO>`，优先使用 MyBatis-Plus 内置 CRUD 方法
- 复杂 SQL 在 Mapper XML 中定义，或使用注解（`@Select`/`@Update`）
- **禁止**在 Mapper 中写业务判断逻辑

```java
@Mapper
public interface UserMapper extends BaseMapper<UserDO> {
    // 简单查询直接用 MyBatis-Plus 内置方法，无需自定义
    // 复杂查询才自定义：
    List<UserDO> selectFriendsByUserId(@Param("userId") Long userId);
}
```

### DataObject（DO）层

- 字段与数据库列一一对应，使用 `@TableName`、`@TableId`、`@TableField` 注解
- 只放数据库映射字段，**禁止**写业务方法
- 使用 Lombok `@Data`

```java
@Data
@TableName("t_user")
public class UserDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private LocalDateTime createTime;
}
```

### Repository 层

- 封装 Mapper，对外返回 BO（或 DO，视复杂度而定）
- 负责 DO ↔ BO 的 converter 转换
- **禁止**在此层写核心业务逻辑

```java
@Repository
public class UserRepository {
    @Autowired
    private UserMapper userMapper;

    public UserBO findById(Long userId) {
        UserDO userDO = userMapper.selectById(userId);
        return UserConverter.toBO(userDO);
    }
}
```

### Service 层

- 所有业务逻辑入口
- 方法参数使用 `XxxParam` 对象，返回使用 `XxxDTO` 对象
- 接口定义在 `service/` 下，实现类命名为 `XxxServiceImpl`

```java
public interface FileService {
    FileInfoDTO getFileInfo(FileQueryParam param);
}
```

---

## 查询规范

### 避免 N+1 查询

- **禁止**在循环中执行数据库查询
- 使用批量查询 + 内存聚合替代循环查询

```java
// 错误（N+1）
for (Long friendId : friendIds) {
    UserDO user = userMapper.selectById(friendId); // 每次都查一次DB
}

// 正确（批量查询）
List<UserDO> friends = userMapper.selectBatchIds(friendIds);
Map<Long, UserDO> friendMap = friends.stream()
    .collect(Collectors.toMap(UserDO::getId, u -> u));
```

### 使用 QueryWrapper

```java
QueryWrapper<FileDO> wrapper = new QueryWrapper<>();
wrapper.eq("user_id", userId)
       .eq("status", FileStatus.ACTIVE)
       .orderByDesc("create_time");
List<FileDO> files = fileMapper.selectList(wrapper);
```

---

## 事务规范

- 事务在 Service 层管理，使用 Spring 声明式事务注解 `@Transactional`
- 只读操作加 `@Transactional(readOnly = true)`
- 事务方法中的异常必须让其向上抛出，不要在事务方法内 catch 后吞掉

```java
@Transactional(rollbackFor = Exception.class)
public void moveFile(FileMoveParam param) {
    // 多步操作，任一失败则全部回滚
    fileRepository.updateFilePath(param.getFileId(), param.getNewPath());
    directoryRepository.updateChildCount(param.getOldDirId(), -1);
    directoryRepository.updateChildCount(param.getNewDirId(), +1);
}
```

---

## 命名约定

| 对象 | 命名规则 | 示例 |
|------|----------|------|
| 表名 | `t_` 前缀 + 下划线小写 | `t_user`, `t_file_info` |
| 列名 | 下划线小写 | `user_id`, `create_time` |
| DO 类 | `XxxDO` | `UserDO`, `FileDO` |
| Mapper | `XxxMapper` | `UserMapper` |
| Repository | `XxxRepository` | `FileRepository` |
| Service 接口 | `XxxService` | `FileService` |
| Service 实现 | `XxxServiceImpl` | `FileServiceImpl` |
| Param 类 | `XxxParam` | `FileUploadParam` |
| DTO 类 | `XxxDTO` | `FileInfoDTO` |

---

## 常见错误

- 在循环中查询数据库（N+1 问题）
- Service 层直接调用 Mapper（跳过 Repository 层）
- DO 类中写业务方法
- 事务方法内 catch 异常后不重新抛出（导致事务不回滚）
- 未使用批量操作处理大数据集（应使用 `insertBatchSomeColumn` 或分批处理）


## Java Rules Overlay - Database and Security

- 严禁字符串拼接 SQL；必须使用参数化查询（`PreparedStatement` / MyBatis 参数绑定 / 框架参数化 API）。
- 所有边界输入先校验再入库：必填、长度、枚举取值、数值范围；校验失败返回可读错误，不写脏数据。
- 多表联动写入必须显式事务边界；批量写入优先批处理，禁止可批量场景下循环逐条写库。
- 定期执行依赖安全扫描（如 OWASP Dependency-Check / Snyk）并修复高危漏洞。
