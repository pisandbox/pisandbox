# Changelog

All notable changes to PiSandbox. Versions follow `versionName (versionCode)`.

## 0.5.2 (7) — 2026-10-29
Tester feedback on 0.5.1 (works on the Galaxy S25; polish for the lessons). First public release candidate.

**Added**
* **Sounds.** A soft click when you press any button (tabs, key row, Game-mode buttons, Power / Restart / Lessons,
  settings controls, the GPIO header's input buttons) and quiet keyboard ticks while the terminal prints, as if it
  were being typed. The ticks never slow the output down: at most one every 50 ms, they stop about 0.1 s after the
  output stops, and a long burst (`apt install`, a tight print loop) goes quiet after about 2 s until the output
  pauses. Settings → **Button click sounds**, **Typing sounds** (both on) and **Sound volume** (default 30%,
  quiet). Nothing plays while the phone is on silent or vibrate. The sounds are generated in code (no audio files,
  about 3 KB of samples) and use the "sonification" audio channel. With our click off, Android's own touch sound
  setting applies.

**Fixed**
* **Every lesson stops cleanly** on ^C (B in Game mode, or Stop on the GPIO tab): it prints "Stopped.", switches
  its LEDs off and frees its pins (gpiozero `close()` / `GPIO.cleanup()`), exits with status 0 and shows no
  KeyboardInterrupt traceback. The reaction game also shows the best time so far.
* **`rpigpio_blink.py`** says what it is doing before it starts ("RPi.GPIO: blinking GPIO17 three times, then fading
  it…"), numbers the blinks and prints "Done." at the end.
* **Reaction game: a false start no longer counts as a time.** In 0.5.1 a press before the LED lit printed "Too
  early" and then timed the round anyway (e.g. 2073 ms). Now any press while it says "get ready" is a *false
  start*: it is reported, the LED does not light and the round is played again. Only real reactions count towards
  the best and average; the number of false starts is shown at the end. Holding the button after a press pauses
  the game until you let go.
* The Python GPIO client stays in step after a call is interrupted by ^C, so the cleanup that follows is reliable.
* Unedited copies in `~/examples` update automatically as before (edited ones are kept, the new one beside them).
  The hashes of every shipped version are now also kept in `rootfs/examples-shipped.sha256`, so this keeps working
  for builds from the source tarball or a squashed public history.

**Release preparation** (repository only)
* The project URL is set in one place (`pisandbox.projectUrl` in gradle.properties); `scripts/set-repo-owner.sh`
  sets the GitHub owner everywhere and rebuilds. `scripts/make-release-kit.sh` builds the GitHub release kit;
  docs/release/PUBLISH.md has the publish commands; `.github/workflows/pages.yml` deploys `site/` to GitHub Pages.
* Home: https://github.com/pisandbox/pisandbox (source link in Settings → About), website
  https://pisandbox.github.io/pisandbox/ (privacy policy at /privacy.html).
* Release APKs no longer embed the git commit id (AGP `vcsInfo`), so the same sources give a byte-identical APK.
* The rootfs tarball is attached to the release and pinned in scripts/assets.lock;
  `PISANDBOX_ROOTFS=prebuilt|build|auto` in fetch-assets.sh.

**Tests**
* JVM tests run each shipped lesson against the real GpioCore, send SIGINT and check "Stopped.", exit status 0, no
  traceback and every pin released (rpigpio_blink both during the blinks and during the PWM fade); that
  `rpigpio_blink.py` announces itself, really toggles GPIO17 three times and fades it; and that reaction-game false
  starts are reported, replayed and never timed (exactly five times, best = fastest real time).
* Guest check (proot session): all five examples stop cleanly on SIGINT and free GPIO 2/17/22/27.

## 0.5.1 (6) — 2026-10-29
Tester feedback: the traffic-lights lesson lit every LED green, which was confusing.

**Fixed / new**
* **Coloured LEDs** on the GPIO tab. Each pin can have its own LED colour (red, amber/yellow, green, blue, white,
  orange or `#rrggbb`); the default is still green. The colour is cleared when the pin is released.
* gpiozero **`TrafficLights(red, amber/yellow, green)`** colours its LEDs automatically — and so do the boards built
  on it (PiTraffic, PiStop, TrafficHat, …) and any `LEDBoard(red=…, green=…)` whose LEDs are named after a colour.
  Three stacked red/amber/green LEDs get a traffic-light housing on the header.
* Python: `pisandbox_gpio.led_color(pin_or_led, "red")`; shell: `gpio -g color 17 red`. Control protocol:
  new `color` command; `state` gains a 7th field (the colour). The Python `state()` helper accepts 6 or 7 fields.
* **Mission 3** rewired like a real signal, top to bottom: **red GPIO17, amber GPIO27, green GPIO22** (button
  GPIO2). Lesson text and `~/examples/README.txt` say which pin is which colour. Unedited copies of
  `traffic_lights.py` in `~/examples` update automatically; edited ones are kept with the new one beside them.
* The previous colour-by-pin-number palette (which happened to make 17, 22 and 27 all green) is gone.

**Tests**
* JVM tests for the colour protocol, the shipped traffic-lights lesson (red → amber → green sequence with a
  simulated GPIO2 press) and the reaction game (five rounds, best time) against the real GpioCore; conformance
  checks for gpiozero auto-colouring, `led_color` and `gpio color`; screenshot `traffic-colors.png` with a pixel
  hue check.

## 0.5.0 (5) — 2026-10-29
First release-ready build (not published).

**Release**
* Signed, R8-minified **release build** (`pisandbox-0.5.0-release.apk`), with keep rules for the Termux terminal
  classes and JNI. The release key differs from the debug key: **uninstall a 0.2–0.4 debug build once** before
  installing (docs/INSTALL.md).
* **About & licenses** screen (Settings): version, privacy statement, source-code link + written source offer,
  the full text of every bundled licence, trademark notice.
* Licence: GPL-3.0-or-later (LICENSE, NOTICE, THIRD_PARTY.md, docs/LICENSING.md). Privacy policy (PRIVACY.md,
  site/privacy.html), tester guide and feedback form (docs/TESTERS.md), static site (site/).

**Privacy**
* Removed AppCompat (and with it the AndroidX Startup / ProfileInstaller components); the release manifest has
  no providers or receivers and only INTERNET, ACCESS_NETWORK_STATE and FOREGROUND_SERVICE permissions.
* WebView usage metrics opt-out and Safe Browsing disabled (it would send URL hash prefixes to a third party);
  the app makes no network connections of its own.
* A friendly **crash screen** with "Copy details" (no network crash reporting).

**New**
* **Sand splash animation** (2 s, tap to skip; follows Android's "Remove animations"): sand fills the chip and the
  pi is drawn into it. On first run it shows **"Setting up Linux… N%"** while Debian unpacks.
* Linux keeps running in a **foreground service** (notification with Power off), so it survives leaving the app.
* Friendly start-up errors (not enough storage, incomplete install, shell exited) with Try again / Copy details.
* Upgrades refresh `~/examples` non-destructively: unchanged examples are updated, edited ones are kept and the new
  version is placed next to them.

**Fixes**
* File viewer crashed on Android 8–12 (`InputStream.readNBytes` is API 33) — found by lint.
* After process death, Linux restarted even if you had powered it off; now it stays off.
* Restart briefly showed "Stopped"; Power off (notification or app close) did not mark the stop as intentional,
  so a quick power-off could be reported as a start-up error.
* A shell that finished while no screen was open left a stale notification.
* Splash/neofetch drawing code had a syntax error in one helper; GPIO header no longer allocates while drawing.
* First-run space check counts cache Android can clear, and asks Android to clear it before unpacking.

## 0.4.0 (4)
Tabbed redesign (Terminal, Files, GPIO, Browser, Lessons, Settings), S25 bug fixes (B = Ctrl-C, DECCKM arrows,
unclipped key row, latching Ctrl/Alt), Robolectric screenshot/layout tests.

## 0.3.0 (3)
Hostname and device-tree fixes, GPIO shims (RPi.GPIO, gpiozero pin factory, `gpio` CLI) with the on-screen header,
handheld UI, in-app browser, runtime overlay re-applied on update.

## 0.2.0 (2)
Renamed to PiSandbox (`app.pisandbox`); Debian 12 rootfs with apt, Python, user `pi` + sudo, board identity.
