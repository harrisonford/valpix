# valpix Part 2 — iPhone App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native iPhone app that creates a wallet, joins through a member, shows the live balance, gets paid by QR and pays by QR against the valpix program, using the phone's own internet.

**Architecture:** All chain logic lives in a pure Swift package, `ValpixCore`, with no UI and no third-party dependencies, so it is unit-tested on the Mac with `swift test` and reused by anything else. The SwiftUI app is a thin layer over it: one observable `AppModel`, a Keychain-backed key store, and a handful of views. The app talks straight to a Solana access point over JSON-RPC.

**Tech Stack:** Swift 6.3, SwiftUI, CryptoKit (Ed25519, SHA-256, ChaChaPoly), CommonCrypto (PBKDF2), LocalAuthentication, AVFoundation (QR scan), CoreImage (QR render), XcodeGen for the project file. Xcode 26.5. No package dependencies.

**Spec:** `docs/superpowers/specs/2026-11-10-valpix-design.md` (section 6 for the app, section 5 for the rules it must mirror). Program interface: `program/idl/valpix.json`.

## Global Constraints

- True black background, white foreground, glass controls. Icons only: no labels, hints or explanations. The only text on screen is numbers, names and times.
- Balance format is Chilean: dot for thousands, comma for decimals, four decimal places, no currency label (`12.345,2871`). Whole pesos white, decimals gray.
- The balance is computed locally as `settled + 1000 CLPC × seconds ÷ 86,400` from the member record, using the chain's clock, never the phone's alone.
- Payments are entered in whole pesos. On-chain amounts are micro-CLPC (6 decimals).
- A payment shows as done only after the chain confirms it. Gray while travelling, white when confirmed, failed if it does not confirm before the transaction expires.
- Names are local, at most 32 bytes of UTF-8, carried in QR codes and sent with payments.
- The wallet key never leaves protected storage unencrypted except in the move-to-new-phone QR the user asks for.
- No server of ours. No analytics. No third-party SDKs.
- Any unavoidable text is in Chilean Spanish. Every control has a spoken accessibility label.
- Minimum iOS 17. Glass uses the system glass effect on iOS 26 and a material fallback below it.
- The visual layer must be easy to restyle: colours, type and glass live in one `Theme` file.

## Review Focus

1. **Clock skew.** A phone whose clock is wrong must still show the right balance, because the app offsets by the chain's time. → Task 3 test `liveBalance_uses_chain_offset`.
2. **Hostile QR content.** A QR with a bad key, a negative or huge amount, a 200-byte name, or a different scheme must be rejected, not crash or pay. → Task 3 tests in `PaymentLinkTests`.
3. **Spending more than you have.** Entering an amount above the white balance must not send a transaction. → Task 5, `AppModel.canPay`.
4. **Access point failure.** No route, a timeout or an RPC error must end in the failed state, never a spinner forever or a false tick. → Task 4 test `pay_reports_failure_when_rpc_is_down`.
5. **PDA derivation.** An address derived wrongly sends money nowhere or to no one; derivation is checked against vectors produced by Solana's own library. → Task 1 tests.

---

## File Structure

```
ios/
  project.yml                         XcodeGen spec
  ValpixCore/
    Package.swift
    Sources/ValpixCore/
      Base58.swift                    encode / decode
      Field25519.swift                is-on-curve check for PDA derivation
      PublicKey.swift                 32-byte key, base58, findProgramAddress
      Keypair.swift                   Ed25519 keypair over CryptoKit
      Borsh.swift                     little-endian reader and writer
      Transaction.swift               instruction, message compile, sign, wire format
      ValpixProgram.swift             addresses, instruction builders, record and event decoders
      Money.swift                     accrued, quoteClpcIn, live balance, peso formatting
      PaymentLink.swift               the valpix: links carried by QR codes
      WalletBackup.swift              password-encrypted wallet export
      SolanaRPC.swift                 JSON-RPC over URLSession behind a protocol
      ValpixClient.swift              fetch state, pay, join, history, confirmation
    Tests/ValpixCoreTests/            one test file per source file, plus Vectors.swift
  Valpix/
    ValpixApp.swift                   entry, deep links
    AppModel.swift                    observable state and actions
    KeyStore.swift                    Keychain storage with biometric gate
    Theme.swift                       colours, type, glass
    Views/                            RootView, JoinView, HomeView, BalanceText, HistoryView,
                                      ChargeView, QRImage, ScanView, ConfirmView, ResultView, WalletView
    Info.plist, Assets.xcassets
  README.md
```

## Tasks

### Task 1: Keys and addresses

`Base58`, `Field25519`, `PublicKey`, `Keypair`, `Borsh`.

**Produces:** `Base58.encode(_: Data) -> String`, `Base58.decode(_: String) -> Data?`; `PublicKey(base58:)`, `PublicKey(data:)`, `.base58`, `.data`, `static func findProgramAddress(seeds: [Data], programId: PublicKey) -> (PublicKey, UInt8)?`, `isOnCurve`; `Keypair()`, `Keypair(seed: Data)`, `.publicKey`, `.seed`, `.sign(_: Data) -> Data`; `BorshWriter`, `BorshReader`.

- [ ] Generate vectors with `@solana/web3.js` from `program/scripts` into `Tests/ValpixCoreTests/Vectors.swift`: base58 pairs, on-curve and off-curve keys, the config, pool, vault and member addresses with bumps for the program ID, a seed with its public key and a signature.
- [ ] Tests first (`Base58Tests`, `PublicKeyTests`, `KeypairTests`, `BorshTests`), watch them fail, implement, `swift test`.
- [ ] Commit.

### Task 2: Transactions and the program interface

`Transaction`, `ValpixProgram`.

**Produces:** `Instruction(programId:accounts:data:)`, `AccountMeta`, `Transaction.build(instructions:feePayer:recentBlockhash:) -> Message`, `message.serialize()`, `Transaction.sign(message:signers:) -> Data` (wire bytes); `ValpixProgram(programId:)` with `.config`, `.pool`, `.vault`, `.member(of:)`, `.pay(payer:recipient:amount:name:)`, `.join(inviter:newcomer:)`, `.reviveSelf(payer:)`; decoders `ConfigRecord`, `PoolRecord`, `MemberRecord` from account data; `ValpixEvent.decode(logLine:)` for `Paid` and `Joined`.

- [ ] Vectors: the exact message bytes web3.js produces for a fixed `pay`, and Anchor's instruction and account discriminators from the IDL.
- [ ] Tests first, fail, implement, pass.
- [ ] Commit.

### Task 3: Money, links, backup

`Money`, `PaymentLink`, `WalletBackup`.

**Produces:** `Money.accrued(dailyRate:from:to:)`, `Money.quoteClpcIn(solReserve:clpcReserve:lamportsOut:)`, `Money.liveBalance(member:dailyRate:chainNow:)`, `PesoFormat.parts(micro:) -> (whole: String, decimals: String)`; `ValpixLink.parse(_:) -> ValpixLink?` and `.string` for `.pay(to:amountPesos:name:)`, `.join(key:name:)`, `.wallet(seed:)`, `.backup(blob:)`; `WalletBackup.seal(seed:password:)` / `.open(_:password:)`.

- [ ] Tests reuse the Rust values (`1_000_006`, `4_000_041`, `11_574`) so both sides agree.
- [ ] Tests first, fail, implement, pass.
- [ ] Commit.

### Task 4: Talking to the chain

`SolanaRPC`, `ValpixClient`.

**Produces:** `protocol RPCTransport { func call(method:params:) async throws -> Data }`; `ValpixClient(rpc:program:)` with `state(for wallet:) async throws -> WalletState` (member record or nil, config, pool, vault lamports, rent minimum, chain time offset), `pay(from:to:amountMicro:name:) async throws -> String` (signature), `join(inviter:newcomer:)`, `confirm(signature:blockhashExpiry:) async -> Outcome` (`.confirmed`, `.failed(reason)`), `history(for:limit:) async throws -> [HistoryEntry]`.

- [ ] Unit tests with a fake transport for request shapes, decoding and failures.
- [ ] One integration test against the local validator, skipped when it is not running: fund a fresh member with the admin script, pay, confirm, read history.
- [ ] Commit.

### Task 5: The app

- [ ] `project.yml`, `Theme`, `KeyStore`, `AppModel`, views. Deep links: the `valpix:` scheme opens the same path as a scanned QR, which is also how the simulator is driven.
- [ ] Build for the simulator with `xcodebuild`; unit-test `AppModel` logic that does not need UI (`canPay`, link routing).
- [ ] Run on the simulator against the local validator: launch, join through the admin script, watch the number tick, open a pay link, confirm, see the tick; screenshot each state and check it.
- [ ] Commit.

### Task 6: README and hand-off notes

- [ ] `ios/README.md`: how to build, run against local and devnet, what needs a real phone (camera, Bluetooth, real biometrics).
- [ ] Commit.
