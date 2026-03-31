# Directory Structure

> 本项目后端代码组织方式（Java 8 + NIO + Spring XML + MyBatis-Plus）

---

## 技术栈

- Java 8 + Java NIO（原生 Selector/Channel）
- Spring（XML 配置，非 Spring Boot）+ MyBatis-Plus
- Druid 连接池 + MySQL
- Lombok + SLF4J/Log4j
- Maven（maven-shade-plugin 打包 fat jar）

---

## 顶层包结构

```
com.alibaba.server/
├── NetServer.java                  # 主入口，启动 NioServerContext
├── nio/
│   ├── acceptor/                   # 各端口 Acceptor 线程
│   ├── selector/                   # Selector 事件循环线程
│   ├── handler/
│   │   ├── event/                  # EventHandlerContext + 具体 EventHandler
│   │   │   └── concret/            # ReadEventHandler, WriteEventHandler
│   │   ├── pipe/                   # ChannelPipeLine（DefaultChannelPipeLine）
│   │   └── worker/                 # Worker 线程池
│   ├── service/
│   │   ├── api/                    # ChannelHandler 接口 + AbstractChannelHandler 基类
│   │   ├── chat/                   # 聊天消息处理
│   │   ├── file/                   # 文件上传/下载/断点续传/目录操作
│   │   └── ratelimit/              # 令牌桶限速（TokenBucketRateLimiter）
│   ├── model/                      # 核心数据模型
│   │   ├── ChannelEventModel
│   │   ├── TransportDataModel
│   │   ├── SocketChannelContext
│   │   └── file/                   # FileUploadContext, FileDownloadContext
│   ├── repository/                 # 持久层（user / file 两大模块）
│   │   ├── user/
│   │   │   ├── mapper/             # MyBatis Mapper 接口
│   │   │   ├── dataobject/         # DO（数据库实体）
│   │   │   ├── repository/         # Repository 层（封装 Mapper + converter）
│   │   │   └── service/            # Service 层（param / dto / impl）
│   │   └── file/
│   │       ├── mapper/
│   │       ├── dataobject/
│   │       ├── repository/
│   │       └── service/
│   └── client/                     # 内置调试客户端（不参与生产逻辑）
│       ├── UserAuthClient
│       ├── FileUploadClient
│       ├── FileDownloadClient
│       └── DirectoryClient
```

---

## 多端口 Reactor 架构

每个功能对应一个独立端口，各端口运行独立 Acceptor 线程 + Selector 线程：

| 端口  | Acceptor                    | 用途             |
|-------|----------------------------|------------------|
| 10086 | TextTransmissionAcceptor    | 文本/聊天通道     |
| 10087 | MainFileUploadAcceptor      | 文件上传          |
| 10088 | MainFileDownloadAcceptor    | 文件下载          |
| 10089 | ResumeUploadAcceptor        | 断点续传上传      |
| 10090 | ResumeDownloadAcceptor      | 断点续传下载      |

---

## 持久层分层规范

每个业务模块（`user`、`file`）严格遵循四层结构：

```
repository/{module}/
  ├── mapper/         MyBatis Mapper 接口，只做 SQL 映射
  ├── dataobject/     DO 类，字段与数据库列一一对应
  ├── repository/     封装 Mapper，含 DO↔BO converter
  └── service/        业务 Service，含 param/dto/impl
```

- **mapper** — 只允许写 SQL，不含业务判断
- **dataobject** — 只放数据库映射字段，不含业务方法
- **repository** — 封装 mapper，提供 BO 级返回，负责 converter 转换
- **service** — 所有业务逻辑入口，参数用 param 对象，返回用 dto 对象

---

## 命名规范

| 类型 | 命名规则 | 示例 |
|------|----------|------|
| 包名 | 小写点分隔 | `com.alibaba.server.nio.service.file` |
| 类名 | UpperCamelCase | `FileUploadContext` |
| 方法名 | lowerCamelCase，动词开头 | `uploadFile()`, `findByUserId()` |
| 常量 | UPPER_SNAKE_CASE | `MAX_BUFFER_SIZE` |
| DO 类 | 以 DO 结尾 | `UserDO`, `FileDO` |
| DTO 类 | 以 DTO 结尾 | `FileInfoDTO` |
| Param 类 | 以 Param 结尾 | `FileUploadParam` |
| Mapper 接口 | 以 Mapper 结尾 | `UserMapper` |
| Repository 类 | 以 Repository 结尾 | `FileRepository` |
| Service 接口 | 以 Service 结尾 | `FileService` |
| Service 实现 | 以 ServiceImpl 结尾 | `FileServiceImpl` |

---

## 配置文件位置

```
src/main/resources/
├── server.properties           # 运行时参数（端口、路径、限速等）
├── spring/
│   └── applicationContext.xml  # Spring Bean / 数据源 / MyBatis / 事务
├── log4j.properties            # 日志配置
└── logback-spring.xml          # 日志配置（备用）
```
