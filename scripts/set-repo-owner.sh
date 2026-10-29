#!/usr/bin/env bash
# Sets the GitHub owner (user or organisation) of the public repository, then rebuilds.
#   scripts/set-repo-owner.sh <owner> [--no-build]
# * The project URL lives in ONE place, gradle.properties (pisandbox.projectUrl); the app reads it as
#   BuildConfig.PROJECT_URL (About screen + GPL written source offer).
# * The same URL also appears as plain text in docs, README, site/ and scripts/assets.lock (prebuilt rootfs
#   download). This script rewrites every tracked text file: github.com/<old>/pisandbox -> github.com/<owner>/pisandbox
#   and <old>.github.io/pisandbox -> <owner>.github.io/pisandbox, where <old> is the owner currently in
#   gradle.properties (OWNER at first; re-running with another name works).
# * Bumps nothing (versionName/versionCode unchanged). Rebuilds dist/pisandbox-<ver>-debug.apk and
#   -release.apk (+ .sha256), overwriting the existing files of that version, and checks the new URL is in both.
# * Does not commit, push or contact anything. Afterwards: review `git diff`, commit, scripts/make-release-kit.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
NEW="${1:-}"; BUILD=1; [[ "${2:-}" == --no-build ]] && BUILD=0
# GitHub user/org names: 1-39 chars, letters/digits/single hyphens, no leading/trailing hyphen.
if ! [[ "$NEW" =~ ^[A-Za-z0-9]([A-Za-z0-9]|-[A-Za-z0-9]){0,38}$ ]] || [[ "$NEW" == OWNER ]]; then
  echo "usage: $0 <github-owner> [--no-build]   (e.g. $0 octocat)" >&2; exit 2
fi
CUR_URL=$(sed -n 's/^pisandbox\.projectUrl=//p' gradle.properties)
[[ "$CUR_URL" =~ ^https://github\.com/([^/]+)/pisandbox$ ]] || { echo "gradle.properties: unexpected pisandbox.projectUrl=$CUR_URL" >&2; exit 1; }
OLD="${BASH_REMATCH[1]}"
NEW_LC=$(tr '[:upper:]' '[:lower:]' <<<"$NEW"); OLD_LC=$(tr '[:upper:]' '[:lower:]' <<<"$OLD")
echo "owner: $OLD -> $NEW"

mapfile -t FILES < <(git grep -I -l -i -E "github\.com/$OLD/pisandbox|$OLD\.github\.io/pisandbox" -- . ':!dist/release-*' || true)
for f in "${FILES[@]}"; do
  sed -i -E "s#github\.com/$OLD/pisandbox#github.com/$NEW/pisandbox#g; s#$OLD_LC\.github\.io/pisandbox#$NEW_LC.github.io/pisandbox#gI" "$f"
  echo "  updated $f"
done
grep -q "^pisandbox.projectUrl=https://github.com/$NEW/pisandbox$" gradle.properties || { echo "gradle.properties not updated" >&2; exit 1; }
LEFT=$(git grep -I -n -E "github\.com/$OLD/pisandbox|$OLD\.github\.io" -- . ':!dist/release-*' || true)
[[ -z "$LEFT" ]] || { echo "still referencing $OLD:"; echo "$LEFT"; exit 1; }

(( BUILD )) || { echo "skipped the rebuild (--no-build)"; exit 0; }
[[ -f app/src/main/assets/rootfs/pisandbox-rootfs-bookworm-arm64.tar.xz ]] || scripts/fetch-assets.sh
[[ -f ~/tools/env.sh ]] && . ~/tools/env.sh
./gradlew -q assembleDebug assembleRelease
VER=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
mkdir -p dist
for t in debug release; do
  src="app/build/outputs/apk/$t/app-$t.apk"; dst="pisandbox-$VER-$t.apk"
  [[ -f "$src" ]] || { echo "missing $src (release unsigned? keystore at ~/keys/pisandbox-release.jks)" >&2; exit 1; }
  cp "$src" "dist/$dst"; (cd dist && sha256sum "$dst" > "$dst.sha256")
  # the URL must be compiled in (a string constant in the dex)
  unzip -p "dist/$dst" 'classes*.dex' | grep -a -q "https://github.com/$NEW/pisandbox" \
    || { echo "$dst does not contain the new project URL" >&2; exit 1; }
  echo "  dist/$dst  $(cut -c1-16 "dist/$dst.sha256")…"
done
echo "done. Next: git diff; commit; scripts/make-release-kit.sh"
