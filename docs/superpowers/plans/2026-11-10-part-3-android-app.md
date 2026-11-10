# valpix Part 3 — Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android app that does exactly what the iPhone app does: creates a wallet, joins through a member, shows the live balance, gets paid by QR and pays by QR against the valpix program, using the phone's own internet. An iPhone and an Android phone must be able to pay each other.

**Architecture:** A port of the iPhone app, file for file. All chain logic lives in `android/core`, a pure Kotlin/JVM module with no Android code, tested on the Mac with `./gradlew :core:test` against the same vectors as the Swift core. `android/app` is a thin Jetpack Compose layer over it: one `AppModel`, a key store backed by the Android Keystore, and a handful of screens. Core functions block; the app calls them from `Dispatchers.IO`.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1, Gradle 9.7.1 (the versions already on this Mac), JDK = Android Studio's bundled runtime. Core: BouncyCastle (Ed25519, ChaCha20-Poly1305, PBKDF2), kotlinx-serialization-json (JSON trees only, no compiler plugin), `java.net.HttpURLConnection`. App: Compose (foundation + ui + material icons, no Material components), androidx.biometric, CameraX + ZXing core (scan and draw QR; no Google Play services). minSdk 26, targetSdk 36, compileSdk 37.

**Spec:** `docs/superpowers/specs/2026-11-10-valpix-design.md` (section 6). The reference implementation is `ios/` — where this plan is silent, the Android app does what the iPhone app does.

## Global Constraints

- Everything in Part 2's Global Constraints applies unchanged: true black, white, glass circles, icons only; Chilean number format with four gray decimals; balance from chain time; whole-peso payments; done only when the chain confirms; names local and at most 32 bytes; no server, analytics or third-party SDKs; unavoidable text in Chilean Spanish; every control has a spoken label; look lives in one `Theme` file.
- Same wire formats as the iPhone app, byte for byte: `valpix:` links, the 77-byte backup blob (PBKDF2-HMAC-SHA256 600,000 rounds, ChaCha20-Poly1305), instruction data, legacy transaction messages.
- Application ID `com.franky.valpix`, matching the iPhone bundle ID. Debug builds only for now; no release signing in this part.
- The wallet seed is stored encrypted by a key that lives in the Android Keystore and is excluded from backups. Opening the app and approving a payment ask for fingerprint, face or the screen lock.
- An empty name shows the hint "tu nombre aquí" (Kris, 2026-11-10).
- Ships with devnet as its only built-in access point; a debug launch can point elsewhere.
- QA is on Kris's Pixel 9a over adb, not an emulator (Kris, 2026-11-10).

## Review Focus

1. **An iPhone QR read by Android, and the reverse.** Links must mean the same on both: accents, `&`, `+`, spaces, the `valpix://` form. → Task 3, `CrossPlatformTest` with strings fixed from the Swift code, mirrored by a Swift test.
2. **A backup made on one platform opened on the other.** → Task 3, a blob sealed by Swift opens in Kotlin; Task 6, one sealed by Kotlin opens in Swift.
3. **64-bit unsigned numbers.** Kotlin's `Long` is signed and JSON numbers pass through text: amounts, lamports and block heights above 2^63 must not go negative or lose digits. → Task 3 `MoneyTest` overflow cases, Task 4 `RpcTest.readsNumbersAboveTheSignedRange`.
4. **A key store that cannot be read is not an empty one.** The Android Keystore throws when the phone's lock changes or the key is lost; the app must not then create a new wallet over a funded one. → Task 4 `WalletKeeperTest`; Task 5 `KeyStore.read()` maps every failure to `Unavailable`.
5. **Saying "failed" after the payment left the phone.** → Task 4 `ClientTest` cases for lost route after send, landed-after-timeout, and `Unconfirmed`.

---

## File Structure

```
android/
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml  gradlew
  core/src/main/kotlin/com/franky/valpix/core/
    Base58.kt  Ed25519.kt (on-curve check)  PublicKey.kt  Keypair.kt  Borsh.kt
    Transaction.kt  ValpixProgram.kt  Money.kt  PaymentLink.kt  WalletBackup.kt
    SolanaRpc.kt  ValpixClient.kt  Hardening.kt
  core/src/test/kotlin/com/franky/valpix/core/
    Vectors.kt (converted from the Swift vectors)  KeysTest  ProgramTest  MoneyTest
    LinkTest  CrossPlatformTest  RpcTest  ClientTest  HardeningTest  IntegrationTest  FakeTransport
  app/src/main/
    AndroidManifest.xml  res/ (icon, theme, backup rules)
    kotlin/com/franky/valpix/
      MainActivity.kt  AppConfig.kt  KeyStore.kt  Authenticator.kt  AppModel.kt  Theme.kt
      ui/ Root.kt  Home.kt  Join.kt  Charge.kt  Pay.kt  Scan.kt  Wallet.kt  Qr.kt
  app/src/debug/AndroidManifest.xml (cleartext for a local validator)
  README.md
program/scripts/invite-android.sh
```

Kotlin names follow the Swift ones (`ValpixLink`, `LinkPolicy`, `WalletKeeper`, `FallbackTransport`, `HistoryEntry.settles`…) so the two cores can be read side by side. Amounts are `ULong`; timestamps `Long`; byte strings `ByteArray` wrapped where equality matters.

## Tasks

Each core task is test-first: port the Swift tests for that area (same names, same vectors), watch them fail, port the code, watch them pass. Test command for every core task: `cd android && ./gradlew :core:test`.

### Task 1: Project, keys and addresses

**Files:** Gradle scaffolding; `Base58.kt`, `Ed25519.kt`, `PublicKey.kt`, `Keypair.kt`, `Borsh.kt`; `Vectors.kt`, `KeysTest.kt`.

- [ ] Scaffold `android/` with `:core` (Kotlin JVM, JUnit 5) and an empty `:app`; add `android/.gitignore`.
- [ ] Convert `ios/ValpixCore/Tests/ValpixCoreTests/Vectors.swift` to `Vectors.kt` (record the command in its header).
- [ ] Port `KeysTests.swift` → `KeysTest.kt` (Base58 pairs, on/off curve, the four PDAs with bumps, public key from seed, signature bytes, Borsh). Run: FAIL (unresolved).
- [ ] Implement. `Ed25519.isOnCurve(bytes)` with `BigInteger`: y from the low 255 bits, `x² = (y²−1)/(d·y²+1) mod p`, on the curve iff `x²` is a square. `Keypair` signs with BouncyCastle. Run: PASS.
- [ ] Commit.

### Task 2: Transactions and the program interface

**Files:** `Transaction.kt`, `ValpixProgram.kt`; `ProgramTest.kt`.

- [ ] Port `ProgramTests.swift` (compact-u16, the three message vectors, account order, signing, record decoding, the Paid log line). Run: FAIL.
- [ ] Implement as in Swift. Run: PASS. Commit.

### Task 3: Money, links, backup

**Files:** `Money.kt`, `PaymentLink.kt`, `WalletBackup.kt`; `MoneyTest.kt`, `LinkTest.kt`, `CrossPlatformTest.kt`; `ios/.../CrossPlatformTests.swift`.

- [ ] Port `MoneyTests.swift` and `LinkTests.swift`; add overflow cases at `ULong.MAX_VALUE`. Run: FAIL.
- [ ] Add `CrossPlatformTests.swift` to the Swift core pinning the exact link text for: a fixed charge with name `pan & café +1`, an open charge, a join, and a backup blob sealed with a known password. Copy the same strings into `CrossPlatformTest.kt`, which must parse them to the same values and open the blob.
- [ ] Implement. The link parser is written by hand (scheme, optional `//`, action, `&`-separated pairs, strict percent-decoding as UTF-8, `+` kept literal); it writes values with everything but unreserved characters percent-encoded. Names reject Unicode control and format characters. Run: PASS, and `swift test` still green. Commit.

### Task 4: Talking to the chain

**Files:** `SolanaRpc.kt`, `ValpixClient.kt`, `Hardening.kt`; `FakeTransport.kt`, `RpcTest.kt`, `ClientTest.kt`, `HardeningTest.kt`, `IntegrationTest.kt`.

- [ ] Port `ClientTests.swift` and `HardeningTests.swift` with a `FakeTransport`; add `readsNumbersAboveTheSignedRange`. Run: FAIL.
- [ ] Implement: `RpcTransport { fun post(body: String): String }`, `HttpTransport` (10 s timeouts), `SolanaRpc`, `ValpixClient` (`state`, `pay`, `join`, `reviveSelf`, `history`, `submit` following the locally derived signature), `WalletState` with a Borsh snapshot, events attributed by invoke stack, `LinkPolicy`, `WalletKeeper`, `FallbackTransport`. Run: PASS.
- [ ] `IntegrationTest`: skipped unless `VALPIX_TEST_RPC` is set; then two fresh members (created with `VALPIX_TEST_ADMIN` key file) pay each other and read history. Run against devnet once. Commit.

### Task 5: The app

**Files:** everything under `android/app`, `program/scripts/invite-android.sh`.

- [ ] Build the Compose app mirroring the iPhone screens and `AppModel` one for one. `KeyStore`: AES-GCM key in the Android Keystore, sealed seed in `filesDir`, written atomically, never deleted. `Authenticator`: `BiometricPrompt` with weak biometrics or device credential; a phone with no lock approves. Lock when the app leaves the screen. Balance redrawn every frame. QR drawn from a ZXing matrix; scanned with CameraX + ZXing; a paste button beside it. `valpix:` links from other apps arrive as `LinkSource.External`.
- [ ] Debug-only launch extras for driving it over adb: `VALPIX_OPEN`, `VALPIX_CHARGE`, `VALPIX_AUTOCONFIRM`, `VALPIX_SKIP_AUTH`, `VALPIX_RPC`; the debug build writes its address to `files/debug-pubkey.txt`.
- [ ] `./gradlew :app:assembleDebug` succeeds; install on the Pixel; `invite-android.sh` makes it a member on devnet.
- [ ] On the Pixel against devnet: join code → balance ticking; pay the iPhone simulator's charge code; get paid by the iPhone simulator; history; wallet screen; no-route state in airplane mode. Screenshots into `docs/screenshots/`. Commit.

### Task 6: README, the reverse backup check, hand-off

- [ ] A Kotlin-sealed backup blob pinned in `CrossPlatformTests.swift`; `swift test` and `./gradlew :core:test` green.
- [ ] `android/README.md` (build, install, debug extras, what is and is not verified); root `README.md` updated. Commit.
