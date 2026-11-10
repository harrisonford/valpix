# valpix — design

Date: 2026-11-10. Status: draft for Kris's approval.

Lines marked **(Kris)** are decisions Kris made. Lines marked **(call)** are choices made while writing this up; they are open to change. Everything else under "Left to the builder" is the builder's to decide.

## 1. Intent

valpix is an open payment system that starts in Valparaíso, Chile. Any person, feria stall or small shop can hold an account, pay by scanning a QR and get paid by showing one. Every account receives 1000 units of the coin a day, credited continuously, so the balance on screen is always rising.

Why it exists and what would make it a success, in Kris's words and choices:

- **Nobody can rewrite balances.** This is the reason the ledger is a public blockchain.
- **No central database or server of our own.** Achieving this is considered the major achievement of the project.
- **Almost zero fee**, and the freest possible implementation.
- **Payments work for a phone that has no internet of its own**, by borrowing a nearby phone's connection.
- **A hyper-minimal wallet**: pitch black, white, glass, one number.

## 2. Goals, non-goals, anti-goals

**Goals for the first version**

- A Solana program on devnet that holds all balances and rules.
- 1000 CLPC a day per account, computed from the chain's clock.
- Paying and getting paid by QR, counted only when confirmed on-chain.
- Joining through an existing member.
- A pool that covers Solana's fees so people never handle SOL.
- A self-custody wallet with backup and move-to-new-phone.
- A native iPhone app, then a native Android app, in this repo.
- Borrowing a nearby phone's internet over Bluetooth.

**Non-goals for now (Kris)**

- Deposits or cash-out in real pesos.
- One-account-per-person checks (phone number or RUT). The design must leave room for them.
- A treasury, donations, or a developer share of fees.
- Running on real SOL (mainnet).
- Final visual design. Proposals are being produced separately with Claude Design; see `docs/design/claude-design-prompt.md`.

**Anti-goals: these would make it a failure even if it works**

- A server or database of ours that the system depends on.
- A payment shown as done before the chain confirmed it.
- People having to buy, hold or understand SOL.
- CLPC being freely exchangeable for SOL that lands in a wallet (this would let the free daily income be sold and the pool drained).
- An app that needs reading: labels, hints, onboarding text, crypto vocabulary.

## 3. Constraints

- No budget for servers. Devnet first, where SOL is free. Devnet can be wiped, so pilot balances are not permanent.
- Two fully native apps: SwiftUI and Jetpack Compose. No shared cross-platform UI layer. **(Kris)**
- iPhone first, because this Mac can run the iOS simulator; Android second. **(Kris)**
- Development machine today: Xcode 26.5, Swift 6.3, Rust 1.95, Node 25. The Solana CLI and Anchor are not installed yet.
- The iOS simulator has no camera and no Bluetooth. Camera scanning and the Bluetooth part need real phones to test.

## 4. The system in one picture

```
 iPhone app ─┐                       ┌─ public Solana access points (RPC)
 Android app ┼─ own internet ────────┤
             └─ Bluetooth → a nearby ┘        Solana program "valpix"
                phone with internet           ├─ Config   (rules)
                                              ├─ Member   (one per account)
                                              └─ Pool     (SOL + CLPC, fees)
```

There is nothing else: no backend, no indexer, no push service.

## 5. The money rules (Solana program)

### 5.1 Coin

- Working name **CLPC** **(Kris)**. It is a closed coin: not backed by Chilean pesos, with no way in or out for real money yet. Whether the name stays is open, because it suggests peso backing.
- CLPC balances live **inside the valpix program**, not as a standard Solana token. **(call)** A standard token could be listed on outside exchanges, and then no rule of ours could keep it from being traded. The cost is that CLPC is invisible to other Solana wallets.
- 6 decimal places internally. **(call)** Payments are entered in whole pesos.

### 5.2 Accounts

- **Config** (one): the daily rate, pool settings, and the key allowed to change them.
- **Member** (one per person, derived from their wallet key): settled balance, the time income was last settled, creation time, and who invited them. No name is stored on-chain.
- **Pool** (one): a SOL reserve and a CLPC reserve.

### 5.3 Daily income

- Every Member earns 1000 CLPC per day from the moment it is created, with no cap and no claim step. **(Kris)**
- No transaction per tick. Balance at any moment is `settled + 1000 × seconds since last settlement ÷ 86400`. The program settles a member's income only when that member pays. **(call)**
- Incoming payments are added to the settled balance without touching the settlement time.

### 5.4 Paying

`pay(recipient, amount, payer_name)`, signed by the payer, who is also the Solana fee payer.

1. Settle the payer's income.
2. Work out the fee in CLPC from the pool (5.6).
3. Require balance ≥ amount + fee. Move `amount` to the recipient; move the fee into the pool's CLPC reserve.
4. Return to the payer Solana's base fee for one signature (5,000 lamports today). A program cannot read what a transaction actually cost, so the refund is this fixed amount. Any priority fee is not refunded; it comes out of the payer's float.
5. Record the payer's name (up to about 32 bytes) in the transaction's log so the recipient's history can show it. **(call)** This is public.

Rules: the recipient must be an existing Member; paying yourself is rejected; a payment never counts until the transaction is confirmed.

**One refund per transaction.** A transaction may contain exactly one `pay`, with a single signer, and `pay` must reject being called from another program. Otherwise one 5,000-lamport fee could collect many refunds and pull SOL out of the pool.

The apps simulate a payment before sending it, so a payment that would fail is caught before it costs a fee. A Solana transaction is only valid for roughly a minute after it is signed, which gives the short expiry Kris wanted: a payment that cannot reach the chain in time is void.

### 5.5 Joining

- A new account is created by an existing member, who pays for it. **(Kris)** Kris values this as a feature: a neighbour brings you in.
- The newcomer's app creates a key and shows a QR. The member scans it with the normal scan button and confirms.
- Creating an account needs SOL for two things: the Member record's locked deposit, and a small SOL float in the newcomer's wallet so they can front transaction fees. Together roughly 2 million lamports, about $0.17 at current prices, which is the SOL of about 400 payments' fees (estimate; the builder must compute it from the live rent rate). Most of it is the minimum a Solana wallet and record must hold, so it stays locked.
- **The inviting member pays this in CLPC, through the pool, at the pool's price. (call)** At the target price that is about 400 CLPC. Members hold no spare SOL by design, so asking them for SOL would bring back a second balance. Together with reviving (5.6), this is where SOL leaves the pool for something other than a fee. See the mainnet blocker in section 10.
- Config carries an optional extra join charge in CLPC, paid into the pool, set to zero at the start. **(call)** A new account earns enough in a day to fund about two more joins, so this is the knob to turn if accounts are being mass-produced during the pilot.
- The first members are created by an admin script.

### 5.6 The fee pool

- Constant product: `SOL reserve × CLPC reserve = k`. **(Kris)**
- **CLPC in, SOL out: only as the fee of a real payment**, one fee at a time, and as the cost of a join. SOL never goes to a wallet on request. **(Kris: build this restriction from the start.)**
- **SOL in, CLPC out: open to any member**, at the current price. This is how the pool is refilled, and the only way to obtain CLPC other than income and payments.
- **A gift is also possible:** anyone can add SOL to the pool and take nothing out, which lowers the fee. **(call)**
- In the first version the pool is refilled with the admin script. The apps have no refill screen. **(call)**
- The pool starts with SOL deposited by the admin and an initial CLPC reserve created at setup; their ratio sets the starting fee. Target: about 1 CLPC per payment. **(call)** The fee is not fixed: it rises with the square of how far the SOL reserve has fallen, and each join removes as much SOL as about 400 payments. A small pool therefore swings a lot. Suggested start on devnet: at least 10 SOL against 2,000,000 CLPC, so that 1,000 joins raise the fee by about half. The final size is open.
- If the pool cannot cover a refund, the payment still goes through, charges no CLPC fee, and the payer's own float pays. The float therefore needs room for a number of unrefunded payments; how many is left to the builder.
- **Reviving a drained float. (call)** Unrefunded payments and priority fees wear a float down, and a wallet with no SOL cannot send anything. So a member can pay CLPC, at the pool's price, to restore a float to its starting level: their own while it still has enough to send the request, or another member's that has run dry. It is allowed only when the float is below a threshold, and at most once per account in a set period (suggested: 30 days), because each use moves a little SOL into a wallet.
- Held in reserve, not built now: a per-account daily limit on refunds, to be added if the pilot shows abuse. **(Kris)**

### 5.7 Changing the rules

- On devnet, one admin key controls Config and program upgrades.
- Before real SOL: that key moves to several people, and later the program is locked. **(call)**

## 6. The apps

Both apps do the same things. The iPhone app is built first.

### 6.1 Screens and behaviour **(Kris, from the mockups he approved)**

- **Home.** True black. The person's name at the top centre, tap to edit, stored only on the phone. A small signal dot. The balance alone in the centre. Two round glass buttons at the bottom: scan (pay) and QR (get paid). Icons only. No labels, hints or explanations anywhere.
- **The number.** Whole pesos in white, decimals in gray ticking in real time (four places). Chilean format: `12.345,2871`. No currency label. Gray means not yet real; white means real and spendable.
- **History.** Tapping the number reveals past payments: name, time, signed amount. It is read from the chain through public access points and cached on the phone, so it is best-effort: older entries may be missing, and on a new phone the remembered names are gone. **(call)**
- **Get paid.** Number pad for a whole-peso amount, then a QR with the amount. Skipping the amount gives an open QR a stall can print; the payer then types the amount. When the payment confirms, a tick and the payer's name.
- **Pay.** Camera scanner, then a confirm screen with the amount and the recipient's name and one button approved by face or fingerprint. The amount shows gray while travelling and white when confirmed. If it does not confirm before the transaction expires, it shows as failed.
- **No route.** When there is no path to the internet, the dot goes hollow and both buttons dim. The number keeps ticking.
- **Joining.** A phone with no account shows its join QR. Once a member has scanned it, the number appears at zero and starts ticking.
- **Wallet.** Reached by a long press on the number **(call)**: back up, move to another phone.
- **Not in parts 1 to 3: "nearby".** Finding the other person over Bluetooth instead of a QR, for paying or inviting, is something Kris wants explored in the visual design. It is not built until the Bluetooth part is planned.

Any unavoidable text is in Chilean Spanish. Controls still carry spoken names for screen readers.

### 6.2 Names **(Kris)**

Local only, not a registry, not verified. Your name is carried inside your QR and sent along with your payments, and each phone remembers the names it has seen so history is readable.

### 6.3 Keys and safety **(Kris: self-custody; details are calls)**

- The wallet key is created on the phone and kept in the system's protected storage, released by fingerprint, face or the device passcode. Solana's key type cannot live inside the iPhone's Secure Enclave, so the key is stored encrypted and guarded by it.
- Opening the app and approving a payment both ask for biometrics.
- **Move to a new phone:** the old phone shows a QR, the new one scans it. This copies the same key, as importing a wallet does. Nothing changes on-chain and the old phone keeps working until the app is removed from it.
- **Backup:** the same QR, encrypted with a password the person chooses, to save or print.
- There is no recovery service. A lost phone without a backup is a lost account, and a leaked backup with its password is the owner's responsibility. **(Kris)**

### 6.4 Talking to the chain

- The app ships with a list of public Solana access points and lets the person add their own. **(call)**
- The balance is computed locally from the Member record, using the chain's clock rather than the phone's.
- While open, the app watches its own Member record so an incoming payment shows at once.
- QR contents: a `valpix:` link carrying the recipient's key, an optional amount and the name; a separate form for joining. **(call)**

### 6.5 Testing on the simulator

Debug builds need a way to feed in a QR payload without a camera, so two simulators can pay each other against a local test validator or devnet.

## 7. Borrowing a neighbour's internet (Bluetooth)

- **Rule (Kris):** every payment is verified on-chain. If neither the buyer, the seller nor any phone in reach has internet, no payment happens.
- A phone with the app open and internet offers itself over Bluetooth Low Energy. A phone without internet connects and asks it to do a small fixed set of things: fetch what is needed to build a transaction, submit a signed transaction, report whether it confirmed.
- Route order: own internet, then the other party's phone, then any nearby phone.
- The carrying phone cannot alter a payment, because it is signed by the payer. It can read it, which reveals nothing that is not already public on the chain. It can drop it.
- **First: one hop**, straight to a phone that has internet. **Then: multi-hop**, where phones without internet pass requests along, in the style of bitchat. **(call)** Message frames should carry a hop limit from the start so the second step does not change the format.
- Reference only: bitchat's whitepaper and iOS source (public domain). Its Android source is GPL-3.0 and must not be copied.
- Known platform limit: Bluetooth relaying is reliable only while the app is on screen.

**A borrowed confirmation counts as confirmed. (Kris)** A phone cannot cheaply prove to another phone that Solana confirmed something. A buyer borrowing a connection is safe, since the worst case is a payment that fails. A seller who learns of the confirmation only through someone else's phone is trusting that phone, and a dishonest buyer could bring their own. Kris's decision is to keep it simple: the seller sees the normal confirmation whichever route it arrived by, with no separate "heard" state and no requirement that sellers have their own connection. Hardening this is future work; the risk is listed in section 10.

## 8. Build order

Each part ends in something that runs and can be tested, and each gets its own implementation plan.

1. **The Solana program**, tested on a local validator and deployed to devnet, with a small admin script for setup, first members and funding the pool.
2. **The iPhone app**, using the phone's own internet: create a wallet, join and invite, the live number, get paid, pay, history, backup and move.
3. **The Android app**, matching the iPhone app.
4. **Bluetooth borrowing** on both, one hop then multi-hop.

## 9. Left to the builder

- Program framework (Anchor is the natural default; earlier related work used it), exact account layouts, instruction names, rounding, error codes.
- How the pool holds its SOL and pays refunds, the float size, the pool floor.
- Solana client approach in Swift and Kotlin: a community library or a thin client of our own.
- Local storage, access-point failover, how the chain clock is tracked.
- QR and backup encodings, password-based encryption scheme.
- Minimum iOS and Android versions, and the fallback for glass effects on older systems. The visual layer should be easy to restyle when the design proposals land.
- Repository layout. Suggested: `program/`, `ios/`, `android/`, `docs/`.
- Test tooling.

## 10. Known limits

- CLPC has no backing; it is worth what people accept it for.
- Until identity checks exist, one person can hold several accounts and several incomes.
- Every payment, and the name sent with it, is public.
- The pool rations SOL but cannot create it; someone must add SOL from outside.
- **Mainnet blocker:** joining and reviving through the pool move SOL into a wallet, so fake accounts could extract it a little at a time. This costs nothing real on devnet. Before real SOL, both must either require a verified person or be paid with the member's own SOL.
- Accounts can multiply during the pilot, since a day's income pays for about two joins. This can drain a devnet pool and make joining expensive for honest people. The extra join charge in Config and the daily refund limit are the two knobs held ready.
- History is best-effort and names do not survive a move to a new phone.
- No signal in reach means no payment.
- A seller with no internet of their own trusts the phone that relays the confirmation. A dishonest buyer with a second phone could fake it.

## 11. Open questions

1. Does CLPC stay as the coin's name?
2. The pool's starting size, and who funds it first.
3. The visual direction, after the Claude Design proposals.

## 12. Working principle

Simpler is best, and security comes second for now. **(Kris)** Where a safeguard would add a rule, a screen or a state, the first version leaves it out and records the risk in section 10, to be solved once the project proves relevant. The exceptions already agreed are the ones in section 5: SOL leaves the pool only as fees, joins and revivals, and one refund per transaction.
