#!/bin/bash
# Builds the Android-side (bionic, arm64) half of ArchDock's GPU passthrough
# and installs it into app/src/main/jniLibs/arm64-v8a:
#
#   libvirgl_test_server.so  virglrenderer's vtest server (an executable),
#                            Venus enabled, with Termux's Android patches
#                            (Venus on Android Vulkan, ashmem, GLES/EGL paths)
#   libvirglrenderer.so, libepoxy.so
#   libvulkan_archdock.so    single-ICD shim: Venus → Mesa wrapper (or system Vulkan)
#   libvulkan_wrapper.so     Mesa "wrapper" Vulkan ICD (bionic-vulkan-wrapper fork)
#                            → /system/lib64/libvulkan.so → Mali-G715 driver
#
# Guest side: Mesa's Venus ICD (Arch package vulkan-virtio) talks to the
# server over /tmp/.virgl_test; Zink provides OpenGL on top.
#
# Requirements: NDK, meson >= 1.3, ninja, python3 with mako, glslang
# (glslangValidator), pkg-config, patchelf, curl, git.
#   pip install --user meson ninja mako packaging pyyaml
# Set ARCHDOCK_SKIP_WRAPPER=1 to build only virglrenderer + shim (system Vulkan).
. "$(dirname "$0")/env.sh"

VIRGL_VERSION=1.3.0
VIRGL_SHA256=56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce
EPOXY_VERSION=1.5.10
VK_HEADERS_REV=${VK_HEADERS_REV:-v1.4.309}
TERMUX_PACKAGES_REV=${TERMUX_PACKAGES_REV:-master}
WRAPPER_REPO=https://github.com/leegao/bionic-vulkan-wrapper
WRAPPER_REV=${WRAPPER_REV:-3abf48704a4d9303967a0811c0dc8ff8567d613b}
# The wrapper uses Mali-specific SPIR-V passes from its author's SPIRV-Tools fork.
SPIRV_TOOLS_REPO=${SPIRV_TOOLS_REPO:-https://github.com/leegao/SPIRV-Tools}
SPIRV_TOOLS_REV=${SPIRV_TOOLS_REV:-20a5b4305f22d94f78281d7fd9a185ae89590d4f}
SPIRV_HEADERS_REV=${SPIRV_HEADERS_REV:-}   # default: the revision in the fork's DEPS

for tool in meson ninja pkg-config patchelf python3; do
  command -v $tool >/dev/null || { echo "missing build tool: $tool"; exit 1; }
done

# virglrenderer's bundled C11 threads shim clashes with bionic's timespec_get
# (API 29+); like Termux, build these libraries against an older API level.
GPU_API=${GPU_API:-28}
GCC="$TOOLCHAIN/bin/aarch64-linux-android$GPU_API-clang"
GCXX="$TOOLCHAIN/bin/aarch64-linux-android$GPU_API-clang++"
PREFIX=$WORK/gpu-prefix
mkdir -p "$PREFIX/lib/pkgconfig" "$PREFIX/include"
CROSS=$WORK/android-aarch64.cross
cat >"$CROSS" <<CROSSFILE
[binaries]
c = '$GCC'
cpp = '$GCXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'

[built-in options]
c_args = ['-O3', '$CPU_FLAGS', '-D__TERMUX__', '-I$PREFIX/include']
cpp_args = ['-O3', '$CPU_FLAGS', '-D__TERMUX__', '-I$PREFIX/include']
c_link_args = ['-L$PREFIX/lib', '-Wl,-z,max-page-size=16384']
cpp_link_args = ['-L$PREFIX/lib', '-Wl,-z,max-page-size=16384', '-static-libstdc++']

[properties]
needs_exe_wrapper = true
pkg_config_libdir = '$PREFIX/lib/pkgconfig'
CROSSFILE
export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig PKG_CONFIG_PATH=
# The Mesa wrapper uses AHardwareBuffer APIs from API 29+: build it at $API.
CROSS_WRAPPER=$WORK/android-aarch64-api$API.cross
sed -e "s|aarch64-linux-android$GPU_API-clang|aarch64-linux-android$API-clang|g" "$CROSS" >"$CROSS_WRAPPER"

# --- Vulkan headers (newer than the NDK's) -------------------------------------
fetch_git https://github.com/KhronosGroup/Vulkan-Headers "$WORK/Vulkan-Headers" "$VK_HEADERS_REV"
rm -rf "$PREFIX/include/vulkan" "$PREFIX/include/vk_video"
cp -r "$WORK/Vulkan-Headers/include/vulkan" "$WORK/Vulkan-Headers/include/vk_video" "$PREFIX/include/"

# --- Vulkan shim (stands in for libvulkan) --------------------------------------
$GCC -shared -fPIC -O2 -fvisibility=hidden -I"$PREFIX/include" -Wl,-soname,libvulkan_archdock.so \
  "$ROOT/native/vk-shim/vk_shim.c" -o "$PREFIX/lib/libvulkan_archdock.so" -ldl -llog
cat >"$PREFIX/lib/pkgconfig/vulkan.pc" <<PC
prefix=$PREFIX
Name: vulkan
Description: ArchDock single-ICD Vulkan shim
Version: ${VK_HEADERS_REV#v}
Libs: -L$PREFIX/lib -lvulkan_archdock
Cflags: -I$PREFIX/include
PC

# --- Termux's Android patches for virglrenderer/libepoxy ------------------------
TP=$WORK/termux-packages
if [ ! -d "$TP/.git" ]; then
  git init -q "$TP" && git -C "$TP" remote add origin https://github.com/termux/termux-packages
  git -C "$TP" sparse-checkout init --no-cone >/dev/null
  echo /packages/virglrenderer-android/ >"$TP/.git/info/sparse-checkout"
fi
git -C "$TP" fetch -q --depth 1 --filter=blob:none origin "$TERMUX_PACKAGES_REV"
git -C "$TP" checkout -q --force FETCH_HEAD

# --- libepoxy + virglrenderer --------------------------------------------------
SRC=$WORK/virglrenderer
fetch_tar "https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-$VIRGL_VERSION/virglrenderer-virglrenderer-$VIRGL_VERSION.tar.gz" "$VIRGL_SHA256" "$SRC"
rm -rf "$SRC/libepoxy"
fetch_git https://github.com/anholt/libepoxy "$SRC/libepoxy" "$EPOXY_VERSION"
for p in "$TP"/packages/virglrenderer-android/*.patch.beforehostbuild; do
  sed "s|@TERMUX_PREFIX@|/data/local/tmp/archdock|g" "$p" | patch -d "$SRC" -p1 --forward --silent \
    || echo "warning: patch $(basename "$p") did not apply cleanly"
done
# Termux's AHardwareBuffer allocator is named vkr_get_fd_info_from_allocation_info;
# one dma-buf call site in 1.3.0 still uses the gbm name.
grep -q 'ArchDock alias' "$SRC/src/venus/vkr_device_memory.c" || \
  sed -i 's|^#endif /\* ENABLE_GBM_ALLOCATION \*/|#if defined(__TERMUX__) /* ArchDock alias */\n#define vkr_gbm_get_fd_info_from_allocation_info vkr_get_fd_info_from_allocation_info\n#endif\n&|' \
  "$SRC/src/venus/vkr_device_memory.c"
# Venus dlopen()s libvulkan at runtime; send it to our single-ICD shim instead
# of Android's platform loader (which can only load the vendor HAL).
sed -i 's|dlopen("libvulkan.so.1", |dlopen("libvulkan_archdock.so", |' "$SRC/src/venus/vkr_library.c"
meson setup --wipe "$WORK/epoxy-build" "$SRC/libepoxy" --cross-file "$CROSS" --prefix "$PREFIX" --libdir lib \
  --buildtype release -Degl=yes -Dglx=no -Dx11=false -Dtests=false >/dev/null
ninja -C "$WORK/epoxy-build" install >/dev/null
meson setup --wipe "$WORK/virgl-build" "$SRC" --cross-file "$CROSS" --prefix "$PREFIX" --libdir lib \
  --buildtype release -Dvenus=true -Dplatforms=egl -Dtests=false >/dev/null
ninja -C "$WORK/virgl-build" install >/dev/null

# --- Mesa wrapper ICD (bionic) -------------------------------------------------
if [ "${ARCHDOCK_SKIP_WRAPPER:-0}" != 1 ]; then
  W=$WORK/bionic-vulkan-wrapper
  fetch_git "$WRAPPER_REPO" "$W" "$WRAPPER_REV"
  # The wrapper links SPIRV-Tools statically (see src/vulkan/wrapper/pull_spirv_tools.sh).
  if [ ! -f "$W/src/vulkan/wrapper/lib/.archdock-$SPIRV_TOOLS_REV" ]; then
    rm -rf "$WORK/SPIRV-Tools" "$WORK/spirv-build"
    fetch_git "$SPIRV_TOOLS_REPO" "$WORK/SPIRV-Tools" "$SPIRV_TOOLS_REV"
    [ -n "$SPIRV_HEADERS_REV" ] || SPIRV_HEADERS_REV=$(sed -n "s/.*'spirv_headers_revision': '\([0-9a-f]*\)'.*/\1/p" "$WORK/SPIRV-Tools/DEPS")
    fetch_git https://github.com/KhronosGroup/SPIRV-Headers "$WORK/SPIRV-Tools/external/spirv-headers" "$SPIRV_HEADERS_REV"
    cmake -S "$WORK/SPIRV-Tools" -B "$WORK/spirv-build" -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release -DSPIRV_SKIP_EXECUTABLES=ON \
      -DSPIRV_SKIP_TESTS=ON -DSPIRV_WERROR=OFF >/dev/null
    ninja -C "$WORK/spirv-build" SPIRV-Tools-static SPIRV-Tools-opt >/dev/null
    mkdir -p "$W/src/vulkan/wrapper/lib"
    cp "$WORK/spirv-build/source/libSPIRV-Tools.a" "$W/src/vulkan/wrapper/lib/"
    cp "$WORK/spirv-build/source/opt/libSPIRV-Tools-opt.a" "$W/src/vulkan/wrapper/lib/"
    rm -rf "$W/src/vulkan/wrapper/include/spirv-tools"
    cp -r "$WORK/SPIRV-Tools/include" "$W/src/vulkan/wrapper/include/spirv-tools"
    touch "$W/src/vulkan/wrapper/lib/.archdock-$SPIRV_TOOLS_REV"
  fi
  $CC -c -fPIC -O2 "$ROOT/native/vk-shim/wrapper_link_stubs.c" -o "$WORK/wrapper_link_stubs.o"
  # Mesa's Vulkan runtime wants libdrm even for this driver; a core-only build is enough.
  if [ ! -f "$PREFIX/lib/pkgconfig/libdrm.pc" ]; then
    fetch_git https://gitlab.freedesktop.org/mesa/drm.git "$WORK/libdrm" "${LIBDRM_REV:-libdrm-2.4.124}"
    meson setup --wipe "$WORK/libdrm-build" "$WORK/libdrm" --cross-file "$CROSS" --prefix "$PREFIX" --libdir lib \
      --buildtype release -Dintel=disabled -Dradeon=disabled -Damdgpu=disabled -Dnouveau=disabled \
      -Dvmwgfx=disabled -Domap=disabled -Dexynos=disabled -Dfreedreno=disabled -Dtegra=disabled \
      -Dvc4=disabled -Detnaviv=disabled -Dcairo-tests=disabled -Dman-pages=disabled \
      -Dvalgrind=disabled -Dtests=false -Dinstall-test-programs=false >/dev/null
    ninja -C "$WORK/libdrm-build" install >/dev/null
  fi
  # The wrapper's generated struct printers reference the Xlib/XCB surface
  # structs. Presentation happens in the guest (Venus WSI), never here, so
  # type-only stub headers are enough – no X11 libraries are linked.
  STUBS=$WORK/wsi-stubs; mkdir -p "$STUBS/X11" "$STUBS/xcb"
  printf '#pragma once\ntypedef struct _XDisplay Display;\ntypedef unsigned long Window;\ntypedef unsigned long VisualID;\n' >"$STUBS/X11/Xlib.h"
  printf '#pragma once\n#include <stdint.h>\ntypedef struct xcb_connection_t xcb_connection_t;\ntypedef uint32_t xcb_window_t;\ntypedef uint32_t xcb_visualid_t;\n' >"$STUBS/xcb/xcb.h"
  sed -e "s|'-D__TERMUX__'|'-D__TERMUX__', '-I$STUBS', '-DVK_USE_PLATFORM_XLIB_KHR', '-DVK_USE_PLATFORM_XCB_KHR', '-include', 'fcntl.h'|g" \
    -e "s|^cpp_link_args = \[|cpp_link_args = ['$WORK/wrapper_link_stubs.o', |" \
    -e "s|^c_link_args = \[|c_link_args = ['$WORK/wrapper_link_stubs.o', |" \
    "$CROSS_WRAPPER" >"$CROSS_WRAPPER.stubs"
  meson setup --wipe "$WORK/wrapper-build" "$W" --cross-file "$CROSS_WRAPPER.stubs" --prefix "$PREFIX" --libdir lib \
    --buildtype release -Dplatforms=android -Dplatform-sdk-version=$API -Dandroid-stub=true \
    -Dgallium-drivers= -Dvulkan-drivers=wrapper -Dllvm=disabled -Dopengl=false -Degl=disabled \
    -Dgles1=disabled -Dgles2=disabled -Dglx=disabled -Dgbm=disabled -Dzstd=disabled >/dev/null
  ninja -C "$WORK/wrapper-build" src/vulkan/wrapper/libvulkan_wrapper.so >/dev/null
  install -m755 "$WORK/wrapper-build/src/vulkan/wrapper/libvulkan_wrapper.so" "$JNILIBS/libvulkan_wrapper.so"
fi

# --- Install into the APK's native libs ----------------------------------------
install -m755 "$PREFIX/bin/virgl_test_server" "$JNILIBS/libvirgl_test_server.so"
for lib in libvirglrenderer.so libepoxy.so libvulkan_archdock.so libdrm.so; do
  [ -e "$PREFIX/lib/$lib" ] || continue
  real=$(readlink -f "$PREFIX/lib/$lib"); install -m755 "$real" "$JNILIBS/$lib"
done
# Versioned SONAMEs (libvirglrenderer.so.1) can't be packaged; use plain names.
patchelf --set-soname libvirglrenderer.so "$JNILIBS/libvirglrenderer.so"
patchelf --set-soname libepoxy.so "$JNILIBS/libepoxy.so"
for f in "$JNILIBS/libvirgl_test_server.so" "$JNILIBS/libvirglrenderer.so"; do
  for n in $(patchelf --print-needed "$f"); do
    case $n in libvirglrenderer.so.*) patchelf --replace-needed "$n" libvirglrenderer.so "$f" ;;
               libepoxy.so.*) patchelf --replace-needed "$n" libepoxy.so "$f" ;; esac
  done
  patchelf --remove-rpath "$f"
done
[ -f "$JNILIBS/libvulkan_wrapper.so" ] && patchelf --remove-rpath "$JNILIBS/libvulkan_wrapper.so"
$STRIP "$JNILIBS"/libvirgl*.so "$JNILIBS"/libepoxy.so "$JNILIBS"/libvulkan_*.so "$JNILIBS"/libdrm.so 2>/dev/null || true
ls -l "$JNILIBS"
