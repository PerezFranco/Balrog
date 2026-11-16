# Balrog 🔥

A secure password manager and encrypted note store for Android. Everything
lives on-device, sealed behind your master password — with optional
fingerprint and face unlock, and two-factor codes built into each entry. *It shall not pass.*

## Features

- **Password vault** — titles, usernames, passwords, URLs, and notes
- **Built-in two-factor codes** — any entry can be its own authenticator:
  scan the site's QR code (or type its setup key) and the entry shows the
  live 6-digit code next to the password, ready to copy
- **Secure notes** — free-form encrypted notes
- **Master password** — the only way in; never stored, never recoverable
- **Fingerprint and face unlock** — hardware-backed via the Android
  Keystore, with an opt-in mode for phones whose face unlock Android rates
  below "strong" (see below)
- **Password and passphrase generator** — cryptographically secure random
  strings with configurable character classes, or diceware-style passphrases
  (`plural-ecosystem-jogging-reoccupy-unclip`) from the EFF large wordlist
- **Auto-lock** — the vault key is dropped after 60 s in the background
- **Clipboard hygiene** — copies are flagged sensitive and cleared after 30 s
- **Screenshot blocking** — `FLAG_SECURE` on every screen
- **No backups, no network** — cloud backup and device transfer are excluded;
  the app requests no internet permission. The camera permission is asked for
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

### Two-factor codes

Codes are standard TOTP (RFC 6238; SHA-1/256/512, 6–8 digits, any period),
verified against the RFC's test vectors. The setup secret is stored inside
the entry's encrypted record, like the password. Keeping a password and its
second factor in the same vault is convenient but means the vault is a single
point of compromise for that account — for your most critical accounts you may
prefer a separate device.

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

Run the unit tests (crypto core, two-factor codes, generators) with:

```sh
./gradlew test
```

Every push is also built and tested by GitHub Actions, which attaches the
debug APK to the run.

## Threat model notes

Balrog protects data at rest against device theft and casual extraction. It
does not defend against a compromised OS, a rooted device with a debugger
attached while the vault is unlocked, or someone who knows your master
password. Choose a long master password; there is deliberately no recovery.

## License

See [LICENSE](LICENSE). The bundled wordlist
(`app/src/main/res/raw/eff_large_wordlist.txt`) is the
[EFF large wordlist](https://www.eff.org/dice) by the Electronic Frontier
Foundation, used under CC BY 3.0 US. QR scanning uses
[ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)
(Apache 2.0).
