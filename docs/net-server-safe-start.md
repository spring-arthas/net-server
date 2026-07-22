# net-server 安全启动方式

## 背景

不要在开发调试时直接运行 `target/net-server-1.0-SNAPSHOT.jar`。

该文件是 Maven 的构建产物，执行 `mvn package` 时会被重新生成。JDK8 运行中的 JVM 会继续从启动 Jar 中按需读取 class/resource，如果服务运行期间该 Jar 被覆盖，后续延迟类加载可能在 native `libzip` 中触发 `SIGBUS`，导致进程直接退出。

本次聊天发送崩溃的现场就是：

- 服务通过 `java -jar target/net-server-1.0-SNAPSHOT.jar` 启动；
- 聊天发送链路调用 `UserFriendMessageService.saveMessage(...)`；
- Spring AOP 延迟加载 `org/springframework/aop/MethodBeforeAdvice.class`；
- JVM 在 `java.util.zip.ZipFile.getEntry` / `libzip.dylib newEntry` 读取启动 Jar 时崩溃。

## 推荐启动命令

先编译：

```bash
cd /Users/hljy/IdeaProjects/code/net-server
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home mvn -DskipTests package
```

再启动：

```bash
cd /Users/hljy/IdeaProjects/code/net-server
./scripts/run-net-server-zulu8.sh
```

脚本会把 `target/net-server-1.0-SNAPSHOT.jar` 复制到 `.runtime/net-server-1.0-SNAPSHOT-runtime.jar` 后再启动。之后即使重新编译覆盖 `target/` 下的 Jar，也不会影响当前运行中的服务进程。

## 可选参数

修改调试端口：

```bash
DEBUG_PORT=18182 ./scripts/run-net-server-zulu8.sh
```

不启用 JDWP：

```bash
JDWP=false ./scripts/run-net-server-zulu8.sh
```
