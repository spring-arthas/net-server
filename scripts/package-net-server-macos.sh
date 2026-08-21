#!/usr/bin/env bash
set -euo pipefail

# [修改] 使用 Java 8 编译项目，使用 Java 17 的 jpackage 生成带运行时的 macOS 应用。
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MAVEN_JAVA_HOME="${MAVEN_JAVA_HOME:-}"
PACKAGING_JAVA_HOME="${PACKAGING_JAVA_HOME:-}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/dist}"
PACKAGE_NAME="${PACKAGE_NAME:-net-server-macos-arm64-1.0}"
APP_NAME="${APP_NAME:-Net Server}"
APP_VERSION="${APP_VERSION:-1.0.0}"
PACKAGE_IDENTIFIER="${PACKAGE_IDENTIFIER:-com.alibaba.netserver}"
MAVEN_COMMAND="${MAVEN_COMMAND:-mvn}"
MAC_SIGN="${MAC_SIGN:-false}"
MAC_SIGNING_KEY_USER_NAME="${MAC_SIGNING_KEY_USER_NAME:-}"

find_java_home() {
  local version="$1"
  if [[ -x /usr/libexec/java_home ]]; then
    /usr/libexec/java_home -v "$version" 2>/dev/null || true
  fi
}

if [[ -z "$MAVEN_JAVA_HOME" ]]; then
  MAVEN_JAVA_HOME="$(find_java_home 1.8)"
fi
if [[ -z "$PACKAGING_JAVA_HOME" ]]; then
  PACKAGING_JAVA_HOME="$(find_java_home 17)"
fi

if [[ -z "$MAVEN_JAVA_HOME" || ! -x "$MAVEN_JAVA_HOME/bin/java" ]]; then
  echo "未找到 Java 8，请设置 MAVEN_JAVA_HOME。" >&2
  exit 1
fi
if [[ -z "$PACKAGING_JAVA_HOME" || ! -x "$PACKAGING_JAVA_HOME/bin/jpackage" ]]; then
  echo "未找到带 jpackage 的 Java 17，请设置 PACKAGING_JAVA_HOME。" >&2
  exit 1
fi
if ! command -v "$MAVEN_COMMAND" >/dev/null 2>&1; then
  echo "未找到 Maven: $MAVEN_COMMAND" >&2
  exit 1
fi

SOURCE_JAR="$ROOT_DIR/target/net-server-1.0-SNAPSHOT.jar"
PACKAGE_PATH="$OUTPUT_DIR/$PACKAGE_NAME.dmg"
JPACKAGE_OUTPUT="$OUTPUT_DIR/$APP_NAME-$APP_VERSION.dmg"
STAGING_DIR="$(mktemp -d "${TMPDIR:-/tmp}/net-server-package.XXXXXX")"
trap 'rm -rf "$STAGING_DIR"' EXIT

mkdir -p "$OUTPUT_DIR"

echo "使用 Java 8 编译 net-server..."
export JAVA_HOME="$MAVEN_JAVA_HOME"
"$MAVEN_COMMAND" -DskipTests package

if [[ ! -s "$SOURCE_JAR" ]]; then
  echo "构建完成但未找到有效 Jar: $SOURCE_JAR" >&2
  exit 1
fi

cp "$SOURCE_JAR" "$STAGING_DIR/net-server.jar"

if [[ -e "$PACKAGE_PATH" || -e "$JPACKAGE_OUTPUT" ]]; then
  echo "输出文件已存在，为避免覆盖用户文件而停止: $PACKAGE_PATH 或 $JPACKAGE_OUTPUT" >&2
  echo "如需重新打包，请先移走已有文件或设置新的 PACKAGE_NAME/OUTPUT_DIR。" >&2
  exit 1
fi

echo "使用 $PACKAGING_JAVA_HOME/bin/jpackage 生成 macOS DMG..."
JPACKAGE_ARGS=( \
  --type dmg \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --vendor "Veneno" \
  --description "Net Server cloud storage backend" \
  --input "$STAGING_DIR" \
  --main-jar net-server.jar \
  --main-class com.alibaba.server.NetServer \
  --dest "$OUTPUT_DIR" \
  --mac-package-identifier "$PACKAGE_IDENTIFIER" \
  --mac-package-name "$APP_NAME" \
  --java-options "-Dfile.encoding=UTF-8" \
)

if [[ "$MAC_SIGN" == "true" ]]; then
  if [[ -z "$MAC_SIGNING_KEY_USER_NAME" ]]; then
    echo "MAC_SIGN=true 时必须设置 MAC_SIGNING_KEY_USER_NAME。" >&2
    exit 1
  fi
  JPACKAGE_ARGS+=(
    --mac-sign
    --mac-signing-key-user-name "$MAC_SIGNING_KEY_USER_NAME"
  )
fi

"$PACKAGING_JAVA_HOME/bin/jpackage" "${JPACKAGE_ARGS[@]}"

if [[ ! -s "$JPACKAGE_OUTPUT" ]]; then
  echo "jpackage 未生成预期文件: $JPACKAGE_OUTPUT" >&2
  exit 1
fi

mv "$JPACKAGE_OUTPUT" "$PACKAGE_PATH"

echo "DMG 已生成: $PACKAGE_PATH"
