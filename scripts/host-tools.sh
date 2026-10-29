#!/usr/bin/env bash
# Builds/stages x86_64 host tools into build/hosttools: PRoot (Termux fork, same version as the
# APK's) and a static qemu-aarch64 (Debian qemu-user). No root needed; nothing is installed system-wide.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
mkdir -p "$HT"; S="$HT/sysroot"; rm -rf "$S"
for p in host-qemu-user host-libtalloc2 host-libtalloc-dev host-make; do debx "$(fetch $p)" "$S"; done
cp "$S/usr/bin/qemu-aarch64" "$HT/qemu-aarch64"
L="$S/usr/lib/x86_64-linux-gnu"; ln -sf libtalloc.so.2 "$L/libtalloc.so"
rm -rf "$HT/proot-src"; mkdir -p "$HT/proot-src"
python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$(fetch host-proot-src)" "$HT/proot-src"
cd "$HT"/proot-src/proot-*/src
PATH="$S/usr/bin:$PATH" make -j"$(nproc)" proot \
  CFLAGS="-I$S/usr/include -O2 -DARG_MAX=131072 -DVERSION=\\\"5.1.107.101-host\\\"" \
  LDFLAGS="-L$L -Wl,-rpath,$L -ltalloc" >/dev/null
cp proot "$HT/proot"
"$HT/proot" --version | head -3; "$HT/qemu-aarch64" --version | head -1
