#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home}"
JAVA_BIN="$JAVA_HOME/bin/java"
SOURCE_JAR="$ROOT_DIR/target/net-server-1.0-SNAPSHOT.jar"
RUNTIME_DIR="$ROOT_DIR/.runtime"
RUNTIME_JAR="$RUNTIME_DIR/net-server-1.0-SNAPSHOT-runtime.jar"
DEBUG_PORT="${DEBUG_PORT:-18181}"
JDWP="${JDWP:-true}"
DRY_RUN="${DRY_RUN:-false}"
NET_SERVER_PUBLIC_IP="${NET_SERVER_PUBLIC_IP:-}"
NET_SERVER_TLS_KEYSTORE="${NET_SERVER_TLS_KEYSTORE:-}"
NET_SERVER_TLS_KEYSTORE_PASSWORD="${NET_SERVER_TLS_KEYSTORE_PASSWORD:-}"

# [修改] TLS 入口和业务后端属于同一 Java 进程，启动前必须一次性校验入口参数。
if [[ -z "$NET_SERVER_PUBLIC_IP" ]]; then
  echo "缺少 NET_SERVER_PUBLIC_IP，无法监听 TLS 公网端口" >&2
  exit 1
fi

if [[ -z "$NET_SERVER_TLS_KEYSTORE" ]]; then
  echo "缺少 NET_SERVER_TLS_KEYSTORE，无法加载 TLS 证书" >&2
  exit 1
fi

if [[ ! -f "$NET_SERVER_TLS_KEYSTORE" ]]; then
  echo "TLS PKCS12 不存在: $NET_SERVER_TLS_KEYSTORE" >&2
  exit 1
fi

export NET_SERVER_PUBLIC_IP
export NET_SERVER_TLS_KEYSTORE
export NET_SERVER_TLS_KEYSTORE_PASSWORD

if [[ ! -x "$JAVA_BIN" ]]; then
  echo "Zulu JDK8 不存在或不可执行: $JAVA_BIN" >&2
  exit 1
fi

if [[ ! -f "$SOURCE_JAR" ]]; then
  echo "未找到服务 Jar: $SOURCE_JAR" >&2
  echo "请先执行: JAVA_HOME=$JAVA_HOME mvn -DskipTests package" >&2
  exit 1
fi

mkdir -p "$RUNTIME_DIR"
TMP_JAR="$RUNTIME_JAR.tmp.$$"
cp "$SOURCE_JAR" "$TMP_JAR"
mv "$TMP_JAR" "$RUNTIME_JAR"

if [[ "$DRY_RUN" == "true" ]]; then
  echo "runtime jar ready: $RUNTIME_JAR"
  exit 0
fi

# [修改] 参数数组始终保留 -jar，兼容 macOS Bash 3.2 在 set -u 下展开空数组。
JAVA_ARGS=("-jar" "$RUNTIME_JAR")
if [[ "$JDWP" == "true" ]]; then
  JAVA_ARGS=("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$DEBUG_PORT" "${JAVA_ARGS[@]}")
fi

exec "$JAVA_BIN" "${JAVA_ARGS[@]}"
