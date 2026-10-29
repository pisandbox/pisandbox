**PiSandbox is a pocket Linux computer for learning to code.** It runs a real Debian 12 (bookworm, arm64) system
with Python 3.11, `apt` and `sudo` on your Android phone: no root, no account, and nothing to download on the first
start. It also simulates a 40-pin GPIO header, so the classic LED and button lessons work on screen.

This is the first public release.

## Highlights
* **Terminal** with an extra-keys row (^C, Esc, Tab, latching Ctrl/Alt, arrows) and an optional handheld Game mode.
* **Simulated GPIO**: `gpiozero`, `RPi.GPIO` and the `gpio` command drive an on-screen header. Outputs light up as
  LEDs and inputs are buttons you press and hold.
* **Coloured LEDs.** gpiozero `TrafficLights` lights up red, amber and green. The traffic-lights lesson is wired
  like a real signal (red GPIO17, amber GPIO27, green GPIO22), and you can set any LED's colour with
  `gpio -g color 17 red` or `pisandbox_gpio.led_color(...)`.
* **Lessons that stop politely (new in 0.5.2).** ^C, B in Game mode or the GPIO tab's Stop button ends every lesson
  with "Stopped.", switches its LEDs off and frees the pins, with no Python traceback. The reaction game no longer
  times a false start: pressing before the LED lights is reported and the round is played again.
* **Sounds (new in 0.5.2).** A soft click on every button and quiet keyboard ticks while the terminal prints.
  Both can be switched off in Settings, there is a volume slider (default: quiet), and nothing plays while the
  phone is on silent or vibrate.
* **Five lessons** (blink, button, traffic lights, reaction game, PWM fade with RPi.GPIO), a **file browser** that can run `.py`
  files, and a **browser** for web servers you start in Linux.
* Keeps running in the background, with a notification that has **Power off**.
* **Private by design**: no analytics, ads, crash reporting or third-party SDKs, and the app makes no network
  connections of its own. Read the [privacy policy](https://pisandbox.github.io/pisandbox/privacy.html).

Website: https://pisandbox.github.io/pisandbox/ · Full list of changes: [CHANGELOG.md](https://github.com/pisandbox/pisandbox/blob/main/CHANGELOG.md).

## Install
You need Android 8.0 or newer on a 64-bit ARM phone, and about **600 MB free** for the first start.

1. Download **`pisandbox-@VERSION@-release.apk`** (below) on the phone and open it.
2. When Android asks, let your browser or file manager **install unknown apps**: tap Settings, turn on
   *Allow from this source*, then go back and tap Install. On Samsung phones, turn off Auto Blocker while you
   install.
3. If Play Protect warns you, tap *More details → Install anyway*. The app targets Android 9 APIs on purpose.
4. Open PiSandbox. Setting up Linux takes 20–60 seconds the first time.

**Had a test (debug) build installed?** Copy anything you want to keep out of `/home/pi`, then uninstall the test
build. A release build cannot install over a debug-signed one. More help is in
[docs/INSTALL.md](https://github.com/pisandbox/pisandbox/blob/main/docs/INSTALL.md).

## Verify
```
sha256sum -c pisandbox-@VERSION@-release.apk.sha256      # or: sha256sum -c SHA256SUMS
apksigner verify --print-certs pisandbox-@VERSION@-release.apk
```
| File | SHA-256 |
|---|---|
| `pisandbox-@VERSION@-release.apk` (@APK_SIZE@) | `@APK_SHA256@` |
| `pisandbox-@VERSION@-rootfs-bookworm-arm64.tar.xz` | `@ROOTFS_SHA256@` |
| `pisandbox-@VERSION@-source.tar.gz` | `@SRC_SHA256@` |

Signing certificate SHA-256: `@CERT_SHA256@`

## Assets
* `pisandbox-@VERSION@-release.apk`: the app (versionCode @VERSION_CODE@).
* `pisandbox-@VERSION@-rootfs-bookworm-arm64.tar.xz`: the Debian rootfs inside the APK, byte for byte. Source
  builds download it with `PISANDBOX_ROOTFS=prebuilt scripts/fetch-assets.sh`, so they don't need to rebuild it.
* `pisandbox-@VERSION@-source.tar.gz`: the complete source of this release (`git archive`, tree `@TREE@`).
* `SHA256SUMS`

## Licence and source
PiSandbox is free software under the GPL-3.0-or-later. It bundles PRoot (GPL-2.0-or-later), talloc
(LGPL-3.0-or-later), Debian 12 packages, gpiozero/colorzero (BSD-3-Clause) and the Termux terminal libraries
(Apache-2.0). See [THIRD_PARTY.md](https://github.com/pisandbox/pisandbox/blob/main/THIRD_PARTY.md); the exact Debian
package list is in `rootfs/manifest-bookworm-arm64.txt`. The written source offer is in the app under
Settings → About & licenses.

Raspberry Pi is a trademark of Raspberry Pi Ltd. PiSandbox is not affiliated with or endorsed by Raspberry Pi Ltd.
Debian is a registered trademark of Software in the Public Interest, Inc.
