# Balrog — handover to a local session

Written from the cloud session on 2026-11-22 so a Claude Code session on Roberto's
own computer can carry on. Trust the code and `git log` over this file if they disagree.
**Status: everything Roberto asked for in code is done and pushed as version 1.5.3. What is
left is getting the released files onto his Hostinger site (section "Deploying") and
testing on his phone.**

## Where things are

- Repo: https://github.com/PerezFranco/Balrog (public). **All work is on branch
  `ccr-ca9fd394-1clrrx`**; `main` still holds only the initial commit. No PR has been
  opened (only open one if Roberto asks).
- Latest published release: **v1.5.2** (versionCode 8) at the time of the first writing;
  **1.5.3 (versionCode 9) was pushed afterwards and CI publishes it by itself** — check
  https://github.com/PerezFranco/Balrog/releases for `v1.5.3` before deploying. 1.5.2 was
  the first signed with the permanent release key. Key fingerprint (public) SHA-256
  `54:7F:69:6A:17:8B:B1:C6:C5:34:4C:B7:2A:1E:DD:E2:77:3D:04:86:53:29:13:BA:70:20:8A:BD:7D:33:DA:3A`.
  Roberto keeps the keystore and its passwords himself; the cloud session deleted its copy.
  Never commit key material.
- Modules: `:app` (Android, Compose), `:core` (plain JVM: `CryptoEngine`, `VaultBackup`
  vault-file format v2, `VaultMerge`, `SyncClient`, `VaultSync`). Server side lives in
  `server/`. `README.md` documents features, sync setup and the security design.
- CI: `.github/workflows/build.yml` runs tests + builds a debug APK on every push.
  `.github/workflows/release.yml` publishes a GitHub Release **when `versionName` in
  `app/build.gradle.kts` has no release yet** (bump `versionCode` and `versionName`
  together, push, wait ~5 min). It reads the four secrets `BALROG_KEYSTORE_BASE64`,
  `BALROG_KEYSTORE_PASSWORD`, `BALROG_KEY_ALIAS` (=`balrog`), `BALROG_KEY_PASSWORD`.
  The cloud session could not push tags; a local session can, but the version-driven flow
  works and is what exists.
- Roberto installed 1.5.2 and was about to set up sync. **Nothing has been reported back
  from real-device use yet.** The cloud session had no Android SDK: the app is only
  compile- and unit-test-verified (by CI). A local machine with the SDK and `adb` can test
  on his phone, which is the biggest thing the cloud session could not do.

## What 1.5.3 contains (all verified in the cloud as noted)

1. **Sync token in `balrog-config.php`** beside the script (a PHP file that only returns
   `array('token' => '…')`; at least 20 characters). `server/balrog-config.sample.php` is the
   template; **releases only ever ship the sample, never a real config**. No/short/malformed
   config → HTTP 503 naming the file. Opening the config in a browser prints nothing. The
   script can now be replaced without touching the token. `SyncTest` runs the real script
   with PHP's built-in server (skipped if `php` is missing).
2. **`server/balrog.html` is now `server/index.html`**, with an elegant **Download the
   Android app** button (login page, and a small link in the header). It points at
   `https://github.com/PerezFranco/Balrog/releases/latest/download/Balrog.apk`; the release
   workflow attaches a copy of the APK under that fixed name. Until 1.5.3 exists the button
   404s. No third-party requests: the page's CSP is `default-src 'none'`.
3. **Balrog icon** as inline SVG logo (login and header) and as SVG + 64 px PNG favicon
   (both `data:` URIs). Source of truth: `app/src/main/res/drawable/ic_launcher_foreground.xml`
   on `#121212`.
4. **Deterministic generator on the web** (Generator tab; no random generator, as Roberto
   wants). Port of `DeterministicGenerator.kt` — **never change the algorithm**. Verified in
   headless Chromium: the page's `detPassword` equals the Kotlin output on 13 vectors
   including accents, CJK and emoji (the Android app hashes the salt as US-ASCII, every
   non-ASCII code point becoming `?`; the page reproduces that), and the three reference values
   (`Roberto`/`Joaquin`/`Perez-Franco`/10 → `mpyz\XK-%G`, `orxycYT73Q`, `prxyeaWGDV`).
   Peppers are vault records of type `PEPPER` `{title, value}`; the page can add, rename and
   delete them, and a vault written by the page was opened and seal-verified by the phone's
   `VaultBackup`. Recipes by paste only (no camera on a PC).
5. **"Full copy" → "Copy Recipe"** in the app (`DeterministicForm.kt`) and the web page, plus
   **Download QR** in both. App: ZXing `QRCodeWriter` (`security/RecipeQr.kt`, EC M, quiet zone
   4, UTF-8 declared only for non-ASCII), saved with `CreateDocument("image/png")` as
   `balrog-recipe-qr.png`; `RecipeQrTest` scans the drawn code back with ZXing and parses it
   with `Recipe.parse`. Web: `qrcode-generator` 1.4.4 (MIT) inlined, UTF-8 bytes, canvas →
   PNG. The PNGs the page downloaded were decoded with ZXing (same library as the app's
   scanner) to exactly the "Copy Recipe" text and parsed by `Recipe.parse`. Both clear the
   previous result as soon as a new generation starts, so a stale password/QR can't be copied.
   The QR is as sensitive as the password when no Pepper is used; a note says so.
6. App version 1.5.3 (versionCode 9); `zxing-core` 3.4.1 is declared explicitly.

Constraints Roberto has stated: no autofill needed; the web page needs no random generator;
the PC page is for organising and copying; he keeps using Bitwarden for now.

## Deploying to his Hostinger cPanel site

Roberto wants the files **uploaded for him**. Files to deploy, from the **1.5.3 release
assets** (not from a stale checkout): `balrog-sync.php`, `balrog-config.sample.php`,
`index.html` (the old name was `balrog.html`).

Roberto wants the files **uploaded for him**. The cloud session cannot: his hosting logins
live on his PC. His other local sessions deploy over **SFTP using `sftp.exe` and generated
`.sftp` batch files** driven by scripts such as `C:\Users\perez\Claude Websites\Nuevo\release.py`
and `C:\Users\perez\Claude RobertoAu\retejo\…` (sites seen: `roberto.au`,
`roberto.perez-franco.com`; Hostinger). The cloud session did not read those scripts; read
them first and reuse their connection method, and never print credentials.

Ask Roberto for the address he typed in the app (Settings → Sync), which tells you the
folder. Then, in this order:

1. Download the current deployed `balrog-sync.php`, extract its `const TOKEN = '…'`, and
   write `balrog-config.php` **only if it does not already exist** (`<?php return
   array('token' => '…');`). Do not echo the token anywhere.
2. Upload the new `balrog-sync.php`, `balrog-config.sample.php` and `index.html` from the
   GitHub release. Never upload or overwrite `balrog-config.php` after step 1.
3. Verify with `curl`: the script without a token → 401 `Unauthorized.`; with the token →
   200/204; `…/balrog-config.php` → 200 with an empty body; `…/balrog-data/vault.php` → 404.
   Check the page loads and the vault unlocks (Roberto must do the final master-password
   step himself).
4. Do **not** delete `balrog-data/` (it holds the vault and `vault.previous.php`). Ask before
   removing a stale `balrog.html`.

## Keep in mind

- Roberto likes to be told plainly what has *not* been tested. Be explicit about that.
- Not verified anywhere yet: the Android UI on a real phone (CI only compiles and unit-tests
  it): sync screens, Download QR through the system file picker, biometrics, folders, etc.
  Also not verified: the real Hostinger deployment.
- Verification already done in the cloud for the earlier releases: Kotlin core tests incl.
  full sync rounds against the real PHP script; the web page driven in headless Chromium
  against the real script and a phone-format vault, and the phone's `VaultBackup` verified
  what the page wrote; the deterministic algorithm reproduced the reference values in an
  independent Python implementation.
- Commit trailers requested by the harness: end commit messages with the
  `Co-Authored-By` and `Claude-Session` lines used in the history.
- Don't change the vault file format casually: the phone, the web page and the server all
  depend on `VaultBackup` v2 (digest framing in `VaultBackup.digest`, mirrored in the page).
