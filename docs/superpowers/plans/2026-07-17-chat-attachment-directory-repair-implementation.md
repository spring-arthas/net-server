# 聊天附件目录归属与历史数据恢复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让聊天附件始终保存到认证用户的隐藏附件目录，并安全迁移家校测试库中现有 29 条非法父目录记录及对应物理文件。

**Architecture:** chat-storage 在现有上传元数据中标识 `CHAT_ATTACHMENT`；net-server 使用传输令牌身份解析或创建隐藏目录，并在上传上下文建立前把真实目录 ID 写回请求。历史迁移使用可回滚脚本保留 fileId，移动文件后事务更新数据库，并执行引用、路径和大小校验。

**Tech Stack:** Swift 5/SwiftUI、Java 8、Maven、Spring XML、MyBatis、MySQL 8、macOS 文件系统

---

### Task 1: 固化服务端聊天附件目录行为

**Files:**
- Modify: `src/main/java/com/alibaba/server/nio/model/file/request/FileUploadRequest.java`
- Modify: `src/main/java/com/alibaba/server/nio/repository/file/service/FileService.java`
- Modify: `src/main/java/com/alibaba/server/nio/repository/file/service/impl/FileServiceImpl.java`
- Modify: `src/main/java/com/alibaba/server/nio/service/file/handler/FileUploadHandler.java`
- Test: `src/test/java/com/alibaba/server/nio/repository/file/service/impl/FileServiceChatAttachmentDirectoryTest.java`

- [x] **Step 1: 编写失败测试**：覆盖首次创建、重复复用、用户隔离、隐藏目录不进入目录树、系统目录禁止改名/移动/删除。
- [x] **Step 2: 使用 Zulu JDK 8 运行测试并确认因接口尚未实现而失败。**
- [x] **Step 3: 实现 `ensureChatAttachmentDirectory` 和统一的上传目录解析，给 `FileUploadRequest` 增加可选 `uploadPurpose`。**
- [x] **Step 4: 让断点检查和元数据初始化共同使用解析后的正数目录 ID。**
- [x] **Step 5: 运行相关测试和现有上传目录测试。**

### Task 2: 让 chat-storage 标识聊天附件用途

**Files:**
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/DirectoryService.swift`
- Modify: `/Users/hljy/macProjects/chat-storage/chat-storage/Services/Chat/ChatAttachmentUploadService.swift`
- Test: `/Users/hljy/macProjects/chat-storage/chat-storageTests/chat_storageTests.swift`

- [x] **Step 1: 编写失败测试**：断言聊天原图、缩略图和预览图均使用 `CHAT_ATTACHMENT`，普通上传默认 `CLOUD_FILE`。
- [x] **Step 2: 给上传方法和 `FileMetaRequest` 增加用途参数，并同时写入 resume-check 字典与 meta-frame。**
- [x] **Step 3: 移除聊天派生图使用 `-1` 的逻辑，所有聊天附件由服务端解析目录。**
- [x] **Step 4: 运行 Swift 相关测试或至少执行 Xcode 编译检查。**

### Task 3: 编写并演练历史数据迁移

**Files:**
- Create: `sql/repair_chat_attachment_parent_ids_20260717.sql`
- Create: `scripts/repair_chat_attachment_files_20260717.sh`

- [x] **Step 1: 生成只读盘点和数据库备份，固定 29 个 fileId、5 个消息 ID 及新旧路径。**
- [x] **Step 2: 脚本 dry-run 校验文件存在、文件类型、大小、用户和根目录，不执行移动或 UPDATE。**
- [x] **Step 3: 为用户 7 和 15 创建或复用隐藏目录记录，生成正数且唯一的目录 ID。**
- [x] **Step 4: 备份损坏预览图并从对应原图重新生成可解码 JPEG。**
- [x] **Step 5: 移动物理文件，事务更新 file 记录；失败时按清单回滚文件。**
- [x] **Step 6: 同步损坏预览图的新大小到消息 JSON，保留所有 fileId。**

### Task 4: 完整验证

**Files:**
- Verify: `src/test/java`
- Verify: `/Users/hljy/macProjects/chat-storage/chat-storageTests`

- [x] **Step 1: 查询确认有效文件不存在 `parent_id IN (0,-1)` 或 `user_id IS NULL` 的聊天附件。**
- [x] **Step 2: 对 29 条记录逐一校验文件存在、大小一致、路径位于认证用户根目录。**
- [x] **Step 3: 校验消息 225..229 的全部 fileId、thumbnailFileId、previewFileId 均可查询。**
- [x] **Step 4: 使用 `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home` 执行 `mvn test` 与 `mvn -DskipTests package`。**
- [x] **Step 5: 汇总迁移数量、目录 ID、损坏文件恢复结果和剩余风险。**
