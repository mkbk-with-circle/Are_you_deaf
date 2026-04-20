#!/usr/bin/env bash
# 一键打 Android 可离线安装的 APK（debug 签名，适合发给朋友侧载安装）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# 默认先 clean 再打包，避免 KSP 增量生成偶发 FileAlreadyExists 失败；加 --fast 可跳过 clean
GRADLE_TASKS=(":app:assembleDebug")
if [[ "${1:-}" == "--fast" ]]; then
  echo ">>> 快速模式（跳过 clean）..."
else
  echo ">>> 清理并构建（避免 KSP 缓存冲突；要快可加参数 --fast）..."
  GRADLE_TASKS=(":app:clean" "${GRADLE_TASKS[@]}")
fi

# 从 app/build.gradle.kts 读取 versionName
VERSION="$(grep -E '^\s*versionName\s*=' app/build.gradle.kts | head -1 | sed 's/.*"\([^"]*\)".*/\1/')"
if [[ -z "$VERSION" ]]; then
  VERSION="unknown"
fi

echo ">>> 构建 Debug APK（versionName=$VERSION）..."
./gradlew "${GRADLE_TASKS[@]}"

SRC="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$SRC" ]]; then
  echo "错误: 未找到 $SRC" >&2
  exit 1
fi

OUT_DIR="$ROOT/dist"
mkdir -p "$OUT_DIR"
# 固定文件名，方便分享（每次覆盖同一路径）
DEST="$OUT_DIR/你尔多龙吗.apk"
cp -f "$SRC" "$DEST"

echo ""
echo "打包完成，可直接发给安卓手机离线安装："
echo "  $DEST"
echo ""
echo "对方手机需允许「安装未知来源/来自此来源的应用」；小米等机型建议再按 README 开自启动与精确闹钟。"
