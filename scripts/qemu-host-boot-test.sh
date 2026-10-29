#!/usr/bin/env bash
# Host-only experiment kept from v0.1 (not packaged in the APK): boots Alpine's aarch64 "virt"
# kernel + an initramfs made from the Alpine minirootfs under full-system QEMU (pure TCG, no KVM).
#   ./scripts/qemu-host-boot-test.sh      -> automated self-test     -i -> interactive shell
set -euo pipefail
. "$(dirname "$0")/lib.sh"
H="$ROOT/build/host"; mkdir -p "$H"
[[ -f "$H/boot/vmlinuz-virt" ]] || tar -xzf "$(fetch alpine-netboot)" -C "$H" boot/vmlinuz-virt
python3 "$ROOT/scripts/mkinitramfs.py" "$(fetch alpine-minirootfs)" "$H/initramfs-pi.cpio.gz"
APPEND="console=ttyAMA0 quiet loglevel=3"
[[ "${1:-}" == "-i" ]] || APPEND="$APPEND pitest=1"
exec timeout ${QEMU_TIMEOUT:-300} qemu-system-aarch64 -M virt -cpu cortex-a72 -smp 2 -m 512 -accel tcg \
  -nographic -no-reboot -kernel "$H/boot/vmlinuz-virt" -initrd "$H/initramfs-pi.cpio.gz" -append "$APPEND"
