# PiSandbox — technical notes

How the Linux sandbox is built, started and verified. (Moved out of the README in 0.5.0.)

## What you get (since v0.2.0)

| | |
|---|---|
| Distro | Debian 12.x bookworm arm64 (official `debian:bookworm` rootfs, pinned) + `rootfs/packages.txt` |
| User | `pi` (uid 1000), groups as on Pi OS (adm dialout cdrom sudo audio video plugdev games users input render netdev gpio i2c spi), passwordless sudo |
| Python | 3.11.2, pip 23.0.1, venv (PEP 668 "externally managed" — use a venv, exactly like Pi OS) |
| Tools | sudo git curl wget nano vim-tiny less htop procps iproute2 ping ssh-client zip/unzip xz file bash-completion tzdata |
| Board identity | Pi 4 Model B Rev 1.5, revision `c03115`, `Hardware: BCM2835`, `compatible = raspberrypi,4-model-b brcm,bcm2711`, per-install serial (`rootfs/overlay/etc/pisandbox/board.conf`) |
| Kernel | the phone's own Android kernel; `uname -r` shows `<real x.y.z>+rpt-rpi-v8`, `uname -v` admits the real one |
| Hostname | `pisandbox` (faked via PRoot `-k`; Android won't let apps set it) |
| apt | works (`sudo apt update / install`), against deb.debian.org |

## How it works

```
MainActivity  ── Termux terminal-view/-emulator (PTY)
  └─ Sandbox.prepare()
      1. RootfsInstaller: first run only — assets/rootfs/pisandbox-rootfs-bookworm-arm64.tar.xz
         → filesDir/rootfs (pure Java: commons-compress + XZ for Java; hard links → copies,
         setuid bits kept, dir modes applied last; extracts to rootfs.partial then renames)
      2. /etc/resolv.conf ← Android's current DNS servers (+1.1.1.1, 8.8.8.8)
      3. proot -0 … bash /usr/lib/pisandbox/prelaunch.sh   (fake root, non-interactive)
           → /etc/pisandbox/generated/{cpuinfo,version,device-tree/,uname,binds,fake-*}
      4. proot -i 1000:1000 -k "<generated uname>" -b <generated binds> … env -i HOME=/home/pi
           USER=pi … LD_PRELOAD=libpisandbox-noaudit.so  /bin/bash -l
```

PRoot flags (identical in the app and in `scripts/lib.sh host_proot`): `--kill-on-exit
--link2symlink --sysvipc -L`, binds `/dev /proc /sys`, `/dev/urandom:/dev/random`,
`/proc/self/fd:/dev/fd`, `/dev/std{in,out,err}`, `rootfs/tmp:/dev/shm`.

**PRoot/apt quirks handled**
* `_apt` sandbox user: `APT::Sandbox::User "root"` (`etc/apt/apt.conf.d/90pisandbox-proot`) — the
  setuid/setgroups dance apt does for `_apt` fails under PRoot.
* fake root: `-0` for prelaunch/build, `-i 1000:1000` for the session; sudo (setuid) works under
  PRoot's fake-id layer. Everything on disk is really owned by the app uid.
* `link2symlink`: Android forbids hard links for apps; PRoot emulates them. Its artefacts in the
  *build* (`.l2s.*` files + absolute-path symlinks) are materialised by `scripts/pack-rootfs.py`.
* `/etc/ld.so.preload` is always masked to `/dev/null` by PRoot, so the sudo audit-noise shim
  (`rootfs/shim/noaudit.c`, NDK-compiled) is injected with `LD_PRELOAD` instead.
* dpkg under PRoot left `/etc/sudoers.d/README` 0640 → `visudo -c` failed; fixed in customize.sh.
* Android may hide `/proc/{stat,loadavg,uptime,vmstat}` from apps (SELinux); prelaunch detects
  that and binds plausible fakes (force with `PISANDBOX_FAKE_PROC=1` for testing).
* W^X: `targetSdk 28` + helper binaries shipped as `lib*.so` in jniLibs (legacy packaging), as in v0.1.

## Build

```bash
source ~/tools/env.sh
./scripts/fetch-assets.sh     # proot libs → jniLibs; builds the rootfs if needed (scripts/build-rootfs.sh)
./gradlew clean assembleDebug # use *clean*: incremental packaging leaves the old 70 MB asset as dead bytes
./scripts/host-verify.sh      # optional: run the app's exact flow on the x86 box (proot + qemu-aarch64)
```

`scripts/build-rootfs.sh` (≈4 min, no root): pinned base → `rootfs/overlay` → NDK-compiled shim →
`rootfs/customize.sh` run inside the arm64 guest via host PRoot + `qemu-aarch64`
(`scripts/host-tools.sh` builds both from pinned sources/debs) → normalise → `tar --sort=name
--numeric-owner | xz -9`. Writes `rootfs/manifest-bookworm-arm64.txt` (all installed package
versions — committed so changes are reviewable). Package versions come from deb.debian.org at
build time (snapshot.debian.org was unreachable from the build box), so the base is pinned
bit-for-bit and the result is pinned by manifest + tarball sha256, not by apt snapshot.

## Sizes

| | |
|---|---|
| APK | ~78 MB (rootfs tar.xz 72.9 MB stored uncompressed in assets + ~5 MB app/proot) |
| First-run download | **0** — rootfs is bundled; first run only unpacks |
| On-device disk | ~360–385 MB unpacked rootfs (+ the APK) |
| Alternative: fetch at runtime | base rootfs 48.4 MB + apt indexes ≈9 MB + packages 43.7 MB ≈ **~100 MB** download, several minutes of apt under ptrace on first run, and fragile on flaky networks; the APK would drop to ~5 MB |

## Layout

```
app/src/main/java/app/pisandbox/  MainActivity.kt  Sandbox.kt  RootfsInstaller.kt
app/src/main/res/                  neutral adaptive icon (slate chip with ">_"), strings, theme
rootfs/   packages.txt  customize.sh  overlay/  shim/noaudit.c  manifest-bookworm-arm64.txt
scripts/  assets.lock  lib.sh  fetch-assets.sh  build-rootfs.sh  pack-rootfs.py  host-tools.sh
          host-verify.sh  guest-checks.sh  qemu-host-boot-test.sh (v0.1 host-only QEMU experiment)
docs/NEXT-STEPS.md   GPIO shims + desktop plan
dist/                APK + sha256 + INSTALL-ON-S25.md
```

## Pi OS apt repository — deliberately not added

Tested on the build box: adding the upstream Pi OS apt repository for bookworm (with its key)
*works* under PRoot (apt update OK; python3-gpiozero 2.0.1+rpt1, python3-lgpio, raspi-config
resolvable). Not shipped because (a) apt output then shows "Raspberry Pi Foundation" as the
origin, which would put a third-party trademark in the terminal UI, and (b) a plain `apt upgrade` immediately
replaces core Debian packages with `+rpt` builds (libc6, dpkg, libpam*, zlib1g, python3-pip …) —
hardware-oriented forks we can't test here. GPIO libraries will come from our own shims instead
(see docs/NEXT-STEPS.md). Users can still add the repo themselves.

**Note:** Pi OS itself moved to Debian 13 "trixie" in late 2025. v0.2 stays on bookworm as
specified (matches the large installed base and most tutorials); moving to trixie is a
`build-rootfs.sh` base-image swap plus re-test (python 3.13, PEP 668 unchanged).

## Pinned artifacts (`scripts/assets.lock`)

Debian bookworm arm64 rootfs (debuerreotype, commit-pinned raw GitHub URL, sha256 = OCI layer
digest `9e2782a4…`); Termux proot 5.1.107.101 / libtalloc 2.5.0 / libandroid-shmem 0.7;
host-only: termux/proot v5.1.107.101 source, Debian qemu-user 10.0.13, talloc, make; Alpine
3.24.3 minirootfs + netboot for the old QEMU experiment. Gradle deps: Termux terminal libs
v0.118.3, commons-compress 1.27.1, org.tukaani:xz 1.10, androidx.annotation 1.9.1 (appcompat was removed in 0.5.0).

## Licenses

See [`THIRD_PARTY.md`](../THIRD_PARTY.md), [`legal/THIRD_PARTY_NOTICES.txt`](../legal/THIRD_PARTY_NOTICES.txt) (the text shown in the app) and [`docs/LICENSING.md`](LICENSING.md).

## Status

- [x] Rename → PiSandbox / `app.pisandbox`, neutral icon, versionName 0.2.0 (versionCode 2)
- [x] Debian 12 rootfs, apt, python3/pip/venv, user pi + sudo, Pi OS prompt, /boot/firmware stubs
- [x] Pi 4 identity: device-tree, cpuinfo, /proc/version, uname, hostname
- [x] Host verification (x86 box, proot + qemu-user): all checks pass, see below
- [x] v0.3.0: hostname/device-tree fix, GPIO shims + on-screen header, handheld UI, browser (host-verified; JUnit incl. Python-shim ⇄ Kotlin-core conformance)
- [ ] On-device test on the S25 (the only test that covers Android SELinux/seccomp/W^X)
- [x] v0.5.0: foreground service keeps the session alive; About & licenses screen; release build
- [ ] Multiple sessions

### Host verification (`scripts/host-verify.sh`, also with `FAKE_PROC=1`)
Unpacks the APK's tarball with the app's semantics, runs prelaunch, starts the `pi` session with
the app's flags and checks: id/hostname/prompt; device-tree model + linux,revision; cpuinfo
Revision/Model; /boot/firmware; uname suffix; /proc fallbacks; `sudo id` (no audit noise);
`sudo apt-get update` + install; `python3 -c 'print(1)'`; PEP 668; venv + pip install. On the
host, qemu-user answers `/proc/cpuinfo` itself, so that one check reads the bound source file
(bind mechanism itself verified separately); no qemu exists on the phone.
