# Balrog 🔥

A secure password manager and encrypted note store for Android. Everything
lives on-device, sealed behind your master password — with optional
fingerprint and face unlock, and two-factor codes built into each entry. *It shall not pass.*

## Features

- **Password vault** — titles, usernames, passwords, URLs, and notes
- **Folders** — file entries and notes under folders and filter the list by
  folder; folder names are encrypted along with everything else
- **Built-in two-factor codes** — any entry can be its own authenticator:
  scan the site's QR code (or type its setup key) and the entry shows the
  live 6-digit code next to the password, ready to copy
- **Secure notes** — free-form encrypted notes
- **Master password** — the only way in; never stored, never recoverable
- **Fingerprint and face unlock, optionally with a PIN or pattern** —
  hardware-backed via the Android Keystore; add a PIN or a nine-dot pattern
  to require something you are *and* something you know. There is an opt-in
  mode for phones whose face unlock Android rates below "strong" (see below)
- **Password and passphrase generator** — cryptographically secure random
  strings with configurable character classes, or diceware-style passphrases
  (`plural-ecosystem-jogging-reoccupy-unclip`) from the EFF large wordlist
- **Encrypted backups** — save the whole vault as one encrypted file, and
  restore it on a new phone or merge it into an existing vault
- **Import from Bitwarden** — bring a whole Bitwarden vault across from its
  export file, two-factor codes included
- **Automatic backup to Drive, OneDrive, …** — pick a file location once and
  Balrog keeps it up to date every time the vault changes
- **Deterministic generator** — recreate a password from a recipe (Base,
  Case, Salt, Pepper, Type, Trim) instead of storing it; the same algorithm
  as the standalone Generator app, output for output
- **Auto-lock** — the vault key is dropped after 60 s in the background
- **Clipboard hygiene** — copies are flagged sensitive and cleared after 30 s
- **Screenshot blocking** — `FLAG_SECURE` on every screen
- **Sync with your own website, and a web version** — an encrypted copy of
  the vault on your own site keeps your devices in step and lets you use and
  organise the vault from a computer's browser
- **One-tap copy** — copy a username, password or two-factor code straight
  from the list
- **No third-party cloud** — Android cloud backup and device transfer are
  excluded. The app uses the internet only if you turn on sync, and then
  only to reach the address you give it. The camera permission is asked for
  only when you first scan a two-factor QR code.

## Security design

```
master password ──PBKDF2-HMAC-SHA256 (310k iters, random salt)──▶ KEK ─┐
                                                                       │ unwraps
biometric ──▶ Android Keystore key (AES-256-GCM, auth-required) ───────┤
                                                                       ▼
                              DEK (random AES-256) ──▶ AES-256-GCM per record
```

- A random 256-bit **data encryption key (DEK)** encrypts every vault record
  (AES-256-GCM, fresh 96-bit IV per write). Records are stored in Room as
  opaque ciphertext blobs — the database never sees plaintext.
- The DEK is stored only in **wrapped** form: encrypted by a key derived from
  the master password, and (optionally) by a hardware-backed Keystore key that
  requires biometric authentication for every use
  (`setUserAuthenticationRequired`, invalidated if biometrics are re-enrolled).
- **No password hash is stored.** A wrong password simply fails GCM
  authentication when unwrapping the DEK.
- Changing the master password re-wraps the DEK — no data re-encryption needed.
- With sync on, the same wrapped DEK and encrypted records are also stored
  on your website; nothing else is.
- The unlocked DEK exists only in memory and is zeroed/dropped on lock,
  auto-lock, or process death.

### Fingerprint and face

Android sorts biometric sensors into classes, and only lets Class 3
("strong") ones unlock Keystore keys. Fingerprint readers are nearly always
Class 3; face unlock is Class 3 on a few phones (e.g. recent Pixels) and
Class 2 on most others. Balrog therefore has two modes:

| Mode | Accepts | How the vault key is protected |
|------|---------|--------------------------------|
| **Strong** (default) | Class 3 fingerprint or face | Keystore key usable only after a biometric match, checked in secure hardware for every unlock, and invalidated if a new biometric is enrolled |
| **Allow weaker biometrics** (opt-in) | Any enrolled fingerprint or face | Device-bound Keystore key (usable only while the phone is unlocked, on Android 12+); the biometric prompt is enforced by the app |

The second mode is what makes face unlock work on most phones. It is a real
trade-off, and the app says so before enabling it: someone who can add their
own face or fingerprint to the phone could open the vault, and a rooted
device could bypass the prompt. The master password always works in either
mode.

**Biometric + PIN or pattern.** Settings → *Second step* makes unlocking
take a fingerprint or face **and then** a PIN or a nine-dot pattern. It is a
real second factor, not a screen in front of the vault: the vault key is
sealed under a key derived from the PIN or pattern before the Keystore wraps
it, so what a biometric match releases is useless without it. Five wrong
attempts switch biometric unlock off and leave only the master password. It
is one PIN or pattern for every enrolled finger and face — Android does not
tell apps which one matched. (Android also offers no way to demand two
different biometrics at once.)

A pattern is quicker to enter but easier to watch over a shoulder or read
from smudges on the glass, and people favour a small set of shapes; a PIN of
six or more digits is the stronger choice. Either way the attempt limit is
what keeps it safe.

### Two-factor codes

Codes are standard TOTP (RFC 6238; SHA-1/256/512, 6–8 digits, any period),
verified against the RFC's test vectors. The setup secret is stored inside
the entry's encrypted record, like the password. Keeping a password and its
second factor in the same vault is convenient but means the vault is a single
point of compromise for that account — for your most critical accounts you may
prefer a separate device.

### Backups

Settings → **Save a backup** writes a `.balrog` file wherever you choose
(Downloads, a USB stick, your own cloud folder). The file is the vault as it
sits on disk: the password-wrapped vault key with its KDF parameters, plus
every record still encrypted. Nothing is decrypted to make it, so it is
exactly as strong as your master password, and a seal over the full record
set detects missing, reordered or altered entries.

**Automatic backup** (Settings) asks once where to keep the file, then
rewrites it whenever an item is added, edited or deleted, or the master
password changes. Pick a location inside Google Drive, OneDrive or any other
app that appears in Android's file picker and that app syncs the file to the
cloud; Balrog itself still has no internet permission, no account and no
tokens. The vault only changes while you are using it, so writing on change
keeps the backup fresher than a timer would; a write that can't complete is
retried the next time the vault is unlocked. It is one file, overwritten in
place — deletions reach the backup too, so rely on the cloud service's
version history (or an occasional manual backup) if you want older copies.

- A backup opens with the master password **in use when it was made**, even
  if you change the password afterwards. Make a fresh backup after changing
  it, and delete old ones you no longer want openable with the old password.
- On a new install, choose **Restore from a backup instead** on the first
  screen; the vault comes back with that master password.
- **Import from a backup** in Settings merges a backup into the current
  vault, skipping items that are already there.
- Biometric unlock is tied to the device and is not part of a backup; turn it
  on again after restoring.

### Sync and the web version

Balrog can keep the vault in step with an encrypted copy on **your own
website**, which is also what the web version works from. The server is a
dumb, untrusted store: it holds the same encrypted vault file a backup is,
never sees your master password or anything readable, and only makes sure
the caller knows a token and that one device cannot overwrite another's
changes unseen.

Setting it up (any host with PHP 7+, such as cPanel shared hosting):

1. From the [latest release](../../releases/latest), download
   `balrog-sync.php`, `balrog-config.sample.php` and `index.html`.
2. Make your settings file: copy `balrog-config.sample.php` to
   `balrog-config.php` and replace the placeholder with a long random token
   (Balrog's generator can make one: 40 letters and numbers):
   `<?php return array('token' => 'your-token');`
3. Upload `balrog-sync.php`, `balrog-config.php` and `index.html` to a folder
   on your site, e.g. `public_html/balrog/`. The site must be served over
   `https://`.
4. In the phone app: Settings → Sync → enter the script's address
   (`https://your-site/balrog/balrog-sync.php`) and the token.
5. On a computer, open `https://your-site/balrog/` and enter the token and
   your master password.

**Updating later:** replace `balrog-sync.php` and `index.html` with the ones
from the new release and leave `balrog-config.php` alone. The token lives in
that file, not in the script, and releases never contain one, so an update
cannot overwrite it. (If you set sync up with an older release that had the
token inside `balrog-sync.php`, create `balrog-config.php` with the same
token *before* you replace the script.) The page, `index.html`, holds no
secrets at all.

The web page has a **Download the Android app** button that always fetches
the latest APK from the GitHub releases.

How it behaves:

- The phone syncs when the vault is unlocked and after every change. Each
  item has a permanent id; the most recent edit of an item wins, deletions
  spread, and an item edited after being deleted elsewhere comes back.
- Changing the master password on the phone changes it everywhere at the
  next sync.
- The web version decrypts in the browser, using the browser's built-in
  cryptography. It can search, copy usernames, passwords and two-factor
  codes, add, edit and delete entries and notes, and file them into folders
  (drag an entry onto a folder). It also has the **deterministic generator**
  with Peppers (see below), but no random-password generator. It locks after
  five minutes without activity.
- A new phone, or a reinstall, can be set up with **Restore from your sync
  server** on the first screen.

What to be aware of:

- Whoever obtains the encrypted file (your web host, or someone who breaks
  into the site) can try master passwords against it at their leisure. The
  token keeps strangers out, but the master password is what protects the
  vault, so make it a long passphrase.
- The web page is delivered by your site each time you open it. Someone who
  can change files on your site could replace it with one that steals your
  master password. Keep the hosting account secure, and prefer the phone if
  you ever doubt the site.
- Devices are merged by clock time, so a device with a badly wrong clock can
  win or lose an edit it should not.

### Deterministic generator

The generator's third tab derives a password from a recipe rather than from
randomness: the same Base, Case, Salt, Pepper, Type and Trim always give the
same password, so it can be recreated anywhere instead of being stored. It
is a line-for-line port of the standalone Generator app
(`art.zirie.generator`) and must stay output-compatible with it and with its
JavaScript and PHP versions; the unit tests pin the reference values. **Do
not alter the algorithm** — any change silently changes every password ever
derived with it.

- **Recipes** use the standalone Generator app's text format (its "Full Copy"
  button; Balrog calls it **Copy Recipe**) (`Base:` / `Case:`
  / `Salt:` / `Trim:` / `Type:` / `Pepper:` / `Sane:` lines) and can be
  pasted or scanned from a QR code in either direction. `Sane` holds the
  password's first and last character; after regenerating, Balrog says
  whether the result checks out.
- **Peppers** are named secrets mixed into the salt. Balrog keeps them in
  the vault, so unlike in the Generator app they are covered by backups. A
  recipe records only a Pepper's first and last character; Balrog selects
  the matching Pepper when exactly one fits, and refuses to generate with
  one that does not match.
- **Download QR** (phone and web) saves a PNG QR code of the recipe, in
  exactly the text "Scan QR" reads, so it can be scanned back in on the phone.
  Treat it like the password: unless a Pepper is used, anyone holding the
  recipe or its QR code can recreate the password.
- **On the web page** the generator works the same and gives the same
  passwords (checked against the Kotlin implementation, including accents,
  CJK and emoji). It uses the phone's Peppers, can add, rename and delete
  them, and takes recipes by paste (no camera scanning on a computer). One
  detail kept from the Android app: non-ASCII characters in the Salt or
  Pepper are replaced by `?` before hashing, so those passwords match the
  app's.
- In the entry editor on the phone, **Generate → Deterministic…** fills the
  password and saves its recipe with the entry; opening it again starts from
  that recipe.

### Importing from Bitwarden

Settings → **Import from Bitwarden** reads a Bitwarden vault export: the
unencrypted `.json` format (recommended — it carries everything) or `.csv`.
Logins come across with username, password, first website and two-factor
setup, and items keep their Bitwarden folder. Anything Balrog has no dedicated field for — extra websites, custom
fields, and whole cards and identities — is kept as readable
text in the entry's notes or in a secure note, so nothing in the export is
lost. Importing the same file twice adds nothing. Bitwarden's encrypted
export formats are not supported; the unencrypted file holds every password
in the clear, so delete it as soon as the import is done.

### Passphrases

Words are drawn uniformly with `SecureRandom` from the 7,776-word EFF large
wordlist, about 12.9 bits of entropy per word; the generator shows the total.
Six words (~77 bits) make a good master password, and the setup screen can
suggest one.

## Building

Requires Android Studio (or the Android SDK + JDK 17). Then:

```sh
./gradlew assembleDebug   # or open in Android Studio
```

- minSdk 26 (Android 8.0), targetSdk 34
- Kotlin 2.0, Jetpack Compose (Material 3), Room, AndroidX Biometric

Run the unit tests (crypto core, backups, sync against the real server script, Bitwarden
import, two-factor codes, generators) with:

```sh
./gradlew test
```

Every push is also built and tested by GitHub Actions, which attaches the
debug APK to the run.

## Releases

Each version is published once as a GitHub Release with the APK attached: when
a push changes `versionName` in `app/build.gradle.kts` to a version that has
no release yet, GitHub Actions builds that commit, tags it `v<versionName>`
and releases it. Android will only update an installed app with an APK
signed by the same key, so for releases you can install over one another, add
a signing key to the repository's Actions secrets:

```sh
keytool -genkeypair -v -keystore balrog.jks -alias balrog \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 balrog.jks   # paste the output as the first secret
```

| Secret | Value |
|--------|-------|
| `BALROG_KEYSTORE_BASE64` | the base64 text above |
| `BALROG_KEYSTORE_PASSWORD` | the keystore password |
| `BALROG_KEY_ALIAS` | `balrog` |
| `BALROG_KEY_PASSWORD` | the key password |

Keep `balrog.jks` and its passwords somewhere safe and out of the repository.
Without these secrets each release is signed with a one-off key: it installs
fine, but moving to the next release means uninstalling first — save a backup
before you do, and restore it afterwards.

## Threat model notes

Balrog protects data at rest against device theft and casual extraction. It
does not defend against a compromised OS, a rooted device with a debugger
attached while the vault is unlocked, or someone who knows your master
password. Choose a long master password; there is deliberately no recovery — a backup
is only as good as your memory of the password it was made under.

## License

See [LICENSE](LICENSE). The bundled wordlist
(`app/src/main/res/raw/eff_large_wordlist.txt`) is the
[EFF large wordlist](https://www.eff.org/dice) by the Electronic Frontier
Foundation, used under CC BY 3.0 US. QR scanning and drawing in the app use
[ZXing](https://github.com/zxing/zxing) and
[ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)
(Apache 2.0). The web page (`server/index.html`) embeds
[qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) 1.4.4 by
Kazuhiko Arase (MIT) to draw QR codes; its licence notice is kept in the file.
"QR Code" is a registered trademark of DENSO WAVE INCORPORATED.
