# Balrog — handover to a local session

Written from the cloud session on 2026-11-22 so a Claude Code session on Roberto's
own computer can carry on. Trust the code and `git log` over this file if they disagree.

## Where things are

- Repo: https://github.com/PerezFranco/Balrog (public). **All work is on branch
  `ccr-ca9fd394-1clrrx`**; `main` still holds only the initial commit. No PR has been
  opened (only open one if Roberto asks).
- Latest published release: **v1.5.2** (versionCode 8), the first signed with the permanent
  release key. Key fingerprint (public) SHA-256
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

## Done but not yet released (in the commit that adds this file)

The sync token moved out of the PHP script into `balrog-config.php` beside it, so a new
`balrog-sync.php` can be uploaded without re-entering the token:

- `server/balrog-sync.php` reads `balrog-config.php` (`<?php return array('token' => '…');`,
  at least 20 characters). No config or short token → HTTP 503 naming the file. Opening the
  config in a browser prints nothing.
- `server/balrog-config.sample.php` is the template. **Releases must only ever ship the
  sample, never a `balrog-config.php`.**
- `SyncClient` / the web page 503 text updated; `SyncTest` covers it against the real PHP
  script (needs `php` on PATH, else those tests skip). Core tests passed in the cloud.
- The deployed script on Roberto's site is still the old one with the token inside it.

## Requested by Roberto, not started in code (all for release 1.5.3)

1. **Rename `server/balrog.html` → `server/index.html`** and give it an elegant
   **"Download the Android app" button**. Plan: the release workflow also attaches a copy
   of the APK under the fixed name `Balrog.apk`, and the button links to
   `https://github.com/PerezFranco/Balrog/releases/latest/download/Balrog.apk`. Update
   `release.yml` (assets: `Balrog-$TAG.apk`, `Balrog.apk`, `server/balrog-sync.php`,
   `server/balrog-config.sample.php`, `server/index.html`), the README and release notes.
   The page's CSP is `default-src 'none'`; a plain `<a href>` is fine, and the page must not
   fetch from other sites.
2. **Balrog icon as favicon and shown in the page**, at login and when logged in. Source of
   truth is `app/src/main/res/drawable/ic_launcher_foreground.xml` on `#121212`. A ready SVG
   (rounded-square crop, renders well at 16–96 px):
   `<svg xmlns="http://www.w3.org/2000/svg" viewBox="20 21 68 68"><rect x="20" y="21" width="68" height="68" rx="15" fill="#121212"/><path d="M54,26 L78,34 V56 C78,70 68,79 54,84 C40,79 30,70 30,56 V34 Z" fill="#1C1B1F" stroke="#FF7A00" stroke-width="3"/><path d="M54,38 C58,44 62,46 62,53 C62,59 58,63 54,63 C50,63 46,59 46,53 C46,50 47,48 49,46 C49,50 51,51 52,51 C51,47 51,42 54,38 Z" fill="#FF7A00"/><path d="M50,66 H58 V70 H50 Z" fill="#FF7A00"/></svg>`
   Embed inline plus as `data:` favicon (CSP already allows `img-src data:`); add a 64 px PNG
   data URI for Safari.
3. **Deterministic generator on the web page** (Roberto reversed his earlier "mobile only";
   he still wants **no random generator** on the web). Port `DeterministicGenerator.kt`
   exactly and **never change the algorithm**: PBKDF2-HMAC-SHA256, 100 000 iterations, 200
   bytes, password = UTF-8 of `"My base is …. My case is …."`, salt = the
   `"My salt 1 is {salt}{pepper}. My trim is {n}."` text hashed as **US-ASCII, every
   non-ASCII code point becoming `?`** (that is what the Android app does), base64 of the
   bytes, numbers from overlapping 5-char windows (step 2) of char codes, **stable** ranking,
   mapping per type. Reference values: Base `Roberto`, Case `Joaquin`, Salt `Perez-Franco`,
   no Pepper, Trim 10 → `mpyz\XK-%G` (letters+numbers+special), `orxycYT73Q`
   (letters+numbers), `prxyeaWGDV` (letters only). Use WebCrypto `deriveBits`. Verify by
   generating many vectors (including non-ASCII and emoji) with Kotlin and comparing to the
   page. Peppers are vault records of type `PEPPER` with payload `{title, value}` (the page
   already decrypts them but hides them); the generator view needs select/add/rename/delete,
   shown as `a...L - Name` (first/last UTF-16 unit of the value) like the app. Recipes are
   the text block `Base:/Case:/Salt:/Trim:/Type:(ANC|AN|A)/[Pepper: x...y]/[Sane: x...y]`
   (see `DeterministicGenerator.Recipe`). No camera scanning on the PC.
4. **"Full copy" → "Copy Recipe"** in the deterministic generator in both the app
   (`DeterministicForm.kt`) and the web page, plus a **"Download QR" button** in both that
   saves a PNG QR code of the recipe text, in exactly the format the app's "Scan QR" reads
   (the same text `Recipe.toText()` produces, including `Pepper` and `Sane` lines).
   - App: ZXing core is already on the classpath (`zxing-android-embedded` 4.3.0 exposes
     core 3.4.1 as `api`). `QRCodeWriter`, EC level M, `MARGIN` 4, add the `CHARACTER_SET`
     UTF-8 hint only when the text is non-ASCII, scale to ≥ ~1000 px, save with
     `ActivityResultContracts.CreateDocument("image/png")`. Keep the pure encoding in its
     own file so a JVM test can encode, decode with `QRCodeReader` and `Recipe.parse` it.
   - Web: no external code at runtime. `npm pack qrcode-generator@1.4.4` (MIT, Kazuhiko Arase,
     reviewed: no network/eval); inline it with its copyright header, override
     `qrcode.stringToBytes` to UTF-8, draw to a canvas with a 4-module quiet zone, download
     via `toBlob`. Verify by decoding the produced PNG with ZXing (`RGBLuminanceSource`,
     `QRCodeReader`) and parsing it with `Recipe.parse`.
   - The QR is as sensitive as the password when no Pepper is used; say so in a short note
     next to the button. Use a generic file name (`balrog-recipe-qr.png`).

Constraints Roberto has stated: no autofill needed; the web page needs no random generator;
the PC page is for organising and copying; he keeps using Bitwarden for now.

## Deploying to his Hostinger cPanel site

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
   GitHub release (not from a stale checkout).
3. Verify with `curl`: the script without a token → 401 `Unauthorized.`; with the token →
   200/204; `…/balrog-config.php` → 200 with an empty body; `…/balrog-data/vault.php` → 404.
   Check the page loads and the vault unlocks (Roberto must do the final master-password
   step himself).
4. Do **not** delete `balrog-data/` (it holds the vault and `vault.previous.php`). Ask before
   removing a stale `balrog.html`.

## Keep in mind

- Roberto likes to be told plainly what has *not* been tested. Be explicit about that.
- Verification already done in the cloud for the earlier releases: Kotlin core tests incl.
  full sync rounds against the real PHP script; the web page driven in headless Chromium
  against the real script and a phone-format vault, and the phone's `VaultBackup` verified
  what the page wrote; the deterministic algorithm reproduced the reference values in an
  independent Python implementation.
- Commit trailers requested by the harness: end commit messages with the
  `Co-Authored-By` and `Claude-Session` lines used in the history.
- Don't change the vault file format casually: the phone, the web page and the server all
  depend on `VaultBackup` v2 (digest framing in `VaultBackup.digest`, mirrored in the page).
