# valpix for Android

A native Jetpack Compose app over `core`, a pure Kotlin module that holds all
the chain logic and has no Android code in it.

It is a port of the iPhone app, file for file: `core` mirrors `ValpixCore`,
`AppModel.kt` mirrors `AppModel.swift`, and each screen has its twin. Where
this README is silent, the app does what [`../ios`](../ios/README.md) does.

Design: `../docs/superpowers/specs/2026-11-10-valpix-design.md`, section 6.
The look lives in `app/src/main/kotlin/com/franky/valpix/Theme.kt` and is a
placeholder until the visual proposals are chosen.

## What it does

The same as the iPhone app: asks for fingerprint, face or the screen lock
every time it is opened; creates a wallet on first launch; shows a join code
until a member brings it in; then the live balance, scan to pay, show a QR to
get paid, history behind the number, and the wallet (move to another phone,
password backup, your own access point) behind a long press.

An iPhone and an Android phone read each other's codes. The exact link text
and a sealed backup are pinned as fixtures in both cores
(`CrossPlatformTest.kt` here, `CrossPlatformTests.swift` there).

Differences from the iPhone app, all deliberate:

- **Where the key lives.** The wallet seed is sealed with AES-GCM by a key
  that never leaves the Android Keystore, and the sealed file sits in the
  app's private storage. The app is excluded from cloud backup and from
  phone-to-phone transfer, so the wallet moves only through the app's own
  "move to another phone" code or a password backup.
- **A link that arrives while the app is locked** is kept and offered after
  unlocking. (The iPhone app drops it.)
- **Access points.** The app ships with devnet only. A real phone cannot
  reach a validator on the Mac, and an address that never answers would make
  every request wait for a timeout first.
- **No Google Play services.** Codes are read with CameraX and ZXing.

## Build and install

Needs Android Studio (for its SDK and its bundled Java). Nothing else.

```sh
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug        # onto the phone connected over adb
```

Only debug builds exist so far. There is no release signing yet.

**Uninstalling the app deletes the wallet**, because Android removes the
app's Keystore key with it. Reinstalling over the top (`installDebug`) keeps
it. Move or back up the wallet first if it matters.

To bring the phone's wallet in as a member on devnet, open the app once, then:

```sh
cd program/scripts
./invite-android.sh
```

## Tests

```sh
cd android
./gradlew :core:test
```

They run on the Mac, with no phone. The vectors in `Vectors.kt` are the Swift
core's, converted by `scripts/gen-vectors.py`, so both cores are held to the
same bytes from Solana's own libraries.

`IntegrationTest` runs the Kotlin client against the real program: it pays,
joins and reads history. It needs a local validator with valpix set up and
`program/scripts` installed (see `../program/README.md`), and is skipped
otherwise.

Backups use 600,000 rounds of PBKDF2, which makes the backup tests take a
few seconds each.

## Driving the phone from a script

Debug builds read a few launch extras, so a script on the Mac can stand in
for the camera and the fingerprint:

```sh
adb shell am start -n com.franky.valpix/.MainActivity \
  --es VALPIX_SKIP_AUTH 1 --es VALPIX_AUTOCONFIRM 1 \
  --es VALPIX_OPEN charge --es VALPIX_CHARGE 100
```

| Extra | Effect |
|---|---|
| `VALPIX_OPEN` | A `valpix:` link treated as a scanned code, or `history`, `charge`, `wallet`. |
| `VALPIX_AUTOCONFIRM=1` | Approves the opened payment or invitation without a tap. |
| `VALPIX_CHARGE=<pesos>` | With `VALPIX_OPEN=charge` and auto-confirm, shows the QR for that amount. |
| `VALPIX_SKIP_AUTH=1` | Skips the fingerprint. |
| `VALPIX_RPC` | The only access point for this launch. |
| `VALPIX_SELFTEST=1` | Checks on the phone that its cryptography matches the iPhone's; result in `adb logcat -s valpix`. |

Extras stick until the app's process ends (`adb shell am force-stop
com.franky.valpix`). The wallet's address is written to
`files/debug-pubkey.txt` (read it with `adb shell run-as com.franky.valpix
cat files/debug-pubkey.txt`). None of this exists in release builds.
