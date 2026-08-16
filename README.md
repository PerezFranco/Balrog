# Balrog 🔥

A secure password manager and encrypted note store for Android. Everything
lives on-device, sealed behind your master password — with optional
biometric unlock. *It shall not pass.*

## Features

- **Password vault** — titles, usernames, passwords, URLs, and notes
- **Secure notes** — free-form encrypted notes
- **Master password** — the only way in; never stored, never recoverable
- **Biometric unlock** — fingerprint/face via hardware-backed Android Keystore
- **Password generator** — cryptographically secure, configurable character classes
- **Auto-lock** — the vault key is dropped after 60 s in the background
- **Clipboard hygiene** — copies are flagged sensitive and cleared after 30 s
- **Screenshot blocking** — `FLAG_SECURE` on every screen
- **No backups, no network** — cloud backup and device transfer are excluded;
  the app requests no internet permission

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

## Building

Requires Android Studio (or the Android SDK + JDK 17). Then:

```sh
./gradlew assembleDebug   # or open in Android Studio
```

- minSdk 26 (Android 8.0), targetSdk 34
- Kotlin 2.0, Jetpack Compose (Material 3), Room, AndroidX Biometric

Run the unit tests for the crypto core with:

```sh
./gradlew test
```

## Threat model notes

Balrog protects data at rest against device theft and casual extraction. It
does not defend against a compromised OS, a rooted device with a debugger
attached while the vault is unlocked, or someone who knows your master
password. Choose a long master password; there is deliberately no recovery.

## License

See [LICENSE](LICENSE).
