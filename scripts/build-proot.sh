#!/bin/bash
# Builds the Termux fork of proot (link2symlink, fake root, sysvipc) for
# arm64 Android with the NDK, statically linked against talloc, and installs
# it as app/src/main/jniLibs/arm64-v8a/libproot.so (+ libproot-loader.so).
# Shipping them as "libraries" makes Android extract them executable.
. "$(dirname "$0")/env.sh"

PROOT_REPO=https://github.com/termux/proot
PROOT_REV=${PROOT_REV:-v5.1.107.102}
TALLOC_VERSION=2.5.0
TALLOC_SHA256=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007

# --- talloc (static) ------------------------------------------------------------
TALLOC=$WORK/talloc
if [ ! -f "$WORK/prefix/lib/libtalloc.a" ]; then
  fetch_tar "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" "$TALLOC_SHA256" "$TALLOC"
  cd "$TALLOC"
  # Same answers Termux uses to cross-compile talloc for Android.
  cat >cross-answers.txt <<'ANS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
ANS
  CFLAGS="$OPT_FLAGS -fPIC" ./configure --prefix="$WORK/prefix" --disable-rpath --disable-python \
    --cross-compile --cross-answers=cross-answers.txt >/dev/null
  make -j"$JOBS" >/dev/null
  mkdir -p "$WORK/prefix/lib" "$WORK/prefix/include"
  # talloc needs libreplace (rep_memset_explicit etc.) when linked statically.
  $AR rcs "$WORK/prefix/lib/libtalloc.a" bin/default/talloc*.o $(find bin/default/lib/replace -name "*.o" ! -name "*test*")
  cp talloc.h "$WORK/prefix/include/"
fi

# --- proot ----------------------------------------------------------------------
# loader-info.awk uses gawk's strtonum(); make sure `awk`/`readelf` resolve to tools that work.
command -v gawk >/dev/null || { echo "gawk is required (apt install gawk / brew install gawk)"; exit 1; }
SHIM=$WORK/shim; mkdir -p "$SHIM"
ln -sf "$(command -v gawk)" "$SHIM/awk"
command -v readelf >/dev/null || ln -sf "$READELF" "$SHIM/readelf"
export PATH=$SHIM:$PATH
PROOT=$WORK/proot
fetch_git "$PROOT_REPO" "$PROOT" "$PROOT_REV"
cd "$PROOT/src"
make clean >/dev/null 2>&1 || true
# Newer NDK clang rejects implicit declarations in this file; add the header.
grep -q '<string.h>' extension/ashmem_memfd/ashmem_memfd.c || \
  sed -i '0,/#include/s//#include <string.h>\n#include/' extension/ashmem_memfd/ashmem_memfd.c
# No -mcpu here: the makefile also builds a 32-bit loader (-m32) that can't
# take an AArch64 CPU name, and proot's cost is ptrace round-trips anyway.
# PROOT_UNBUNDLE_LOADER: the loader is a separate file; ArchDock points
# PROOT_LOADER at libproot-loader.so in nativeLibraryDir at runtime.
make -j"$JOBS" V=1 \
  CC="$CC" LD="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$WORK/prefix/include -DARG_MAX=131072 -DVERSION=\\\"$PROOT_REV-archdock\\\" -DPROOT_UNBUNDLE_LOADER=\\\"/nonexistent\\\"" \
  CFLAGS="-O3 -Wall -Wno-unused-parameter" \
  LDFLAGS="-L$WORK/prefix/lib -l:libtalloc.a -Wl,-z,noexecstack -static" \
  PROOT_UNBUNDLE_LOADER=/nonexistent \
  proot loader/loader >"$WORK/proot-build.log" 2>&1 || { tail -40 "$WORK/proot-build.log"; exit 1; }

install -m755 proot "$JNILIBS/libproot.so"
install -m755 loader/loader "$JNILIBS/libproot-loader.so"
# Tensor G4 cores are AArch64-only, so the 32-bit loader is not shipped.
$STRIP "$JNILIBS/libproot.so"
echo "proot:  $(file -b "$JNILIBS/libproot.so" 2>/dev/null || ls -l "$JNILIBS/libproot.so")"
echo "loader: $(file -b "$JNILIBS/libproot-loader.so" 2>/dev/null || ls -l "$JNILIBS/libproot-loader.so")"
