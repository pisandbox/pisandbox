#!/usr/bin/env python3
"""Normalise a rootfs that was customised under PRoot and pack it as tar.xz.

PRoot's --link2symlink turns every hard link into a symlink chain pointing at hidden
".l2s.*" files via *absolute host paths* (e.g. /home/me/build/rootfs/usr/bin/.l2s.unzip0001).
Those paths would be dangling on the phone, so: each such symlink becomes a real hard link to
the final data file (stored as a tar hard link; the app's extractor copies it), and all .l2s.*
helpers are removed. Also drops PRoot meta files. Usage: pack-rootfs.py ROOTFS OUT.tar.xz
"""
import os, sys, subprocess

root = os.path.abspath(sys.argv[1]); out = sys.argv[2]
fixed = 0
for d, dirs, files in os.walk(root):
    for n in files + dirs:
        p = os.path.join(d, n)
        if os.path.islink(p) and os.readlink(p).startswith(root + "/") and not n.startswith(".l2s."):
            final = os.path.realpath(p)
            if not os.path.isfile(final):
                sys.exit(f"cannot resolve l2s link {p} -> {final}")
            os.unlink(p); os.link(final, p); fixed += 1
removed = 0
for d, dirs, files in os.walk(root):
    for n in files + dirs:
        if n.startswith(".l2s.") or n.startswith(".proot-meta-file.") or n.startswith(".proot."):
            os.unlink(os.path.join(d, n)); removed += 1
bad = [os.path.join(d, n) for d, ds, fs in os.walk(root) for n in fs + ds
       if os.path.islink(os.path.join(d, n)) and os.readlink(os.path.join(d, n)).startswith(root)]
if bad: sys.exit(f"host-absolute symlinks remain: {bad[:5]}")
print(f"l2s links materialised: {fixed}, helper files removed: {removed}")
# Sorted, numeric root ownership (the app ignores owners anyway), xz -9 (decoder needs ~65 MiB).
tar = subprocess.Popen(["tar", "--sort=name", "--numeric-owner", "--owner=0", "--group=0",
                        "--format=gnu", "-C", root, "-cf", "-", "."], stdout=subprocess.PIPE)
with open(out, "wb") as f:
    subprocess.check_call(["xz", "-9", "-T", "0", "-c"], stdin=tar.stdout, stdout=f)
assert tar.wait() == 0
