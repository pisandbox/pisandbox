#!/usr/bin/env bash
# Builds the GitHub release kit for the current commit into dist/release-v<version>/:
#   pisandbox-<v>-release.apk (+.sha256)                  rebuilt now from this exact commit, release-signed
#   pisandbox-<v>-rootfs-bookworm-arm64.tar.xz (+.sha256) the rootfs inside the APK (for source builds)
#   pisandbox-<v>-source.tar.gz (+.sha256)                git archive of HEAD (complete corresponding source)
#   SHA256SUMS, release-notes.md (the GitHub release body), PUBLISH.md (exact publish commands), KIT-INFO.txt
#   scripts/make-release-kit.sh [--preview]
# Needs a clean working tree (so the tarball is exactly what the APK was built from). While the project URL in
# gradle.properties is still the github.com/OWNER placeholder it refuses unless --preview is given; a preview kit
# must be regenerated after scripts/set-repo-owner.sh. Publishes nothing.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
PREVIEW=0; [[ "${1:-}" == --preview ]] && PREVIEW=1
[[ -f ~/tools/env.sh ]] && . ~/tools/env.sh
BT="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/35.0.0"
EXPECTED_CERT="d23fd53931a163380ad9907777396d17c4e1e748c10ce3a426ce27e33df58bc8"

[[ -z "$(git status --porcelain)" ]] || { echo "working tree not clean - commit first:"; git status --short; exit 1; }
URL=$(sed -n 's/^pisandbox\.projectUrl=//p' gradle.properties)
OWNER=$(sed -E 's#^https://github\.com/([^/]+)/pisandbox$#\1#' <<<"$URL")
if [[ "$OWNER" == OWNER ]]; then
  (( PREVIEW )) || { echo "project URL is still the placeholder ($URL): run scripts/set-repo-owner.sh <owner> first, or pass --preview" >&2; exit 1; }
fi
VER=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
VC=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
COMMIT=$(git rev-parse HEAD); TREE=$(git rev-parse 'HEAD^{tree}')
KIT="dist/release-v$VER"; RFS_NAME="pisandbox-rootfs-bookworm-arm64.tar.xz"
APK="pisandbox-$VER-release.apk"; RFS="pisandbox-$VER-rootfs-bookworm-arm64.tar.xz"; SRC="pisandbox-$VER-source.tar.gz"

echo "== checks"
grep -q "^pisandbox-rootfs .*/releases/download/v$VER/$RFS " scripts/assets.lock \
  || { echo "scripts/assets.lock: pisandbox-rootfs entry must point at release v$VER/$RFS" >&2; exit 1; }
LOCK_SHA=$(awk '$1=="pisandbox-rootfs"{print $3}' scripts/assets.lock)
GIT_SHA=$(awk '{print $1}' "rootfs/$RFS_NAME.sha256")
[[ "$LOCK_SHA" == "$GIT_SHA" ]] || { echo "assets.lock rootfs sha256 != rootfs/$RFS_NAME.sha256" >&2; exit 1; }
STAGED="app/src/main/assets/rootfs/$RFS_NAME"
[[ -f "$STAGED" ]] || scripts/fetch-assets.sh
[[ "$(sha256sum < "$STAGED" | cut -d' ' -f1)" == "$GIT_SHA" ]] || { echo "staged rootfs does not match rootfs/$RFS_NAME.sha256 - run scripts/fetch-assets.sh" >&2; exit 1; }
# the runtime overlay and PRoot libs come from tracked sources/pinned downloads; restage to be sure
scripts/build-runtime.sh >/dev/null && cp build/runtime/pisandbox-runtime.tar app/src/main/assets/runtime/

echo "== release build of $COMMIT"
./gradlew -q assembleRelease
OUT=app/build/outputs/apk/release/app-release.apk
[[ -f "$OUT" ]] || { echo "no signed release APK (keystore missing?)" >&2; exit 1; }
CERT=$("$BT/apksigner" verify --print-certs "$OUT" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
[[ "$CERT" == "$EXPECTED_CERT" ]] || { echo "unexpected signing certificate $CERT" >&2; exit 1; }
BADGE=$("$BT/aapt" dump badging "$OUT" | head -1)
grep -q "name='app.pisandbox' versionCode='$VC' versionName='$VER'" <<<"$BADGE" || { echo "badging mismatch: $BADGE" >&2; exit 1; }
unzip -p "$OUT" 'classes*.dex' | grep -a -q "$URL" || { echo "APK does not contain $URL" >&2; exit 1; }
[[ "$(unzip -p "$OUT" "assets/rootfs/$RFS_NAME" | sha256sum | cut -d' ' -f1)" == "$GIT_SHA" ]] || { echo "APK rootfs mismatch" >&2; exit 1; }

echo "== kit $KIT"
rm -rf "$KIT"; mkdir -p "$KIT"
cp "$OUT" "$KIT/$APK"
cp "$STAGED" "$KIT/$RFS"
git archive --format=tar --prefix="pisandbox-$VER/" HEAD | gzip -n -9 > "$KIT/$SRC"
( cd "$KIT"
  for f in "$APK" "$RFS" "$SRC"; do sha256sum "$f" > "$f.sha256"; done
  sha256sum "$APK" "$RFS" "$SRC" > SHA256SUMS )
sha() { cut -d' ' -f1 "$KIT/$1.sha256"; }
human() { numfmt --to=iec-i --suffix=B --format=%.1f "$(stat -c %s "$1")"; }
CERT_COLON=$(tr 'a-f' 'A-F' <<<"$CERT" | sed 's/../&:/g; s/:$//')
if (( PREVIEW )) && [[ "$OWNER" == OWNER ]]; then
  NOTE="> **PREVIEW KIT.** The project URL is still the placeholder \`$URL\`. Do step 1 first; it regenerates this kit with the real owner. Don't upload the files in this preview."
  OWNER_SH="your-github-name"
else NOTE=""; OWNER_SH="$OWNER"; fi
render() {
  sed -e "s|@VERSION@|$VER|g; s|@VERSION_CODE@|$VC|g; s|@APK_SHA256@|$(sha "$APK")|g; s|@ROOTFS_SHA256@|$(sha "$RFS")|g" \
      -e "s|@SRC_SHA256@|$(sha "$SRC")|g; s|@CERT_SHA256@|$CERT_COLON|g; s|@TREE@|$TREE|g; s|@COMMIT@|${COMMIT:0:12}|g" \
      -e "s|@APK_SIZE@|$(human "$KIT/$APK")|g; s|@ROOTFS_SIZE@|$(human "$KIT/$RFS")|g; s|@PROJECT_URL@|$URL|g" \
      -e "s|@OWNER@|$OWNER_SH|g; s|@KIT_DATE@|$(date '+%Y-%m-%d %H:%M %Z')|g; s|@NCOMMITS@|$(git rev-list --count HEAD)|g" "$1" \
  | awk -v note="$NOTE" '{ if ($0 == "@PREVIEW_NOTE@") { if (note != "") print note } else print }'
}
render "docs/release/release-notes-v$VER.md" > "$KIT/release-notes.md"
render docs/release/PUBLISH.md > "$KIT/PUBLISH.md"
! grep -n '@[A-Z_0-9]*@' "$KIT/release-notes.md" "$KIT/PUBLISH.md" || { echo "unrendered placeholder" >&2; exit 1; }
cat > "$KIT/KIT-INFO.txt" <<INFO
PiSandbox $VER (versionCode $VC) release kit
generated  $(date '+%Y-%m-%d %H:%M:%S %Z')
commit     $COMMIT
tree       $TREE
projectUrl $URL$( [[ "$OWNER" == OWNER ]] && echo "   <- PLACEHOLDER: preview only, regenerate after scripts/set-repo-owner.sh" )
cert       $CERT_COLON
INFO
(cd "$KIT" && sha256sum -c --quiet SHA256SUMS)
# the tarball must be exactly HEAD
mkdir -p build/kit-check && rm -rf build/kit-check/* && tar -xzf "$KIT/$SRC" -C build/kit-check
(cd "build/kit-check/pisandbox-$VER" && git init -q && git add -A -f && [[ "$(git write-tree)" == "$TREE" ]]) \
  || { echo "source tarball tree differs from HEAD" >&2; exit 1; }
rm -rf build/kit-check
ls -l "$KIT"
echo "kit ready: $KIT$( [[ "$OWNER" == OWNER ]] && echo ' (PREVIEW - placeholder owner)')"
