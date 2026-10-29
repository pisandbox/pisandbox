# Next steps (after v0.5)

## Done in v0.3
* GPIO bridge: the app's control server (abstract Unix socket `@app.pisandbox.ctl`, uid-checked, line protocol),
  an `RPi.GPIO` shim, a gpiozero `pisandbox` pin factory (the default via `GPIOZERO_PIN_FACTORY`), and the
  WiringPi-style `gpio` CLI. The on-screen 40-pin header shows LEDs for outputs and press-and-hold buttons for inputs.
* `browse` command and an in-app WebView browser.

## Done in v0.4
* S25 bug list (B = Ctrl-C, DECCKM arrows, unclipped single-line key row, CTRL/ALT latches) and the tabbed redesign
  (Terminal, Files, GPIO, Browser, Lessons, Settings; Game mode optional). Robolectric screenshot + clipping tests.

## Done in v0.5
* Release build (R8, release key), About & licenses, crash screen, sand splash, foreground service,
  non-destructive examples refresh, GPL-3.0-or-later + public-repo docs and static site (not published).
  Robolectric flow tests (process death, recreation, large font, split screen, dark mode, low storage, update path).

## Still open
0. **On-device check of 0.5.0** (release APK) on the S25 using docs/TESTERS.md: R8 at runtime, StrictMode log of the
   debug build, real power-off/SIGKILL, WebView navigation, first-run timing. Not possible here (no emulator: the
   host's KVM cannot run nested virtualisation).
   0.5.1 was confirmed working on the S25; 0.5.2 adds the lesson polish (clean ^C, false starts). Publishing is prepared: scripts/set-repo-owner.sh, scripts/make-release-kit.sh,
   docs/release/PUBLISH.md, .github/workflows/pages.yml. Owner: github.com/pisandbox (organisation), approved for publishing.
1. **lgpio / libgpiod shims**: code that imports `lgpio` directly, `gpioset`/`gpioget`, plus a C `libwiringPi.so`
   (only the CLI exists so far). Native code that mmaps `/dev/gpiomem` (pigpio) stays out of scope.
2. **I²C / SPI virtual devices**: a BME280-style sensor, an SSD1306 OLED drawn in the panel, an MCP3008 + potentiometer.
   Extend the protocol with `i2c`/`spi` transactions and install `smbus2`/`spidev` shims.
3. **More breadboard parts**: buzzer (PWM to a tone), RGB LED, servo angle, potentiometer slider, and per-pin
   colour choice. Optionally drag-and-drop wiring.
4. **Desktop (X11/VNC)**: as planned before (Xvnc + openbox/lxpanel theme as an optional download).
5. **Packaging**: build RPi.GPIO/gpiozero glue as a .deb so `apt` knows about it. Add a release key and a store build
   (remember to keep the Pi-free name and icon).
