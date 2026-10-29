#!/usr/bin/env bash
# Builds the PiSandbox *runtime overlay*: small files the app (re)installs into the guest rootfs on
# first run and after every app update, so app upgrades reach existing installs without
# re-extracting the 380 MB rootfs (and without touching /home or apt state):
#   rootfs/runtime/**        prelaunch, profile.d, RPi.GPIO shim, gpiozero factory, gpio/browse/...
#   + gpiozero 2.0.1 / colorzero 2.0 (pinned wheels) -> /usr/lib/python3/dist-packages
# Output: build/runtime/pisandbox-runtime.tar (deterministic: sorted, mtime 0, owner 0)
set -euo pipefail
. "$(dirname "$0")/lib.sh"
O="$ROOT/build/runtime"; T="$O/tree"; rm -rf "$O"; mkdir -p "$T"
cp -a "$ROOT/rootfs/runtime/." "$T/"
DP="$T/usr/lib/python3/dist-packages"
for w in py-gpiozero py-colorzero; do python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$(fetch $w)" "$DP"; done
# Examples sync (profile.d): sha256 of every version of each example we ever shipped (git history + the
# current file). A user's copy whose hash is in this list is unmodified, so it is safe to update.
EX=usr/share/pisandbox/examples
( cd "$ROOT"
  for f in "rootfs/runtime/$EX"/*; do n=${f##*/}
    { sha256sum < "$f"
      if git rev-parse -q --verify HEAD >/dev/null 2>&1; then
        for r in $(git rev-list HEAD -- "$f"); do git show "$r:$f" 2>/dev/null | sha256sum; done
      fi; } | cut -d' ' -f1 | sort -u | sed "s|\$|  $n|"
  done
  # versions shipped before (kept in git so a tarball / squashed history still knows them)
  grep -v '^#' rootfs/examples-shipped.sha256 ) | sort -u > "$T/$EX/.shipped-hashes"
echo "examples: $(wc -l < "$T/$EX/.shipped-hashes") shipped hashes"
find "$T" -name __pycache__ -prune -exec rm -rf {} +
find "$T" -type d -exec chmod 755 {} +; find "$T" -type f -exec chmod u+rw,go+r {} +
chmod 0440 "$T"/etc/sudoers.d/*
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner --format=gnu -C "$T" -cf "$O/pisandbox-runtime.tar" .
ls -la "$O/pisandbox-runtime.tar"
