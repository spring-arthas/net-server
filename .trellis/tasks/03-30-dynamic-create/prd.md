# 新建动态功能

## 目标

新增一个 NIO 协议帧处理器，用于处理客户端用户新建动态的请求。

## 需求

- 动态支持纯文字内容，最多 500 个字符
- 动态支持上传图片，最多 9 张
- 图片存储复用现有文件上传机制
- 动态数据持久化到数据库

## 验收标准

- [ ] 新增 `TransportDataModel` 命令类型（CMD_DYNAMIC_CREATE）
- [ ] 新增 `DynamicCreateHandler`，继承 `AbstractChannelHandler`，注册到 Pipeline
- [ ] 文字内容超过 500 字符时返回业务错误
- [ ] 图片超过 9 张时返回业务错误
- [ ] 动态数据写入数据库（含 userId、文字内容、图片文件ID列表、创建时间）
- [ ] 耗时操作提交到 Worker 线程池，不阻塞 Selector 线程
- [ ] 关键流程打印入参/出参/异常日志

## 技术说明

- 遵循现有多端口 Reactor 架构，动态功能挂载在文本通道（port 10086）或新建独立端口（待确认）
- 持久层遵循四层结构：mapper → dataobject → repository → service
- 图片 ID 列表以 JSON 数组或逗号分隔字符串存储到数据库字段
- 业务异常使用 `DynamicException`（新建）
