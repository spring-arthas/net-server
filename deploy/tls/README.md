# net-server 内置 TLS 部署

当前本地/局域网默认配置已关闭 TLS，服务直接监听明文 TCP。以下内容仅适用于将
`TLS.GATEWAY.ENABLED` 显式改为 `true` 的部署。

`net-server` 自己监听公网 TLS 端口并转发到同一 JVM 内的回环后端，不再启动 HAProxy。

默认启动不需要预先设置 TLS 环境变量。Java 会在每次启动时：

- 从当前有效物理网卡选择局域网 IPv4，排除回环、VPN 和常见虚拟网卡；
- 根据当前操作系统的 `user.home` 使用 `${user.home}/.net-server/tls/net-server.p12`；
- PKCS12 不存在时，在 JVM 内创建本机 CA 和服务端证书，不依赖 OpenSSL；
- IP 发生变化时复用本机 CA，只重新签发服务端证书；
- 已存在且不是程序自动管理的 PKCS12 不会被覆盖。

单进程启动命令：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home \
  scripts/run-net-server-zulu8.sh
```

四个公网端口和帧协议保持不变：`10086`、`10087`、`10088`、`10188`。

首次自动创建后，CA 文件位于：

```text
${user.home}/.net-server/tls/chat-storage-local-ca.crt
${user.home}/.net-server/tls/chat-storage-local-ca.der
```

需要在客户端信任其中一个 CA 文件。显式设置以下环境变量时，环境变量仍具有最高优先级：

- `NET_SERVER_PUBLIC_IP`
- `NET_SERVER_TLS_KEYSTORE`
- `NET_SERVER_TLS_KEYSTORE_PASSWORD`
- `NET_SERVER_TLS_KEYSTORE_AUTO_CREATE`

如需使用外部 OpenSSL 手工生成自定义证书，仍可执行：

```bash
NET_SERVER_TLS_KEYSTORE_PASSWORD= \
  deploy/tls/generate-local-certs.sh \
  "${HOME}/.net-server/tls-custom" \
  192.168.0.101
```

脚本仍保留 PEM、CRT、KEY 和 DER 文件，便于检查证书链和回滚。
