#!/bin/bash
# Runs INSIDE the arm64 guest (PRoot -0 + qemu-aarch64 on the build host) as fake root.
# Turns the stock debian:bookworm rootfs into the PiSandbox rootfs.
set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive LC_ALL=C.UTF-8
PKGS=$(grep -Ev '^\s*(#|$)' /tmp/pisandbox-build/packages.txt | tr '\n' ' ')

apt-get update
apt-get -y upgrade
apt-get install -y --no-install-recommends $PKGS

# --- Pi-OS-style default account: "pi" (uid/gid 1000), passwordless sudo, usual groups ---
for g in input netdev gpio i2c spi render; do getent group $g >/dev/null || groupadd -r $g; done
getent group pi >/dev/null || groupadd -g 1000 pi
id pi >/dev/null 2>&1 || useradd -m -u 1000 -g 1000 -s /bin/bash \
  -G adm,dialout,cdrom,sudo,audio,video,plugdev,games,users,input,render,netdev,gpio,i2c,spi pi
passwd -d pi >/dev/null          # no password (sudo is NOPASSWD; matches an unconfigured image)
# dpkg under PRoot can leave sudoers.d/README 0640; visudo/sudo insist on 0440.
chmod 0440 /etc/sudoers /etc/sudoers.d/*
visudo -c

# --- Pi-OS-style bash prompt:  pi@pisandbox:~ $  (green user@host, blue cwd, space before $) ---
python3 - /etc/skel/.bashrc /home/pi/.bashrc <<'PY'
import sys
color = r"""PS1='${debian_chroot:+($debian_chroot)}\[\033[01;32m\]\u@\h\[\033[00m\]:\[\033[01;34m\]\w \$\[\033[00m\] '"""
plain = r"""PS1='${debian_chroot:+($debian_chroot)}\u@\h:\w \$ '"""
for rc in sys.argv[1:]:
    out, n = [], 0
    for line in open(rc):
        st = line.strip()
        if st == "#force_color_prompt=yes":
            line = "force_color_prompt=yes\n"
        elif st.startswith("PS1='${debian_chroot") and "\\033[01;32m" in st:
            line = "    " + color + "\n"; n += 1
        elif st.startswith("PS1='${debian_chroot"):
            line = "    " + plain + "\n"; n += 1
        out.append(line)
    assert n == 2, (rc, n)
    open(rc, "w").write("".join(out))
PY
chown -R 1000:1000 /home/pi

# --- quiet sudo/su under PRoot (no kernel audit available to apps) ---
install -m 0644 /tmp/pisandbox-build/libpisandbox-noaudit.so /usr/lib/aarch64-linux-gnu/
# Loaded via LD_PRELOAD from the app's session environment (Sandbox.kt). NOT /etc/ld.so.preload:
# PRoot always masks that path with /dev/null, so it would silently do nothing.

# --- misc ---
ln -sf /usr/share/zoneinfo/Etc/UTC /etc/localtime; echo Etc/UTC > /etc/timezone
rm -f /etc/resolv.conf                     # written by the app at every launch
apt-get clean
rm -rf /var/lib/apt/lists/* /var/cache/apt/*.bin /var/log/apt/* /var/log/dpkg.log /var/log/alternatives.log \
       /tmp/* /var/tmp/* /root/.cache /var/cache/debconf/*-old /var/lib/dpkg/*-old
dpkg-query -W -f='${Package}\t${Version}\t${Architecture}\n' | sort > /root/pisandbox-manifest.txt
python3 --version; pip3 --version; sudo --version | head -1
