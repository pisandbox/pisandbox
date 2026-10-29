# Shared helpers (sourced). ROOT = repo root.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.cache/downloads"; mkdir -p "$CACHE"
HT="$ROOT/build/hosttools"          # host proot + qemu-aarch64 (scripts/host-tools.sh)

fetch() { # lock-name -> prints path of the verified file
  local line url sha f
  line=$(grep -E "^$1 " "$ROOT/scripts/assets.lock") || { echo "no lock entry for $1" >&2; exit 1; }
  url=$(awk '{print $2}' <<<"$line"); sha=$(awk '{print $3}' <<<"$line")
  f="$CACHE/$1--$(basename "$url")"
  if [[ ! -f "$f" ]]; then echo "downloading $1" >&2; curl -fsSL --retry 3 -o "$f.part" "$url" && mv "$f.part" "$f"; fi
  echo "$sha  $f" | sha256sum -c --quiet - >&2 || { echo "SHA256 MISMATCH for $1" >&2; rm -f "$f"; exit 1; }
  echo "$f"
}

debx() { # extract a .deb's data.tar.* into dir (no dpkg needed)
  local deb=$1 out=$2 tmp; tmp=$(mktemp -d); mkdir -p "$out"
  (cd "$tmp" && ar x "$deb"); tar -xf "$tmp"/data.tar.* -C "$out"; rm -rf "$tmp"
}

# Run a command in an arm64 rootfs on the x86_64 build host, with the same PRoot build/flags
# the app uses (see ProotBackend.kt) plus "-q qemu-aarch64" for CPU emulation.
#   ID=-0 | "-i 1000:1000"   EXTRA=(more proot args)   WD=/root
host_proot() {
  local rootfs=$1; shift
  [[ -x "$HT/proot" ]] || "$ROOT/scripts/host-tools.sh" >&2
  mkdir -p "$ROOT/build/proot-tmp"
  PROOT_TMP_DIR="$ROOT/build/proot-tmp" "$HT/proot" --kill-on-exit --link2symlink --sysvipc -L ${ID:--0} \
    -q "$HT/qemu-aarch64" -r "$rootfs" \
    -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random -b /proc/self/fd:/dev/fd \
    -b /proc/self/fd/0:/dev/stdin -b /proc/self/fd/1:/dev/stdout -b /proc/self/fd/2:/dev/stderr \
    -b "$rootfs/tmp:/dev/shm" "${EXTRA[@]}" -w "${WD:-/root}" "$@"
}
