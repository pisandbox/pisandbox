#!/usr/bin/env bash
# Builds the PiSandbox guest rootfs: official debian:bookworm arm64 rootfs (pinned in assets.lock)
# + packages (rootfs/packages.txt) + Pi-OS-like config (rootfs/overlay, rootfs/customize.sh),
# customised by running the arm64 guest under the host PRoot + qemu-aarch64 (no root, no binfmt).
# Output: build/rootfs/pisandbox-rootfs-bookworm-arm64.tar.xz (+ .sha256), and the installed
# package list in rootfs/manifest-bookworm-arm64.txt (committed, so changes are reviewable).
# Package versions = whatever deb.debian.org bookworm(-updates,-security) serves at build time.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
OUTDIR="$ROOT/build/rootfs"; W="$OUTDIR/work"; OUT="$OUTDIR/pisandbox-rootfs-bookworm-arm64.tar.xz"
mkdir -p "$OUTDIR"; chmod -R u+w "$W" 2>/dev/null || true; rm -rf "$W"; mkdir -p "$W"

echo "== base: debian:bookworm arm64"
tar -xzf "$(fetch debian-rootfs)" -C "$W"

echo "== overlay + build inputs"
cp -a "$ROOT/rootfs/overlay/." "$W/"
# runtime overlay too (the app re-applies it on every app update; see scripts/build-runtime.sh)
cp -a "$ROOT/rootfs/runtime/." "$W/"
B="$W/tmp/pisandbox-build"; mkdir -p "$B"
cp "$ROOT/rootfs/packages.txt" "$ROOT/rootfs/customize.sh" "$B/"
NDKB=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin | tail -1)
"$NDKB/clang" --target=aarch64-linux-gnu -O2 -fPIC -shared -nostdlib -fuse-ld=lld \
  -Wl,-soname,libpisandbox-noaudit.so -Wl,--hash-style=both -Wl,-z,max-page-size=65536 \
  -o "$B/libpisandbox-noaudit.so" "$ROOT/rootfs/shim/noaudit.c"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$W/etc/resolv.conf"

echo "== customise inside the arm64 guest (proot + qemu-aarch64)"
host_proot "$W" /usr/bin/env -i HOME=/root TERM=dumb LANG=C.UTF-8 \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash /tmp/pisandbox-build/customize.sh
mv "$W/root/pisandbox-manifest.txt" "$ROOT/rootfs/manifest-bookworm-arm64.txt"
rm -rf "$B"

echo "== normalise + pack"
python3 "$ROOT/scripts/pack-rootfs.py" "$W" "$OUT"
(cd "$OUTDIR" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
ls -la "$OUT"; cat "$OUT.sha256"; du -sh "$W"
