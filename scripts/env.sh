# Shared settings for ArchDock's native build scripts (sourced).
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORK=${ARCHDOCK_BUILD_DIR:-$ROOT/third_party/build}
JNILIBS=$ROOT/app/src/main/jniLibs/arm64-v8a
mkdir -p "$WORK" "$JNILIBS"

ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
NDK_VERSION=${NDK_VERSION:-27.2.12479018}
NDK=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}
[ -d "$NDK" ] || { echo "NDK not found at $NDK (set ANDROID_NDK_HOME or install ndk;$NDK_VERSION)"; exit 1; }
API=${ANDROID_API:-31}
HOST_TAG=linux-x86_64
[ "$(uname -s)" = Darwin ] && HOST_TAG=darwin-x86_64
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/$HOST_TAG
export PATH=$TOOLCHAIN/bin:$PATH
export CC="$TOOLCHAIN/bin/aarch64-linux-android$API-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android$API-clang++"
export AR=$TOOLCHAIN/bin/llvm-ar
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
export STRIP=$TOOLCHAIN/bin/llvm-strip
export OBJCOPY=$TOOLCHAIN/bin/llvm-objcopy
export OBJDUMP=$TOOLCHAIN/bin/llvm-objdump
export READELF=$TOOLCHAIN/bin/llvm-readelf
# Tensor G4: Cortex-X4 + A720 + A520 (all Armv9.2). ARCHDOCK_CPU=generic for other phones.
ARCHDOCK_CPU=${ARCHDOCK_CPU:-cortex-x4}
if [ "$ARCHDOCK_CPU" = generic ]; then CPU_FLAGS="-march=armv8-a"; else CPU_FLAGS="-mcpu=$ARCHDOCK_CPU"; fi
OPT_FLAGS="-O3 $CPU_FLAGS"
JOBS=${JOBS:-$(nproc 2>/dev/null || echo 4)}

fetch_git() { # url dir rev
  if [ ! -d "$2/.git" ]; then git clone -q "$1" "$2"; fi
  git -C "$2" fetch -q --depth 1 origin "$3" 2>/dev/null || git -C "$2" fetch -q origin
  git -C "$2" checkout -q --force "$3"
}
fetch_tar() { # url sha256 dest-dir
  local f=$WORK/$(basename "$1")
  [ -f "$f" ] || curl -fL --retry 3 -o "$f" "$1"
  echo "$2  $f" | sha256sum -c - >/dev/null || { echo "checksum mismatch for $f"; rm -f "$f"; exit 1; }
  rm -rf "$3"; mkdir -p "$3"; tar -xf "$f" -C "$3" --strip-components=1
}
