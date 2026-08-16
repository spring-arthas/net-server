# 登录会话协议一致性修复设计

## 目标

修复 Android 点击登录后服务端已经完成密码校验、客户端却仍停留在登录页且缺少明确反馈的问题。

## 已确认原因

1. 服务端的 `SessionTokenFactory` 目前是懒初始化。若 `USER_SESSION_TOKEN_SECRET` 未配置，服务仍可监听 10086，直到登录响应生成 `sessionToken` 时才抛异常。
2. Android 登录流程会先发布 `Authenticated`，随后全局连接保持逻辑才检查 `sessionToken`。旧服务端或异常响应缺少该字段时，登录态会被立即清除，用户看到的结果接近“点击后无反应”。

## 设计

### 服务端

- 在 `BasicServer.startupBasicServer()` 完成配置加载后立即初始化 `SessionTokenFactory`。
- 密钥解析顺序为：环境变量、仓库外显式配置、本机持久化密钥。
- 环境变量和配置都为空时，首次启动使用 `SecureRandom` 生成 32 字节随机值，写入
  `~/.net-server/user-session-token.secret`，后续启动复用同一个值。
- 本机密钥文件尽量收紧为仅当前用户可读写；生成或读取失败时才阻止网络端口启动。
- 不恢复公共默认密钥，不将随机值写入仓库或日志。

### Android

- `AuthRepository.login()` 在持久化用户和发布 `Authenticated` 之前校验 `sessionToken` 非空。
- 响应缺少会话令牌时清除残留登录态、关闭已经绑定用户的控制连接，并抛出带稳定错误码的 `AuthException`。
- Compose 登录页继续使用现有错误展示逻辑，不增加新的 UI 状态或弹窗。

## 不在范围内

- 不改变心跳、重连、后台前台服务或网络监听策略。
- 不兼容缺少 `sessionToken` 的旧后端。
- 不修改聊天、文件传输、数据库或登录凭据保存规则。

## 验证

- Java 回归测试证明服务启动路径会预初始化会话令牌服务。
- Java 回归测试证明本机密钥首次生成、重复读取保持一致，并且工厂在缺省配置下使用该密钥。
- Android 单测证明缺少 `sessionToken` 的成功响应不会发布登录态，并会显示可诊断错误。
- Java 8 完成后端定向测试与打包。
- Android 完成相关单测和 Debug APK 构建；不进行模拟器或人工登录测试。
