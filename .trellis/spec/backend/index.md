# Backend Development Guidelines

> Best practices for backend development in this project.

---

## Overview

This directory contains guidelines for backend development. Fill in each file with your project's specific conventions.

---

## 项目简介

本项目是基于 **Java 8 + Java NIO + Spring（XML配置）+ MyBatis** 开发的云盘服务端，实现了文件上传、下载、断点续传、目录管理、用户认证等功能，采用自实现的 Reactor 线程模型（非 Spring MVC，无 HTTP Controller 层）。

---

## 技术栈概览

- Java 8 + Java NIO（原生 Selector/Channel）
- Spring（XML 配置，非 Spring Boot）+ MyBatis-Plus
- Druid 连接池 + MySQL
- Lombok + SLF4J/Log4j
- Maven fat jar 打包

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | 包结构、Reactor 架构、持久层分层、命名规范 | Done |
| [Database Guidelines](./database-guidelines.md) | MyBatis-Plus 用法、四层结构、N+1 避免、事务规范 | Done |
| [Error Handling](./error-handling.md) | 业务异常类、禁止空 catch、NIO 层异常处理 | Done |
| [Quality Guidelines](./quality-guidelines.md) | 命名/格式/注释规范、禁止写法、提交规范 | Done |
| [Logging Guidelines](./logging-guidelines.md) | SLF4J+Log4j、日志级别、禁止打印敏感信息 | Done |
| [API Module](./api-module.md) | 自实现 NIO 协议、TransportDataModel、ChannelHandler 规范 | Done |

---

## Pre-Development Checklist

开始后端开发前必读：

- [ ] [Directory Structure](./directory-structure.md) — 确认文件放在正确的包/层
- [ ] [Database Guidelines](./database-guidelines.md) — 涉及数据库操作时必读
- [ ] [Error Handling](./error-handling.md) — 所有异常处理必须符合规范
- [ ] [Quality Guidelines](./quality-guidelines.md) — 代码风格、命名、注释
- [ ] [Logging Guidelines](./logging-guidelines.md) — 关键流程必须打印日志
- [ ] [API Module](./api-module.md) — 涉及 ChannelHandler / 协议设计时必读
