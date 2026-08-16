# Windows 上传存储目录

## 目标

- Windows 上传根目录固定为 \`E:\storage\upload\file\`。
- Mac/Linux 继续读取独立的 \`NIO.FILE.BASE.PATH.LINUX.MAC\` 配置。
- 目录不存在时由服务自动创建，不能因目录缺失导致上传失败。

## 验收

- Windows 与 Mac/Linux 配置保持独立。
- 本机目标目录存在且可写。
- 配置测试、构建和服务重启通过。
