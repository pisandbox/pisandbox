# Publishing PiSandbox @VERSION@ on GitHub

Everything is prepared locally, and nothing in this file has been run yet. Run the steps **on the build box, in the
repository** (`cd pi-android-proto`), because that is where the signing key is. Each step says what it changes.
Kit generated: @KIT_DATE@ from commit `@COMMIT@` (tree `@TREE@`). Project URL compiled into the APK:
`@PROJECT_URL@`.

@PREVIEW_NOTE@

## 0. Accounts and tools (once)
* Create a free GitHub account at https://github.com/signup (or an organisation). Its name is the **owner**.
* Install GitHub CLI 2.x (https://cli.github.com) and run `gh auth login` (GitHub.com, HTTPS, log in with a
  browser), then `gh auth setup-git` so `git push` uses the same login.

```bash
OWNER=@OWNER@          # <- the GitHub user or organisation name
VER=@VERSION@
```

## 1. Put the real owner into the app and the docs (local only)
```bash
scripts/set-repo-owner.sh "$OWNER"   # replaces the OWNER placeholder everywhere, rebuilds both APKs into dist/
git diff --stat                      # gradle.properties, README, docs, site, PRIVACY.md, scripts/assets.lock, ...
./gradlew testDebugUnitTest lint     # optional re-check (about 3 min)
git add -A && git commit -m "Set repository owner to $OWNER"
scripts/make-release-kit.sh          # regenerates dist/release-v$VER/ from this commit (refuses a dirty tree)
```
The version stays @VERSION@ (versionCode @VERSION_CODE@). The rebuilt release APK differs from the earlier one
only in the source link. Phones with an earlier release build (0.5.0 or later, same key) can install @VERSION@ over the top.

## 2. Create the empty public repository (on GitHub)
```bash
gh repo create "$OWNER/pisandbox" --public \
  --description "A pocket Linux computer for learning: Debian 12 + Python + a virtual GPIO header on Android" \
  --homepage "https://${OWNER}.github.io/pisandbox/" --disable-wiki
```
Don't add a README, licence or .gitignore on GitHub. The repository has to start empty.
(Web alternative: github.com → New repository → name `pisandbox`, Public, no template files.)

## 3. Choose the public history (local only)
The local `master` branch has @NCOMMITS@ commits. The early ones contain an old prototype name and package
(`dev.russell.piproto`), a first name, and test-phone notes. **Recommended:** publish one clean commit that has
exactly the same files:
```bash
git branch -f main "$(git commit-tree 'HEAD^{tree}' -m "PiSandbox $VER - first public release")"
git rev-parse 'main^{tree}'          # must print the tree in dist/release-v$VER/KIT-INFO.txt
```
The commit gets git's configured name and email, or `PiSandbox build <build@localhost>` if that's the
identity you want to show publicly. To use another one, prefix the command with
`GIT_AUTHOR_NAME=... GIT_AUTHOR_EMAIL=... GIT_COMMITTER_NAME=... GIT_COMMITTER_EMAIL=...`
(a `<user>@users.noreply.github.com` address keeps your real email private).
To publish the full history instead: `git branch -f main HEAD`.

## 4. Push and tag (public from here on)
```bash
git remote add origin "https://github.com/$OWNER/pisandbox.git"
git push -u origin main
git tag -a "v$VER" main -m "PiSandbox $VER"
git push origin "v$VER"
```

## 5. Create the release with its files
```bash
cd "dist/release-v$VER"
sha256sum -c SHA256SUMS
gh release create "v$VER" --repo "$OWNER/pisandbox" --verify-tag --latest \
  --title "PiSandbox $VER" --notes-file release-notes.md \
  "pisandbox-$VER-release.apk" "pisandbox-$VER-release.apk.sha256" \
  "pisandbox-$VER-rootfs-bookworm-arm64.tar.xz" "pisandbox-$VER-rootfs-bookworm-arm64.tar.xz.sha256" \
  "pisandbox-$VER-source.tar.gz" "pisandbox-$VER-source.tar.gz.sha256" \
  SHA256SUMS
cd -
```
(Web alternative: Releases → Draft a new release → tag `v$VER` → paste `release-notes.md` → drag in the same 7
files.) The rootfs file (@ROOTFS_SIZE@) is what `PISANDBOX_ROOTFS=prebuilt scripts/fetch-assets.sh` downloads.
GitHub allows release assets of up to 2 GiB each.

## 6. Website and privacy policy (GitHub Pages)
`site/` (index.html, privacy.html, style.css, img/) is deployed by `.github/workflows/pages.yml`. Branch-based Pages
can only serve the repository root or `/docs`, so this site needs the Actions workflow. All of its links are
relative, so it works under `/pisandbox/`.
```bash
gh api -X POST "repos/$OWNER/pisandbox/pages" -f build_type=workflow   # Settings → Pages → Source: GitHub Actions
gh workflow run pages.yml --repo "$OWNER/pisandbox" --ref main && sleep 10
gh run watch --repo "$OWNER/pisandbox" "$(gh run list --repo "$OWNER/pisandbox" --workflow pages.yml --limit 1 --json databaseId -q '.[0].databaseId')"
```
The push in step 4 already started one Pages run. It fails because Pages wasn't switched on yet, which is
harmless; the manual run above is the one that counts. Then check:
`https://${OWNER}.github.io/pisandbox/` and `https://${OWNER}.github.io/pisandbox/privacy.html` (the privacy-policy
URL for app stores). Pages on a free account requires a public repository, which this one is.

## 7. Check it like a user
```bash
cd "$(mktemp -d)" && gh release download "v$VER" --repo "$OWNER/pisandbox" && sha256sum -c SHA256SUMS
```
* On the phone: open `https://github.com/$OWNER/pisandbox/releases/latest`, install the APK, and check that
  Settings → About & licenses shows `https://github.com/$OWNER/pisandbox`.
* Optional repository settings: About → topics (`android`, `linux`, `education`, `python`, `gpio`); Settings →
  Security → enable private vulnerability reporting.

## Keep (GPL source offer)
For at least 3 years, keep the `v$VER` tag, the source tarball and the rootfs asset published. Back up
`~/keys/pisandbox-release.jks` and its `.pass` file offline (see `~/keys/README-BACKUP.txt`). Without them,
nobody can ever ship an update that installs over this release.
