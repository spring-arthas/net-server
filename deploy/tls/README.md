# net-server 内置 TLS 部署

`net-server` 自己监听公网 TLS 端口并转发到同一 JVM 内的回环后端，不再启动 HAProxy。

当前本地环境：

- 公网/局域网 IP：`172.21.32.64`
- PKCS12：`/Users/hljy/.net-server/tls-strict-20260810/net-server.p12`
- 本地开发密码：空字符串
- CA：`/Users/hljy/.net-server/tls-strict-20260810/chat-storage-local-ca.crt`

单进程启动命令：

```bash
export NET_SERVER_PUBLIC_IP=172.21.32.64
export NET_SERVER_TLS_KEYSTORE=/Users/hljy/.net-server/tls-strict-20260810/net-server.p12
export NET_SERVER_TLS_KEYSTORE_PASSWORD=
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home \
  scripts/run-net-server-zulu8.sh
```

四个公网端口保持不变：`10086`、`10087`、`10088`、`10188`。客户端证书、地址、帧协议都不改。

需要重新生成本地证书时：

```bash
NET_SERVER_TLS_KEYSTORE_PASSWORD= \
  deploy/tls/generate-local-certs.sh \
  /Users/hljy/.net-server/tls-strict-20260810 \
  172.21.32.64
```

脚本仍保留 PEM、CRT、KEY 和 DER 文件，便于检查证书链和回滚。
