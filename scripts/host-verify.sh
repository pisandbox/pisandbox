#!/usr/bin/env bash
# Host-side check of the exact on-phone flow, on an x86_64 Linux box: unpack the packed rootfs the
# way RootfsInstaller.kt does (hard links -> copies, modes incl. setuid), write resolv.conf, run
# prelaunch.sh as fake root, then start a login shell as "pi" with the generated binds + uname,
# like Sandbox.kt -- using the same PRoot version/flags plus qemu-aarch64 for the CPU.
# Not covered: Android SELinux / seccomp / W^X behaviour, Termux terminal, real phone CPU.
#   ./scripts/host-verify.sh            full test        FAKE_PROC=1 also forces the /proc fallbacks
#   SIM_ANDROID=1 additionally reproduces the phone's /proc layout: /proc/device-tree is a symlink
#   to /sys/firmware/devicetree/base whose files are unreadable (EACCES), as on the Galaxy S25.
# The GPIO side runs the protocol mock (gpio-mock-server.py) on the host's abstract socket
# @app.pisandbox.ctl -- the same place the app listens -- and the guest runs gpio-conformance.py.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
T="$ROOT/build/verify"; R="$T/rootfs"
chmod -R u+w "$T" 2>/dev/null || true; rm -rf "$T"; mkdir -p "$T"
echo "== unpack (RootfsInstaller semantics)"
python3 - "$ROOT/build/rootfs/pisandbox-rootfs-bookworm-arm64.tar.xz" "$R" <<'PY'
import os, sys, tarfile, shutil
src, dst = sys.argv[1:]; os.makedirs(dst); dirs = []
with tarfile.open(src, "r:xz") as t:
    for m in t:
        name = m.name.removeprefix("./").rstrip("/")
        if not name: continue
        out = os.path.join(dst, name); os.makedirs(os.path.dirname(out), exist_ok=True)
        if m.isdir(): os.makedirs(out, exist_ok=True); dirs.append((out, m.mode)); continue
        if m.issym(): os.symlink(m.linkname, out); continue
        if m.islnk(): shutil.copyfile(os.path.join(dst, m.linkname.removeprefix("./")), out)
        elif m.isfile():
            with t.extractfile(m) as f, open(out, "wb") as o: shutil.copyfileobj(f, o)
        else: continue
        os.chmod(out, m.mode & 0o7777)
for d, mode in reversed(dirs): os.chmod(d, mode | 0o700)
os.chmod(os.path.join(dst, "tmp"), 0o1777)
PY
echo "== runtime overlay (RootfsInstaller.applyRuntime semantics)"
[ -f "$ROOT/build/runtime/pisandbox-runtime.tar" ] || "$ROOT/scripts/build-runtime.sh"
python3 - "$ROOT/build/runtime/pisandbox-runtime.tar" "$R" <<'PY'
import os, sys, tarfile
src, dst = sys.argv[1:]
with tarfile.open(src) as t:
    for m in t:
        name = m.name.removeprefix("./").rstrip("/")
        if not name: continue
        out = os.path.join(dst, name)
        if m.isdir(): os.makedirs(out, exist_ok=True); continue
        if os.path.lexists(out): os.chmod(out, 0o600) if not os.path.islink(out) else None; os.remove(out)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with t.extractfile(m) as f, open(out, "wb") as o: o.write(f.read())
        os.chmod(out, m.mode & 0o7777)
PY
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$R/etc/resolv.conf"
EXTRA=()
if [ -n "${SIM_ANDROID:-}" ]; then
  echo "== simulating Android: /proc/device-tree -> /sys/firmware/devicetree/base (unreadable)"
  F="$T/fakefw"; mkdir -p "$F/devicetree/base/system"; echo "phone dtb" > "$F/devicetree/base/model"
  echo x > "$F/devicetree/base/compatible"; chmod 000 "$F/devicetree/base/model" "$F/devicetree/base/compatible"
  ln -s "$F/devicetree/base" "$T/proc-device-tree-link"   # host-side target, so PRoot can sanitize it
  EXTRA+=(-b "$F:/sys/firmware" -b "$T/proc-device-tree-link:/proc/device-tree")
  # negative control: without PiSandbox's overlays the guest must see the phone's EACCES
  if ID="-i 1000:1000" WD=/ host_proot "$R" /bin/cat /proc/device-tree/model >/dev/null 2>&1 \
     || ID="-i 1000:1000" WD=/ host_proot "$R" /bin/cat /sys/firmware/devicetree/base/model >/dev/null 2>&1; then
    echo "SIM FAIL: simulated device-tree is readable; simulation ineffective"; exit 1
  fi
  echo "sim ok: without overlays both device-tree paths give: $(ID="-i 1000:1000" WD=/ host_proot "$R" /bin/cat /proc/device-tree/model 2>&1 | tail -1)"
fi
BASE_EXTRA=("${EXTRA[@]}")
echo "== prelaunch (fake root; same invocation as Sandbox.kt, with Android's PATH outside)"
PATH=/system/bin:$PATH ID=-0 WD=/ host_proot "$R" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  LANG=C.UTF-8 ${FAKE_PROC:+PISANDBOX_FAKE_PROC=1} /bin/bash /usr/lib/pisandbox/prelaunch.sh
G="$R/etc/pisandbox/generated"; EXTRA=("${BASE_EXTRA[@]}")
while IFS= read -r l; do EXTRA+=(-b "$R${l%%:*}:${l#*:}"); done < "$G/binds"
EXTRA+=(-k "$(cat "$G/uname")" -b "$ROOT/scripts/guest-checks.sh:/tmp/guest-checks.sh" -b "$ROOT/scripts/gpio-conformance.py:/tmp/gpio-conformance.py")
# an older shipped button.py (0.5.1; its hash is in rootfs/examples-shipped.sha256) -> must be updated on login
EXTRA+=(-b "$ROOT/scripts/testdata/old-button.py:/tmp/old-button.py")
echo "== GPIO mock control server on @app.pisandbox.ctl"
python3 "$ROOT/scripts/gpio-mock-server.py" unix:@app.pisandbox.ctl --browse-log "$T/browse.log" & MOCK=$!
trap 'kill $MOCK 2>/dev/null' EXIT; sleep 0.5
echo "== session as pi"
ID="-i 1000:1000" WD=/home/pi host_proot "$R" /usr/bin/env -i HOME=/home/pi USER=pi LOGNAME=pi SHELL=/bin/bash \
  TERM=xterm LANG=C.UTF-8 TZ=America/Chicago PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/games:/usr/games \
  LD_PRELOAD=/usr/lib/aarch64-linux-gnu/libpisandbox-noaudit.so PISANDBOX_HOST_QEMU=1 GPIOZERO_PIN_FACTORY=pisandbox \
  /bin/bash -il /tmp/guest-checks.sh
