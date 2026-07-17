# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

# 一、项目规范文档

详细规范已拆分至 `.trellis/spec/backend/`，开发前必读：

| 文档 | 内容 |
|---|---|
| [index.md](.trellis/spec/backend/index.md) | 项目简介、技术栈概览 |
| [directory-structure.md](.trellis/spec/backend/directory-structure.md) | 包结构、Reactor 架构、持久层分层、配置文件、命名规范 |
| [api-module.md](.trellis/spec/backend/api-module.md) | 自实现 NIO 协议、TransportDataModel、ChannelHandler 规范 |
| [database-guidelines.md](.trellis/spec/backend/database-guidelines.md) | MyBatis-Plus 四层结构、N+1 避免、事务规范 |
| [error-handling.md](.trellis/spec/backend/error-handling.md) | 业务异常类、禁止空 catch、NIO 层异常处理 |
| [logging-guidelines.md](.trellis/spec/backend/logging-guidelines.md) | SLF4J+Log4j、日志级别、禁止打印敏感信息 |
| [quality-guidelines.md](.trellis/spec/backend/quality-guidelines.md) | 命名/格式/注释规范、构建运行、禁止写法、提交规范 |

---

# 二、沟通偏好

- **所有回复必须使用中文**，包括 trellis slash commands 执行后的输出
- 简洁直接，优先给结论
- 直接给可运行完整代码，不给片段
- 禁止反问
