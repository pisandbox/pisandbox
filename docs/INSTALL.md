# Installing PiSandbox 0.5.2

Requirements: Android 8.0 or newer, 64-bit ARM (almost every phone since 2017; tested target Galaxy S25),
**about 600 MB free storage** for the first start.

## Which file?
| File | For |
|---|---|
| `pisandbox-0.5.2-release.apk` | everyone (signed with the release key) |
| `pisandbox-0.5.2-debug.apk` | developers; same features, debug key, StrictMode logging |

Check the download: `sha256sum -c pisandbox-0.5.2-release.apk.sha256`.

## Coming from a 0.2 / 0.3 / 0.4 test build? Uninstall it once first
Earlier test builds were **debug-signed**. Android only installs an update that is signed with the same key, so the
release build **cannot install over a debug build** — you will see "App not installed", "package conflicts with an
existing package", or `INSTALL_FAILED_UPDATE_INCOMPATIBLE` from adb.

1. Save anything you want from Linux first (uninstalling deletes `/home/pi` and installed packages). For example
   `cat ~/myprogram.py` and copy the text, or push your work to a git server.
2. Settings → Apps → PiSandbox → **Uninstall** (or `adb uninstall app.pisandbox`).
3. Install the release APK. From now on, release builds update each other and keep your files.

(A debug APK still installs over an older debug build if you just want to try the new version without losing files.)

Downloads: the **Releases** page of https://github.com/pisandbox/pisandbox (each release lists the APK, its `.sha256` and
the signing certificate). Release APKs are signed with the certificate SHA-256
`D2:3F:D5:39:31:A1:63:38:0A:D9:90:77:77:39:6D:17:C4:E1:E7:48:C1:0C:E3:A4:26:CE:27:E3:3D:F5:8B:C8`
(check with `apksigner verify --print-certs pisandbox-0.5.2-release.apk`).

## Install
1. Download the APK on the phone (or copy it over) and open it from the browser's downloads or a file manager.
   Android blocks apps from outside the Play Store until you allow the app that opens the APK to
   **install unknown apps**: tap **Settings** in the prompt, switch on **Allow from this source**, go back and tap
   **Install**. To set it beforehand: Settings → Apps → (⋮ or Special app access) → **Install unknown apps** →
   your browser (e.g. Chrome or Samsung Internet) or file manager (My Files) → **Allow from this source**.
   You can switch it off again after installing.
2. Samsung: if nothing happens, Settings → Security and privacy → **Auto Blocker** → off while installing.
3. If Play Protect warns ("built for an older version of Android"/"unknown app"), choose **More details → Install
   anyway**. PiSandbox targets Android 9 APIs on purpose: newer targets forbid running the Linux programs it unpacks.
4. From a computer: `adb install pisandbox-0.5.2-release.apk`.

## First start
The sand animation shows **"Setting up Linux… N%"** while Debian is unpacked (20–60 s, no download). Later starts
take about 2 s (tap to skip). If Android asks, allow notifications: Linux keeps running in the background with a notification that has
**Power off**.
