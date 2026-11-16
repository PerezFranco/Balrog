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
- **Auto-lock** — the vault key is dropped after 60 s in the background
- **Clipboard hygiene** — copies are flagged sensitive and cleared after 30 s
- **Screenshot blocking** — `FLAG_SECURE` on every screen
- **No cloud, no network** — Android cloud backup and device transfer are
  excluded, and the app requests no internet permission; backups go only
  where you put them. The camera permission is asked for
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

Run the unit tests (crypto core, backups, Bitwarden import, two-factor codes, generators) with:

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
Foundation, used under CC BY 3.0 US. QR scanning uses
[ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)
(Apache 2.0).
