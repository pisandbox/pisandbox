#!/usr/bin/env python3
"""Turn the Alpine minirootfs tarball into a gzip'd newc cpio initramfs with an /init
that boots straight to a shell on the serial console. Pure Python: no root, no cpio binary.
Used by the QEMU (system-emulation) path: kernel = Alpine vmlinuz-virt, initrd = this file.

usage: mkinitramfs.py <minirootfs.tar.gz> <out.cpio.gz>
"""
import gzip, io, os, sys, tarfile, stat

INIT = b"""#!/bin/sh
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
mount -t proc proc /proc; mount -t sysfs sysfs /sys; mount -t devtmpfs dev /dev 2>/dev/null
mkdir -p /dev/pts /tmp; mount -t devpts devpts /dev/pts; mount -t tmpfs tmpfs /tmp
hostname pi-proto
echo; echo "=== pi-android-proto: Alpine $(cat /etc/alpine-release) up on $(uname -m) ==="
if grep -q 'pitest=1' /proc/cmdline; then
  echo "--- self-test ---"; uname -a; cat /etc/os-release; cat /proc/cpuinfo | head -8; free -m
  echo "PI_PROTO_BOOT_OK"; sync; poweroff -f
fi
exec setsid cttyhack /bin/sh -l 2>/dev/null || exec /bin/sh -l
"""

def main(src, dst):
    ino = [1000]
    out = io.BytesIO()
    def entry(name, mode, data=b"", nlink=1, rdev=(0, 0)):
        name_b = name.encode() + b"\0"
        ino[0] += 1
        hdr = "070701" + "".join("%08X" % v for v in (
            ino[0], mode, 0, 0, nlink, 0, len(data), 0, 0, rdev[0], rdev[1], len(name_b), 0))
        out.write(hdr.encode() + name_b)
        out.write(b"\0" * ((4 - out.tell() % 4) % 4))
        out.write(data)
        out.write(b"\0" * ((4 - out.tell() % 4) % 4))
    seen = set()
    with tarfile.open(src) as t:
        members = t.getmembers()
        byname = {m.name.lstrip("./"): m for m in members}
        for m in members:
            n = m.name.lstrip("./")
            if not n or n == "init": continue
            perm = m.mode & 0o7777
            if m.isdir(): entry(n, stat.S_IFDIR | perm, nlink=2)
            elif m.issym(): entry(n, stat.S_IFLNK | 0o777, m.linkname.encode())
            elif m.islnk():
                tgt = byname[m.linkname.lstrip("./")]
                entry(n, stat.S_IFREG | perm, t.extractfile(tgt).read())
            elif m.isreg(): entry(n, stat.S_IFREG | perm, t.extractfile(m).read())
            seen.add(n)
    for d in ("dev", "proc", "sys", "tmp", "dev/pts"):
        if d not in seen: entry(d, stat.S_IFDIR | 0o755, nlink=2)
    entry("dev/console", stat.S_IFCHR | 0o600, rdev=(5, 1))
    entry("init", stat.S_IFREG | 0o755, INIT)
    entry("TRAILER!!!", 0)
    with open(dst, "wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=6, mtime=0) as f:
        f.write(out.getvalue())  # mtime=0 -> byte-for-byte reproducible

if __name__ == "__main__":
    main(*sys.argv[1:3])
