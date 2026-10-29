#!/usr/bin/env bash
# Splash preview: renders the real SandView frames with Robolectric (SplashTest) and turns them into GIFs:
#   dist/screens/splash.gif           normal start (2.0 s intro + 0.3 s fade into the app)
#   dist/screens/splash-first-run.gif first start ("Setting up Linux… N%"; the unpack is sped up)
# plus a still: dist/screens/splash.png. The device draws at the display rate (60/120 fps); the GIF is 50 fps.
set -euo pipefail
cd "$(dirname "$0")/.."
source ~/tools/env.sh 2>/dev/null || true
./gradlew -q testDebugUnitTest --tests 'app.pisandbox.SplashTest'
F=build/splash-frames
mk() { # $1 frames dir, $2 output gif
  ffmpeg -v error -y -framerate 50 -i "$1/%04d.png" -vf "scale=360:-1:flags=lanczos,palettegen=max_colors=96:stats_mode=diff" "$F/palette.png"
  ffmpeg -v error -y -framerate 50 -i "$1/%04d.png" -i "$F/palette.png" \
    -lavfi "scale=360:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" -loop 0 "$2"
}
mk "$F/intro" dist/screens/splash.gif
mk "$F/firstrun" dist/screens/splash-first-run.gif
cp "$F/intro/0090.png" dist/screens/splash.png
ls -la dist/screens/splash*
