#!/bin/bash
# PiSandbox pre-launch step. The app runs this as (fake) root under PRoot *before* every
# interactive session, with only the real /dev, /proc and /sys bound (no fake files yet).
# It (re)generates the files that the session then binds over the host's view:
#   /etc/pisandbox/generated/cpuinfo        -> /proc/cpuinfo   (real CPU blocks + board lines)
#   /etc/pisandbox/generated/version        -> /proc/version
#   /etc/pisandbox/generated/device-tree/   -> /proc/device-tree, /sys/firmware/devicetree/base
#   /etc/pisandbox/generated/fake-*         -> /proc/{stat,loadavg,uptime,vmstat} only when Android
#                                              hides the real ones from apps (SELinux)
# and writes two small files the app reads:
#   generated/binds    one "guest-src:guest-dest" per line (src relative to the guest root)
#   generated/uname    PRoot -k argument: \sysname\nodename\release\version\machine\domain\hwcap\
set -u
umask 022
# Never trust the caller's environment (v0.2 on Android ran this with PATH=/system/bin, so every
# external command failed, nothing was generated and the session fell back to the real hostname).
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LC_ALL=C
unset LD_LIBRARY_PATH LD_PRELOAD
P=/etc/pisandbox; G=$P/generated
rm -rf "$G.new"; mkdir -p "$G.new/device-tree/system"
MODEL="Generic ARM64 board"; REVISION="0"; HARDWARE=""; COMPATIBLE=""; KERNEL_SUFFIX=""
[ -r $P/board.conf ] && . $P/board.conf

# ---- one-time identity (persisted) ----
if [ ! -s $P/serial ]; then
  printf '10000000%08x\n' "$(od -An -N4 -tu4 /dev/urandom | tr -d ' ')" > $P/serial
fi
SERIAL=$(tr -d '\n' < $P/serial)
if [ ! -s /etc/machine-id ]; then
  tr -d '-' < /proc/sys/kernel/random/uuid > /etc/machine-id 2>/dev/null \
    || od -An -N16 -tx1 /dev/urandom | tr -d ' \n' > /etc/machine-id; echo >> /etc/machine-id
fi

# ---- device tree (strings are NUL-terminated, numbers big-endian, like the kernel exposes) ----
D=$G.new/device-tree
printf '%s\0' "$MODEL" > $D/model
# shellcheck disable=SC2086
printf '%s\0' $COMPATIBLE > $D/compatible
printf '%s\0' "$SERIAL" > $D/serial-number
printf '%s\0' "" > $D/name
be32() { local h; h=$(printf '%08x' "$((16#$1))"); printf "\\x${h:0:2}\\x${h:2:2}\\x${h:4:2}\\x${h:6:2}"; }
be32 "$REVISION" > $D/system/linux,revision
{ be32 "${SERIAL:0:8}"; be32 "${SERIAL:8:8}"; } > $D/system/linux,serial

# ---- /proc/cpuinfo: the phone's real CPU blocks (true features/core count) + board lines ----
if [ -r /proc/cpuinfo ] && grep -q '^processor' /proc/cpuinfo 2>/dev/null; then
  grep -Ev '^(Hardware|Revision|Serial|Model)[[:space:]]*:' /proc/cpuinfo | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}' > $G.new/cpuinfo
else
  n=$(nproc 2>/dev/null || echo 4)
  for ((i=0; i<n; i++)); do
    printf 'processor\t: %d\nBogoMIPS\t: 108.00\nFeatures\t: fp asimd evtstrm crc32 cpuid\nCPU implementer\t: 0x41\nCPU architecture: 8\nCPU variant\t: 0x0\nCPU part\t: 0xd08\nCPU revision\t: 3\n\n' $i
  done | sed '$d' > $G.new/cpuinfo
fi
{
  echo
  [ -n "$HARDWARE" ] && printf 'Hardware\t: %s\n' "$HARDWARE"
  printf 'Revision\t: %s\nSerial\t\t: %s\nModel\t\t: %s\n' "$REVISION" "$SERIAL" "$MODEL"
} >> $G.new/cpuinfo

# ---- kernel identity: keep the real version number (features match the real kernel) ----
REAL=$(uname -r); BASE=$(printf '%s' "$REAL" | grep -oE '^[0-9]+\.[0-9]+(\.[0-9]+)?' || echo "$REAL")
KREL="$BASE$KERNEL_SUFFIX"
KVER="#1 SMP PREEMPT PiSandbox (host kernel $REAL)"
printf 'Linux version %s (pisandbox@pisandbox) (gcc (Debian 12.2.0-14) 12.2.0, GNU ld (GNU Binutils for Debian) 2.40) %s\n' "$KREL" "$KVER" > $G.new/version
HOST=$(tr -d '\n' < /etc/hostname 2>/dev/null); HOST=${HOST:-pisandbox}
printf '\\Linux\\%s\\%s\\%s\\%s\\(none)\\-1\\\n' "$HOST" "$KREL" "$KVER" "$(uname -m)" > $G.new/uname

# ---- fallbacks for /proc files Android may deny to apps ----
NCPU=$(grep -c '^processor' $G.new/cpuinfo); NCPU=${NCPU:-4}
{
  echo "$G/cpuinfo:/proc/cpuinfo"
  echo "$G/version:/proc/version"
  echo "$G/device-tree:/proc/device-tree"
  echo "$G/device-tree:/sys/firmware/devicetree/base"
} > $G.new/binds
readable() { [ -z "${PISANDBOX_FAKE_PROC:-}" ] && head -c1 "$1" >/dev/null 2>&1; }
readable /proc/loadavg || { echo '0.12 0.07 0.02 1/120 1000' > $G.new/fake-loadavg; echo "$G/fake-loadavg:/proc/loadavg" >> $G.new/binds; }
readable /proc/uptime  || { echo '1234.56 4567.89' > $G.new/fake-uptime; echo "$G/fake-uptime:/proc/uptime" >> $G.new/binds; }
readable /proc/vmstat  || { printf 'nr_free_pages 146031\nnr_inactive_anon 196744\nnr_active_anon 26908\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\npgfault 0\npgmajfault 0\n' > $G.new/fake-vmstat; echo "$G/fake-vmstat:/proc/vmstat" >> $G.new/binds; }
readable /proc/stat    || {
  { echo 'cpu  1957 0 2877 93280 262 342 254 87 0 0'
    for ((i=0; i<NCPU; i++)); do echo "cpu$i 31 0 226 12027 82 10 4 9 0 0"; done
    echo 'intr 0'; echo 'ctxt 0'; echo 'btime 1700000000'; echo 'processes 100'
    echo 'procs_running 1'; echo 'procs_blocked 0'; echo 'softirq 0 0 0 0 0 0 0 0 0 0 0'
  } > $G.new/fake-stat; echo "$G/fake-stat:/proc/stat" >> $G.new/binds; }

rm -rf "$G"; mv "$G.new" "$G"
echo "prelaunch ok: $MODEL / rev $REVISION / serial $SERIAL / uname -r $KREL / $(wc -l < $G/binds) binds"
