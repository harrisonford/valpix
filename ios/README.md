# valpix for iPhone

A native SwiftUI app over `ValpixCore`, a pure Swift package that holds all
the chain logic and has no third-party dependencies.

Design: `../docs/superpowers/specs/2026-11-10-valpix-design.md`, section 6.
The look lives in `Valpix/Theme.swift` and is a placeholder until the
visual proposals are chosen.

## What works

- Creates a wallet on first launch and keeps it in the Keychain.
- With no account: shows a join code. Once a member scans it, the balance
  appears and starts ticking.
- Home: name, signal dot, live balance (white pesos, gray decimals), scan
  and get-paid buttons. Tap the number for history, long-press for the wallet.
- Get paid: number pad, QR, and a tick when the chain confirms a payment.
- Pay: scan or open a `valpix:` link, confirm with Face ID, then sent,
  confirmed or failed.
- Invite: scan a newcomer's join code and confirm.
- Wallet: show the move-to-another-phone QR, or make a password-sealed backup.
  A phone with no account can scan either to adopt that wallet.
- Without a route to the chain: the dot goes hollow, the buttons dim and the
  last known balance keeps ticking.

## Build and run

Needs Xcode 26 and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
cd ios
xcodegen generate
open Valpix.xcodeproj        # then run on a simulator
```

Build signed (the default). A build made with `CODE_SIGNING_ALLOWED=NO`
cannot use the Keychain, and the app then shows a struck-through lock
instead of running on a wallet it could not save.

The app talks to the access point in `ValpixRPCURL` (see `project.yml`),
which is a local validator by default. Start one as described in
`../program/README.md`. To point a simulator somewhere else for one launch:

```sh
SIMCTL_CHILD_VALPIX_RPC=https://api.devnet.solana.com xcrun simctl launch booted cl.valpix.app
```

## Tests

```sh
cd ios/ValpixCore
swift test        # 65 tests
```

One suite, `LocalValidatorTests`, runs the Swift client against the real
program: it pays, joins and reads history. It needs a local validator with
valpix set up and `program/scripts` installed, and is skipped otherwise.

The test vectors in `Tests/ValpixCoreTests/Vectors.swift` come from Solana's
own libraries. Regenerate them after changing the program:

```sh
cd program/scripts
pnpm exec tsx gen-vectors.ts > ../../ios/ValpixCore/Tests/ValpixCoreTests/Vectors.swift
```

## Driving the simulator from a script

The simulator has no camera, and iOS asks "Open in valpix?" before
following a link, which a script cannot tap. Debug builds therefore read a
few environment variables (prefix them with `SIMCTL_CHILD_` for `simctl launch`):

| Variable | Effect |
|---|---|
| `VALPIX_OPEN` | A `valpix:` link treated as a scanned code, or `history`, `charge`, `wallet`. |
| `VALPIX_AUTOCONFIRM=1` | Approves the opened payment without a tap. |
| `VALPIX_CHARGE=<pesos>` | With `VALPIX_OPEN=charge` and auto-confirm, shows the QR for that amount. |
| `VALPIX_SKIP_AUTH=1` | Skips Face ID. |
| `VALPIX_RPC` | Access point for this launch. |

The wallet's address is written to `Documents/debug-pubkey.txt` in the app's
container. None of this exists in release builds.

## Not done, or needs a real phone

- Camera scanning is written but has only been compiled; the simulator has
  no camera.
- Face ID was bypassed in every simulator run.
- Borrowing a nearby phone's internet over Bluetooth is part 4.
- No app icon yet.
- No automated UI tests; the screens were checked by running them and
  reading screenshots (`../docs/screenshots/`).
