# Releasing Koggles

A release is a signed APK on the repository's GitHub Releases page. It's produced by
[`.github/workflows/release.yml`](.github/workflows/release.yml) when a version tag is pushed.
The design and its review are in [`plans/RELEASE-HARDENING.md`](plans/RELEASE-HARDENING.md).

## Tags and versions

- Tags are `vX.Y.Z`, each part `0`–`99`, written without leading zeros: `v0.1.0` and
  `v12.34.56` are valid; `v01.2.3`, `v1.2` and `v1.2.3-rc1` are not.
- `versionName` is `X.Y.Z` and `versionCode = X·10000 + Y·100 + Z`
  (`tools/ci/version-from-tag.sh`).
- A release must have a higher `versionCode` than every published (non-draft) release, otherwise
  Android refuses to install it over the previous one. The `publish` job enforces this.
- Release tags must point at a commit on `main`.
- Releases with major version `0` are marked as pre-releases.

## One-time setup

**1. Create the release key.** This happens on your machine, once.

```sh
keytool -genkeypair -v -keystore ~/.android/koggles-release.jks -alias koggles \
  -keyalg RSA -keysize 4096 -validity 10000
```

Passwords must be **ASCII**; Java's PKCS12 keystores reject anything else. Then **back up** the
`.jks` file and both passwords somewhere off this machine. If the key is lost, future releases
can't upgrade existing installs; users would have to uninstall and reinstall.

**2. Pin the certificate.** It's public, so it goes in a repository variable, not a secret:

```sh
keytool -list -v -keystore ~/.android/koggles-release.jks -alias koggles | grep SHA256:
gh variable set KOGGLES_CERT_SHA256 --body '<the SHA256 value>'
```

**3. Create the `release` Environment.** In GitHub, go to Settings → Environments → New
environment `release`, then:

- **Required reviewers:** add yourself.
- **Deployment branches and tags:** "Selected", with tag pattern `v*` and branch `main`.

**4. Store the signing secrets in that Environment.** Each command prompts for its value, so
nothing lands in your shell history:

```sh
base64 -w0 ~/.android/koggles-release.jks | gh secret set KOGGLES_KEYSTORE_B64 --env release
gh secret set KOGGLES_STORE_PASSWORD --env release
gh secret set KOGGLES_KEY_ALIAS --env release        # koggles
gh secret set KOGGLES_KEY_PASSWORD --env release
```

**5. Add rulesets.** In Settings → Rules:

- a **tag** ruleset on `v*` that restricts creation, update and deletion to admins;
- a **branch** ruleset on `main` that blocks force pushes and deletion.

For local release builds, create `keystore.properties` at the repository root (see README). It
is gitignored and must never be committed.

## Dry run (no Release is created)

```sh
gh workflow run release.yml --ref main -f version=0.0.1
```

1. Approve the `release` environment when asked.
2. When the run is green, download only the release files into `dist/`:

   ```sh
   gh run download <run-id> -n koggles-v0.0.1 -D dist
   ```

3. Check the APK yourself, with the Android SDK on the path:

   ```sh
   source tools/env.sh
   tools/ci/verify-release-apk.sh dist/koggles-v0.0.1.apk dist/koggles-v0.0.1-mapping.txt 0.0.1 1 \
     "$(gh variable get KOGGLES_CERT_SHA256)"
   ```

A dispatched run never publishes; only a tag push does.

## Publishing

**Stop unless the MVP sign-off holds.** The workflow checks the build, not these. Every item must
be true before you tag (`plans/RELEASE-HARDENING.md` R8, Blueprint Task 21 Step 8):

- every gate record in `docs/verification/` says PASS: G1–G4, including G2's physical-phone
  matrix, G3's headset fit and G4's on-phone performance;
- every row in `docs/verification/manual-checklist.md` says PASS, including the release-build
  walk-through on a physical phone. Rows under "After publishing" are the exception: you check
  them once the release exists;
- every row in `docs/claude-response/2026-10-11-koggles-rulings-for-human.md` is resolved
  (remediation R4);
- the release-plan decisions H6 (protections) and H7 (licensing for public distribution) are
  made;
- all automated suites are green, and a dry run of this exact commit passed.

If anything is open, don't tag. Then, on an up-to-date `main`:

```sh
git tag -a v0.1.0 -m "Koggles 0.1.0: <what changed>"
git push origin v0.1.0
```

Then approve the `release` environment. The tag message becomes the top of the release notes.
The workflow:

1. builds and signs;
2. runs the static checks (signature and pinned certificate, version, native libraries,
   ≥ 9000 presets, licence files, JNI upcalls kept by R8, not debuggable, size);
3. runs the emulator smoke test;
4. builds the source archive;
5. publishes four files: the APK, its `.sha256`, the R8 mapping and the source archive.

## When something goes wrong

- **A run failed before `publish`:** nothing was released. Fix the problem, delete the tag
  (`git push --delete origin vX.Y.Z; git tag -d vX.Y.Z`), and tag again.
- **Publishing was interrupted and left a draft release.** `gh release create` starts as a draft.
  Drafts don't count toward the version check, but the tag still exists. Remove the draft with
  `gh release delete vX.Y.Z` (keep the tag) and re-run the failed workflow, or delete both and
  tag again.
- **A bad release shipped:** don't reuse its version. Fix the problem and release a higher
  version. If you must withdraw it, use `gh release delete vX.Y.Z --cleanup-tag`; its
  `versionCode` stays "used" for anyone who installed it.
- **The key is compromised:** stop releasing and remove the Environment secrets. Releases signed
  with a new key can't update existing installs, so users must uninstall and reinstall.
  (Android's v3.1 key rotation can bridge this, but it isn't set up yet.) Generate a new key,
  update the secrets and `KOGGLES_CERT_SHA256`, and say so in the next release's notes.

## Sideloading notes

- Android doesn't need a certificate authority for app signing, so a self-generated key is
  enough for an ordinary sideload: you allow installs from the browser or file manager, then
  confirm.
- Google Play Protect may scan or warn about an app it hasn't seen before, especially one that
  asks for audio capture. A stable signing key helps over time.
- Google's developer-verification requirement for sideloaded apps (rolling out from late 2026 by
  country) may require registering the package `dev.koggles` and this key's fingerprint in the
  Android Developer Console. Check the current requirements for your region before
  distributing widely.
