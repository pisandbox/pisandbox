#!/usr/bin/env bash
# Render every tab at Galaxy S25 size (1080x2340, 420dpi) with Robolectric -> dist/screens/*.png,
# plus no-clipping / no-wrapping layout assertions (also at 360dp).
# Builds a *host* (x86_64) copy of termux's tiny pty JNI (tools/hostjni/termux.c, Apache-2.0) so the terminal card
# shows real emulator output for a scripted sample session; without it the terminal card renders empty.
set -euo pipefail
cd "$(dirname "$0")/.."
source ~/tools/env.sh 2>/dev/null || true
gcc -D_GNU_SOURCE -shared -fPIC -O2 -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    tools/hostjni/termux.c -o tools/hostjni/libtermux.so
./gradlew --no-daemon testDebugUnitTest --tests 'app.pisandbox.ScreensTest'
ls -la dist/screens
