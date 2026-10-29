# Release checklist (maintainers)

Nothing here publishes anything. These are the steps for whoever decides to make a public release.

## Signing key
* Release keystore: `~/keys/pisandbox-release.jks` (PKCS12, alias `pisandbox`, RSA 4096, valid to 2054), password in
  `~/keys/pisandbox-release.pass` (mode 600, directory 700). **Never commit either** (`.gitignore` blocks `*.jks`,
  `*.pass`, `keys/`). Backup instructions: `~/keys/README-BACKUP.txt`.
* Certificate SHA-256: `D2:3F:D5:39:31:A1:63:38:0A:D9:90:77:77:39:6D:17:C4:E1:E7:48:C1:0C:E3:A4:26:CE:27:E3:3D:F5:8B:C8`
  (publish this so users can verify APKs: `apksigner verify --print-certs`).
* **If the key is lost, existing installs can never be updated** — keep two offline copies (see the backup note).
* Other machines: `-Ppisandbox.keystore=/path/key.jks` (password file next to it, same name with `.pass`). Without a
  keystore `assembleRelease` produces an unsigned APK.
* Google Play: if you use Play App Signing, this key becomes the *upload* key — decide before the first upload.

## Before tagging
1. Set the public source URL (shown in About and in the written offer). It lives in one place,
   `pisandbox.projectUrl` in `gradle.properties`; `scripts/set-repo-owner.sh <owner>` sets it, rewrites the same link
   in README/docs/site/PRIVACY.md/`scripts/assets.lock` and rebuilds both APKs (bumps nothing). The
   `OWNER` placeholder in that URL must not ship: release builds warn about it and
   `scripts/make-release-kit.sh` refuses it (except with `--preview`).
2. Bump `versionCode`/`versionName` in `app/build.gradle.kts` and `rootfs/runtime/etc/pisandbox/version`; update CHANGELOG.
3. `./gradlew clean assembleDebug assembleRelease testDebugUnitTest lint` — all green, lint 0 errors.
4. `SIM_ANDROID=1 scripts/host-verify.sh` — all PASS. `scripts/render-screens.sh`, `scripts/render-splash.sh`.
5. Install the release APK on a real phone and run docs/TESTERS.md (R8 problems only show at runtime).
6. Copy to `dist/` with `.sha256` files.
7. Commit, then `scripts/make-release-kit.sh` → `dist/release-v<version>/` (release APK, rootfs asset, `git archive`
   source tarball, SHA256SUMS, release notes from `docs/release/release-notes-v<version>.md`, PUBLISH.md from
   `docs/release/PUBLISH.md`). For a new version: copy the notes template, and in `scripts/assets.lock` point the
   `pisandbox-rootfs` entry at the new release tag (and its SHA-256 if the rootfs was rebuilt).

## Source-offer obligations (GPL)
Shipping the APK ships GPL binaries (PRoot, Debian packages). Keep, for at least 3 years per release:
* this repository at the release tag (includes `rootfs/manifest-bookworm-arm64.txt`, the exact package list);
* the PRoot/talloc sources named in `scripts/assets.lock`;
* ideally an archive of the Debian source packages for the manifest versions (`apt-get source` against
  snapshot.debian.org), in case the snapshot service is unreachable when someone asks.

## Rootfs and big binaries
APKs, the 73 MB rootfs tarball and release kits are never committed (`.gitignore`); only their `.sha256` files are.
The rootfs is reproducible from the recipe in git (`scripts/build-rootfs.sh`, `rootfs/`), but package versions come
from the live Debian mirror, so the exact bytes shipped in an APK are attached to its GitHub release and pinned in
`scripts/assets.lock` (`PISANDBOX_ROOTFS=prebuilt scripts/fetch-assets.sh`). `rootfs/manifest-bookworm-arm64.txt`
lists every package version for the source offer.

## Publishing (not done; needs the owner's decision and accounts)
Step-by-step commands: [docs/release/PUBLISH.md](release/PUBLISH.md) (rendered with the real checksums into
`dist/release-v<version>/PUBLISH.md` by `scripts/make-release-kit.sh`). In short: set the owner, commit, build the kit,
`gh repo create`, push a clean `main`, tag, `gh release create` with the kit files, enable Pages (Actions source;
`.github/workflows/pages.yml` deploys `site/`).
* Stores need the privacy policy URL (`https://<owner>.github.io/pisandbox/privacy.html`), the trademark line in the
  listing, and a store icon/feature graphic without third-party logos.
