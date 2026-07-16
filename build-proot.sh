#!/usr/bin/env bash
set -euo pipefail
# build-proot.sh - Build proot native binaries for the Agora Android app.
# Supports both arm64-v8a and armeabi-v7a via the first argument.
# Must run on Linux with NDK installed (GitHub Actions runner or local WSL).

SCRIPT_DIR="$(cd "$(dirname "BASH_SOURCE[0]")" && pwd)"
FORCE="${1:-}"
TARGET_ARCH="${2:-arm64-v8a}"

# ── NDK auto-detection ────────────────────────────────────────
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK="$ANDROID_NDK_HOME"
elif [ -d "/usr/local/lib/android/sdk/ndk/28.2.13676358" ]; then
    NDK="/usr/local/lib/android/sdk/ndk/28.2.13676358"
elif [ -d "/home/newoether/android-sdk/ndk/28.2.13676358" ]; then
    NDK="/home/newoether/android-sdk/ndk/28.2.13676358"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK=$(ls -d "$ANDROID_HOME/ndk/"*/ 2>/dev/null | sort -V | tail -1)
    NDK="${NDK%/}"
else
    echo "ERROR: NDK not found. Set ANDROID_NDK_HOME or install NDK 28.2.13676358."
    exit 1
fi

# ── Architecture-specific settings ────────────────────────────
TC_PREFIX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

if [ "$TARGET_ARCH" = "armeabi-v7a" ]; then
    CROSS_PREFIX="armv7a-linux-androideabi24"
    JNILIBS="$SCRIPT_DIR/app/src/main/jniLibs/armeabi-v7a"
    FDROID_JNILIBS="$SCRIPT_DIR/app/src/fdroid/jniLibs/armeabi-v7a"
    BLD_DIR="$SCRIPT_DIR/.build-proot/build-proot-armeabi-v7a"
    SYSROOT="$SCRIPT_DIR/.build-proot/sysroot/armeabi-v7a"
elif [ "$TARGET_ARCH" = "arm64-v8a" ]; then
    CROSS_PREFIX="aarch64-linux-android26"
    JNILIBS="$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a"
    FDROID_JNILIBS="$SCRIPT_DIR/app/src/fdroid/jniLibs/arm64-v8a"
    BLD_DIR="$SCRIPT_DIR/.build-proot/build-proot-arm64-v8a"
    SYSROOT="$SCRIPT_DIR/.build-proot/sysroot/arm64-v8a"
else
    echo "ERROR: Unknown architecture: $TARGET_ARCH (use arm64-v8a or armeabi-v7a)"
    exit 1
fi

CC="${TC_PREFIX}/${CROSS_PREFIX}-clang"
STRIP="${TC_PREFIX}/llvm-strip"
export PATH="${TC_PREFIX}:$PATH"

SYSROOT_LIB="$SYSROOT/lib"
SYSROOT_INC="$SYSROOT/include"
LOADER_OUT="$BLD_DIR/loader-out"

echo "=== build-proot.sh: NDK=$NDK ARCH=$TARGET_ARCH ==="

# ── Paths ──────────────────────────────────────────────────────
TALLOC_SRC="$SCRIPT_DIR/thirdparty/talloc"
PROOT_SRC="$SCRIPT_DIR/thirdparty/proot/src"

# ── Ensure readelf is available (GNUmakefile needs it) ─────────
if ! command -v readelf &>/dev/null; then
    if command -v llvm-readelf &>/dev/null; then
        READELF_DIR="$SCRIPT_DIR/.build-proot/.tmp-bin"
        mkdir -p "$READELF_DIR"
        ln -sf "$(command -v llvm-readelf)" "$READELF_DIR/readelf"
        export PATH="$READELF_DIR:$PATH"
    fi
fi

# ── Hash-based incremental detection ───────────────────────────
HASH_FILE="$BLD_DIR/.source_hashes"
need_rebuild=false

calc_hashes() {
    local hash=""
    hash+=$(md5sum "$TALLOC_SRC/talloc.c" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    hash+=$(md5sum "$TALLOC_SRC/talloc.h" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    hash+=$(md5sum "$TALLOC_SRC/config.h" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    hash+=$(md5sum "$TALLOC_SRC/replace.h" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    hash+=$(md5sum "$PROOT_SRC/GNUmakefile" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    hash+=$(echo "$NDK" | md5sum | cut -d' ' -f1)
    hash+="-"
    hash+=$(echo "$TARGET_ARCH")
    echo "$hash"
}

if [ "$FORCE" = "--force" ]; then
    need_rebuild=true
    echo "  (forced rebuild)"
elif [ ! -f "$HASH_FILE" ]; then
    need_rebuild=true
    echo "  (first build, no hash file)"
elif [ ! -f "$JNILIBS/libproot_exec.so" ] || \
     [ ! -f "$JNILIBS/libproot_loader.so" ] || \
     [ ! -f "$JNILIBS/libtalloc.so" ]; then
    need_rebuild=true
    echo "  (missing jniLibs output)"
else
    new_hash=$(calc_hashes)
    old_hash=$(cat "$HASH_FILE")
    if [ "$new_hash" != "$old_hash" ]; then
        need_rebuild=true
        echo "  (source changed)"
    else
        echo "  (up to date, skipping)"
    fi
fi

if [ "$need_rebuild" = false ]; then
    echo "=== build-proot.sh: All binaries up to date ==="
    exit 0
fi

echo "=== build-proot.sh: Building proot binaries for $TARGET_ARCH ==="

# ── Create directories ─────────────────────────────────────────
mkdir -p "$SYSROOT_LIB" "$SYSROOT_INC" "$LOADER_OUT" \
         "$JNILIBS" "$FDROID_JNILIBS" "$BLD_DIR/loader"

# ── Step 1: Build talloc ───────────────────────────────────────
echo "  [1/4] Building libtalloc.so..."
(
    cd "$TALLOC_SRC"
    "$CC" -fPIC -O2 -shared \
        -Wl,-soname,libtalloc.so \
        -o "$SYSROOT_LIB/libtalloc.so" \
        talloc.c \
        -DHAVE_CONFIG_H \
        -I.
)
cp "$TALLOC_SRC/talloc.h" "$SYSROOT_INC/"
echo "  [1/4] Done: $(stat -c%s "$SYSROOT_LIB/libtalloc.so") bytes"

# ── Step 2: Build proot (in-tree inside the build dir) ─────────
echo "  [2/4] Building proot (GNUmakefile)..."
PROOT_BLD="$BLD_DIR/src"
rm -rf "$PROOT_BLD"
mkdir -p "$PROOT_BLD"
cp -r "$PROOT_SRC/." "$PROOT_BLD/"
find "$PROOT_BLD" -name '*.o' -delete
find "$PROOT_BLD" -name '*.d' -delete
find "$PROOT_BLD" -name '*.res' -delete
rm -f "$PROOT_BLD/build.h" "$PROOT_BLD/proot" "$PROOT_BLD/loader/loader" \
      "$PROOT_BLD/.check_process_vm" "$PROOT_BLD/.check_seccomp_filter"
# ── Cross-compilation source fixes ─────────────────────────────
printf 'int main(void){return 0;}\n' > "$PROOT_BLD/.check_process_vm.c"
printf 'int main(void){return 0;}\n' > "$PROOT_BLD/.check_seccomp_filter.c"
ASHMEM_C="$PROOT_BLD/extension/ashmem_memfd/ashmem_memfd.c"
if [ -f "$ASHMEM_C" ] && ! grep -q '#include <string.h>' "$ASHMEM_C"; then
    sed -i '1i #include <string.h>' "$ASHMEM_C"
fi
# ── Patch loader-info.awk for POSIX awk (mawk) compatibility ───
cat > "$PROOT_BLD/loader/loader-info.awk" <<'ENDAWK'
function hextonum(hex,   i, n, c, idx) {
    hex = tolower(hex)
    n = 0
    for (i = 1; i <= length(hex); i++) {
        c = substr(hex, i, 1)
        idx = index("0123456789abcdef", c)
        if (idx == 0) break
        n = n * 16 + (idx - 1)
    }
    return n
}
$NF == "pokedata_workaround" { pokedata_workaround = hextonum($2) }
$NF == "_start" { start = hextonum($2) }
END {
    print "#include <unistd.h>"
    print "const ssize_t offset_to_pokedata_workaround=" (pokedata_workaround - start) ";"
}
ENDAWK
(
    cd "$PROOT_BLD"
    export SOURCE_DATE_EPOCH=0
    export CPPFLAGS="-I${SYSROOT_INC} -DSYS_SECCOMP=1"
    export LDFLAGS="-L${SYSROOT_LIB}"
    export CC="${TC_PREFIX}/${CROSS_PREFIX}-clang"
    make CROSS_COMPILE="${CROSS_PREFIX}-" \
        PROOT_UNBUNDLE_LOADER="loader-out" \
        GIT=/bin/true \
        proot
)
echo "  [2/4] Done: $(stat -c%s "$PROOT_BLD/proot") bytes"

# ── Step 3: Strip and deploy binaries to jniLibs ───────────────
echo "  [3/4] Stripping and deploying..."

"$STRIP" --strip-all \
    "$BLD_DIR/src/proot" \
    -o "$JNILIBS/libproot_exec.so"

LOADER_SRC="$BLD_DIR/src/loader/loader"
if [ ! -f "$LOADER_SRC" ]; then
    echo "ERROR: loader binary not found at $LOADER_SRC"
    exit 1
fi
cp "$LOADER_SRC" "$JNILIBS/libproot_loader.so"

"$STRIP" --strip-all \
    "$SYSROOT_LIB/libtalloc.so" \
    -o "$JNILIBS/libtalloc.so"

echo "  [3/4] Binaries deployed:"
echo "    $(stat -c%s "$JNILIBS/libproot_exec.so") bytes  libproot_exec.so"
echo "    $(stat -c%s "$JNILIBS/libproot_loader.so") bytes  libproot_loader.so"
echo "    $(stat -c%s "$JNILIBS/libtalloc.so") bytes  libtalloc.so"

# ── Step 4: Sync to fdroid jniLibs flavor ──────────────────────
echo "  [4/4] Syncing to fdroid jniLibs..."
cp "$JNILIBS/libproot_exec.so" "$FDROID_JNILIBS/libproot_exec.so"
cp "$JNILIBS/libproot_loader.so" "$FDROID_JNILIBS/libproot_loader.so"
cp "$JNILIBS/libtalloc.so" "$FDROID_JNILIBS/libtalloc.so"

# ── Save hash for next incremental check ───────────────────────
calc_hashes > "$HASH_FILE"

echo "=== build-proot.sh: Complete ($TARGET_ARCH) ==="