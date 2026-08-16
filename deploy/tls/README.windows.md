# Windows TLS 配置

本文件只描述 Windows 本地环境。

## 前置条件

- JDK 8
- Maven
- Git for Windows（仅手工生成自定义证书时需要 OpenSSL 3）

## 自动生成本机证书

默认无需先执行证书脚本，也无需设置 IP。启动时 Java 会自动选择当前有效物理网卡，
并在以下目录创建本机 CA 和 PKCS12：

```text
%USERPROFILE%\.net-server\tls
```

首次启动后，需要在客户端信任 `chat-storage-local-ca.crt` 或
`chat-storage-local-ca.der`。IP 变化时程序复用该 CA，只重新签发服务端证书。

显式设置以下环境变量时，环境变量仍具有最高优先级：

- `NET_SERVER_PUBLIC_IP`
- `NET_SERVER_TLS_KEYSTORE`
- `NET_SERVER_TLS_KEYSTORE_PASSWORD`
- `NET_SERVER_TLS_KEYSTORE_AUTO_CREATE`

如需手工维护自定义证书，仍可执行 `generate-local-certs-windows.ps1`。已有自定义
PKCS12 不会被自动覆盖。

## 构建和启动

```powershell
mvn clean package -DskipTests
powershell -ExecutionPolicy Bypass -File .\scripts\run-net-server-windows.ps1
```

## 本机数据库配置

数据库凭据不写入仓库。请创建：

```text
%USERPROFILE%\.net-server\database.properties
```

文件格式：

```properties
url=jdbc:mysql://127.0.0.1:3306/net-server?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false
username=本机MySQL用户名
password=本机MySQL密码
```

也可以使用 Windows 用户环境变量覆盖：

- `NET_SERVER_DB_URL`
- `NET_SERVER_DB_USERNAME`
- `NET_SERVER_DB_PASSWORD`
