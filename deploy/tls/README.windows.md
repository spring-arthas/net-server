# Windows TLS 配置

本文件只描述 Windows 本地环境。现有 macOS/Linux 的 `generate-local-certs.sh` 和
`run-net-server-zulu8.sh` 不需要修改。

## 前置条件

- JDK 8
- Maven
- Git for Windows（提供 OpenSSL 3）

## 生成本机证书

先确认本机需要监听的 IPv4 地址，然后在项目根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\tls\generate-local-certs-windows.ps1 `
  -PublicIp 192.168.0.102
```

默认输出目录为：

```text
%USERPROFILE%\.net-server\tls
```

脚本会生成 Java 8 可读取的 PKCS12，并设置当前 Windows 用户的：

- `NET_SERVER_PUBLIC_IP`
- `NET_SERVER_TLS_KEYSTORE`

如果使用 IntelliJ，请在生成证书后重启 IntelliJ，使新的用户环境变量生效。

直接执行 `java -jar` 且未设置 `NET_SERVER_TLS_KEYSTORE` 时，程序会根据
`os.name` 自动读取 `TLS.GATEWAY.KEYSTORE.PATH.WINDOWS`。macOS 和 Linux 分别读取
各自的 `.MACOS`、`.LINUX` 配置，旧的 `TLS.GATEWAY.KEYSTORE.PATH` 仅作为兼容回退。

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
