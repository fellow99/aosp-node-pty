#!/usr/bin/env bash
# scripts/build-pty-android.sh —— 交叉编译 node-pty 的 UNIX 目标为 Android arm64 libpty_node.so
#
# 固化 docs/000-工程规划.md §3.4 的踩坑（不是"注意事项"，是脚本本体）：
#   1) 异常模型跟随 node-pty 源码：其 binding.gyp 用 node_addon_api_except，
#      故 -fexceptions + -DNAPI_CPP_EXCEPTIONS（pty.cc 裸 throw Napi::Error）；
#      不定义 NODE_ADDON_API_ENABLE_MAYBE（node-pty 不用 Maybe API）。
#   2) 必须产生 DT_NEEDED libnode.so：-Wl,--no-as-needed ... -Wl,-l:libnode.so
#   3) 输出命名 libpty_node.so（Android 打包/System.loadLibrary 只认 lib*.so）
#   4) Node 头文件版本必须 == 配对 runtime（NODE_MODULE_VERSION 一致）
#   5) bionic 无 libutil：绝不链接 -lutil（forkpty/openpty 在 libc）
#
# 产物：<out>/libpty_node.so（strip）+ <out>/BUILD-METADATA.txt
# libnode.so 仅用于链接期符号解析，绝不打包进产物。
#
# 用法：
#   scripts/build-pty-android.sh \
#     --node 24.22.1 --api 24 \
#     --src   .dist/src/node-pty \
#     --addon .dist/src/node-addon-api \
#     --headers .dist/src/node-headers/include/node \
#     --libnode <path-to>/libnode.so \
#     --ndk "$ANDROID_NDK_HOME" \
#     --out build/out
set -euo pipefail

NODE_VERSION=""
ANDROID_API="24"
SRC_DIR=""
ADDON_DIR=""
HEADERS_DIR=""
LIBNODE=""
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
OUTPUT_DIR=""

usage() {
  cat <<'EOF'
Usage: build-pty-android.sh --node <ver> [--api 24] --src <node-pty dir>
       --addon <node-addon-api dir> --headers <node include/node dir>
       --libnode <libnode.so> [--ndk <dir>] --out <output dir>

Cross-compiles node-pty UNIX target (src/unix/pty.cc) into a single
Android arm64-v8a shared object named libpty_node.so.
The paired libnode.so is used at LINK time only and is NOT bundled.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --node) NODE_VERSION="$2"; shift 2;;
    --api) ANDROID_API="$2"; shift 2;;
    --src) SRC_DIR="$2"; shift 2;;
    --addon) ADDON_DIR="$2"; shift 2;;
    --headers) HEADERS_DIR="$2"; shift 2;;
    --libnode) LIBNODE="$2"; shift 2;;
    --ndk) NDK_DIR="$2"; shift 2;;
    --out) OUTPUT_DIR="$2"; shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "未知参数：$1" >&2; usage >&2; exit 2;;
  esac
done

[[ "$ANDROID_API" =~ ^[0-9]+$ ]] || { echo "Android API 必须为数字" >&2; exit 2; }
[[ -n "$SRC_DIR" && -n "$ADDON_DIR" && -n "$HEADERS_DIR" && -n "$LIBNODE" && -n "$OUTPUT_DIR" ]] || {
  echo "缺少必需参数（--src/--addon/--headers/--libnode/--out）" >&2; usage >&2; exit 2; }
[[ -n "$NDK_DIR" ]] || { echo "需要 --ndk 或 ANDROID_NDK_HOME/ANDROID_NDK_ROOT" >&2; exit 2; }

SRC_DIR="$(realpath "$SRC_DIR")"
ADDON_DIR="$(realpath "$ADDON_DIR")"
HEADERS_DIR="$(realpath "$HEADERS_DIR")"
LIBNODE="$(realpath "$LIBNODE")"
NDK_DIR="$(realpath "$NDK_DIR")"
OUTPUT_DIR="$(realpath -m "$OUTPUT_DIR")"

test -f "$SRC_DIR/src/unix/pty.cc" || { echo "缺 $SRC_DIR/src/unix/pty.cc" >&2; exit 1; }
test -f "$ADDON_DIR/napi.h" || { echo "缺 $ADDON_DIR/napi.h" >&2; exit 1; }
test -f "$HEADERS_DIR/node_api.h" || { echo "缺 $HEADERS_DIR/node_api.h（Node 头文件版本不对？）" >&2; exit 1; }
test -f "$LIBNODE" || { echo "缺链接期引擎 $LIBNODE" >&2; exit 1; }

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
[[ "$(uname -s)" == "Darwin" ]] && HOST_TAG="darwin-x86_64"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
CLANGXX="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++"
STRIP="$TOOLCHAIN/bin/llvm-strip"
test -x "$CLANGXX" || { echo "缺编译器 $CLANGXX（NDK/主机标签不对？）" >&2; exit 1; }
test -x "$STRIP" || STRIP="$(command -v llvm-strip || true)"
[[ -n "$STRIP" ]] || { echo "缺 llvm-strip" >&2; exit 1; }

# ── 编译（五条踩坑见文件头注释）──────────────────────────────────────────────
mkdir -p "$OUTPUT_DIR"
RAW_OUT="$OUTPUT_DIR/libpty_node.so"

# node-pty 的 UNIX 主体单文件：src/unix/pty.cc（Android 走 forkpty，不编 spawn-helper）。
# include 顺序：node 头 → node-addon-api → node-pty 自身 src（pty.cc 内部相对 include 需要）。
#
# 异常模型（以 node-pty 1.1.0 源码为准，勿改）：其 binding.gyp 依赖
# node_addon_api_except → 定义 NAPI_CPP_EXCEPTIONS，pty.cc 内为裸 `throw Napi::Error`。
# 因此必须 -fexceptions + -DNAPI_CPP_EXCEPTIONS；禁用异常会导致编译失败/错误路径不抛。
# 不定义 NODE_ADDON_API_ENABLE_MAYBE（node-pty 不用 Maybe API）。
# NODE_GYP_MODULE_NAME：pty.cc 末尾 NODE_API_MODULE(NODE_GYP_MODULE_NAME, init) 所需模块名。
"$CLANGXX" -std=gnu++17 -fPIC -shared -O2 -fexceptions \
  -I"$HEADERS_DIR" \
  -I"$ADDON_DIR" \
  -I"$SRC_DIR/src" \
  -DNAPI_CPP_EXCEPTIONS \
  -DNODE_GYP_MODULE_NAME=pty_node \
  -DNAPI_VERSION=9 \
  -D_LARGEFILE_SOURCE -D_FILE_OFFSET_BITS=64 \
  "$SRC_DIR/src/unix/pty.cc" \
  -Wl,--no-as-needed \
  -L"$(dirname "$LIBNODE")" -Wl,-l:libnode.so \
  -o "$RAW_OUT"

RAW_SIZE=$(stat -c%s "$RAW_OUT" 2>/dev/null || stat -f%z "$RAW_OUT")

# strip：保留 .dynsym（napi_* 未定义引用与 NAPI 注册入口都在动态表，不受 strip 影响）。
"$STRIP" --strip-unneeded "$RAW_OUT"
STRIPPED_SIZE=$(stat -c%s "$RAW_OUT" 2>/dev/null || stat -f%z "$RAW_OUT")
SHA=$(sha256sum "$RAW_OUT" 2>/dev/null | awk '{print $1}' || shasum -a 256 "$RAW_OUT" | awk '{print $1}')

# ── 自检：DT_NEEDED 必须含 libnode.so（防止链接参数回归静默失效）──────────────
if command -v readelf >/dev/null 2>&1; then
  if ! readelf -d "$RAW_OUT" | grep -q 'NEEDED.*libnode\.so'; then
    echo "ERROR: 产物 DT_NEEDED 中没有 libnode.so（--no-as-needed/-l:libnode.so 未生效）" >&2
    exit 1
  fi
fi

cat >"$OUTPUT_DIR/BUILD-METADATA.txt" <<EOF
node_version=$NODE_VERSION
android_abi=arm64-v8a
android_api=$ANDROID_API
artifact=libpty_node.so
size=$STRIPPED_SIZE
size_unstripped=$RAW_SIZE
sha256=$SHA
ndk=$(basename "$NDK_DIR")
link_libnode=$(basename "$LIBNODE")
note=libnode.so is link-time only and is NOT bundled; provided at runtime by node-runtime
stripped=llvm-strip --strip-unneeded
EOF

echo "=== Build complete ==="
file "$RAW_OUT" 2>/dev/null || true
echo "libpty_node.so: ${RAW_SIZE} -> ${STRIPPED_SIZE} bytes"
echo "sha256=$SHA"
command -v readelf >/dev/null 2>&1 && { echo "--- NEEDED ---"; readelf -d "$RAW_OUT" | grep NEEDED || true; }
