#!/usr/bin/env bash
# Stages everything the APK bundles:
#   app/src/main/jniLibs/arm64-v8a/lib*.so   PRoot + its libs (Termux aarch64 builds, pinned in
#                                             assets.lock). Packaged as "native libs" so Android
#                                             extracts them to nativeLibraryDir (exec allowed;
#                                             avoids the W^X exec ban on app data).
#   app/src/main/assets/rootfs/pisandbox-rootfs-bookworm-arm64.tar.xz
#                                             the customised Debian 12 rootfs from build-rootfs.sh
#                                             PISANDBOX_ROOTFS selects where it comes from:
#                                               auto (default)  build/rootfs/ if present, else the prebuilt
#                                                               release asset, else build it
#                                               prebuilt        download the release asset (sha256 in assets.lock)
#                                               build           always run build-rootfs.sh (REBUILD_ROOTFS=1 too)
set -euo pipefail
. "$(dirname "$0")/lib.sh"
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"; ASSETS="$ROOT/app/src/main/assets"
mkdir -p "$JNI"; rm -rf "$ASSETS/rootfs" "$ASSETS/qemu" "$ASSETS/runtime"; mkdir -p "$ASSETS/rootfs"

echo "== Termux proot + libs (aarch64 Android builds)"
STAGE="$ROOT/.cache/termux"; rm -rf "$STAGE"
for p in termux-proot termux-libtalloc termux-libandroid-shmem; do debx "$(fetch $p)" "$STAGE"; done
U="$STAGE/data/data/com.termux/files/usr"
cp "$U/bin/proot"                 "$JNI/libproot.so"
cp "$U/libexec/proot/loader"      "$JNI/libproot-loader.so"
[[ -f "$U/libexec/proot/loader32" ]] && cp "$U/libexec/proot/loader32" "$JNI/libproot-loader32.so"
cp "$(readlink -f "$U/lib/libtalloc.so.2")" "$JNI/libtalloc.so"
cp "$U/lib/libandroid-shmem.so"   "$JNI/libandroid-shmem.so"
# Android only extracts files named lib*.so from jniLibs: rename sonames/NEEDED entries and drop
# Termux's hard-coded RUNPATH (the app sets LD_LIBRARY_PATH=nativeLibraryDir).
command -v patchelf >/dev/null || { echo "patchelf is required" >&2; exit 1; }
patchelf --set-soname libtalloc.so "$JNI/libtalloc.so"
patchelf --replace-needed libtalloc.so.2 libtalloc.so --remove-rpath "$JNI/libproot.so"
chmod 755 "$JNI"/*.so

echo "== PiSandbox rootfs (Debian 12 bookworm arm64)"
R="$ROOT/build/rootfs/pisandbox-rootfs-bookworm-arm64.tar.xz"
MODE="${PISANDBOX_ROOTFS:-auto}"; [[ "${REBUILD_ROOTFS:-0}" == 1 ]] && MODE=build
prebuilt() { # verified download (lib.sh fetch) -> build/rootfs/ with its .sha256
  local f; f=$(fetch pisandbox-rootfs) || return 1
  mkdir -p "$(dirname "$R")"; cp "$f" "$R"
  (cd "$(dirname "$R")" && sha256sum "$(basename "$R")" > "$(basename "$R").sha256")
  echo "using the prebuilt rootfs from the release ($(grep -E '^pisandbox-rootfs ' "$ROOT/scripts/assets.lock" | awk '{print $2}'))"
}
case "$MODE" in
  build)    "$ROOT/scripts/build-rootfs.sh" ;;
  prebuilt) [[ -f "$R" ]] && cmp -s <(awk '{print $1}' "$R.sha256" 2>/dev/null) <(grep -E '^pisandbox-rootfs ' "$ROOT/scripts/assets.lock" | awk '{print $3}') \
              || prebuilt ;;
  auto)     [[ -f "$R" ]] || ( prebuilt ) || { echo "prebuilt rootfs unavailable - building it (slow)" >&2; "$ROOT/scripts/build-rootfs.sh"; } ;;
  *)        echo "PISANDBOX_ROOTFS must be auto, prebuilt or build" >&2; exit 2 ;;
esac
(cd "$(dirname "$R")" && sha256sum -c --quiet "$(basename "$R").sha256")
cp "$R" "$ASSETS/rootfs/"
echo "== runtime overlay (GPIO shims, tools, prelaunch)"
"$ROOT/scripts/build-runtime.sh"
mkdir -p "$ASSETS/runtime"; cp "$ROOT/build/runtime/pisandbox-runtime.tar" "$ASSETS/runtime/"
echo "done:"; ls -la "$JNI" "$ASSETS/rootfs"
