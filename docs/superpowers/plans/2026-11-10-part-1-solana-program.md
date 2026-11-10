# valpix Part 1 — Solana Program Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Solana program that holds every valpix balance and rule (daily income, payments, joining, the fee pool), tested locally and deployed to devnet with an admin script.

**Architecture:** One Anchor program, `valpix`. Three record types (`Config`, `Pool`, `Member`) plus a system-owned `vault` address that holds the pool's SOL. All arithmetic lives in a pure `math` module with unit tests; instruction handlers are thin and are tested end to end in-process with LiteSVM. A small TypeScript admin script drives setup against a local validator and devnet.

**Tech Stack:** Rust 1.95, Anchor 1.2.2 (`anchor-lang`), Agave/Solana CLI 4.3 (`cargo-build-sbf`), LiteSVM for tests, TypeScript + `@anchor-lang/core` for the admin script (run with `pnpm` + `tsx`).

**Spec:** `docs/superpowers/specs/2026-11-10-valpix-design.md` (section 5 is the source for every rule below).

## Global Constraints

- CLPC lives inside the program, not as a Solana token. 6 decimals: `UNIT = 1_000_000` micro-CLPC.
- Income is exactly 1000 CLPC per day per member, computed lazily: `settled + daily_rate × elapsed_secs ÷ 86_400`, settled only when that member spends.
- A payment counts only when confirmed; the recipient must be an existing member; paying yourself is rejected.
- The fee refund is fixed at Solana's base fee for one signature, `5_000` lamports. Priority fees are never refunded.
- A transaction may hold exactly one valpix instruction, and that instruction must not be reached through another program. Compute-budget instructions may accompany it.
- SOL leaves the vault only as: a fee refund, the cost of a join, a revival. SOL enters freely.
- Pool pricing is constant product. Rounding always favours the pool: CLPC charged rounds up, CLPC paid out rounds down.
- Payer names are at most 32 bytes of UTF-8.
- No server, no database. Devnet only. Keys and `.env` files never enter the repo.
- Simpler is best: no safeguard beyond those listed here.

## Review Focus

1. **Empty or low pool.** With the vault at or below its floor, a payment still succeeds, charges no CLPC fee and refunds nothing. → Task 3 test `pay_without_refund_when_pool_is_low`.
2. **Refund multiplication.** Two `pay` instructions in one transaction, or `pay` reached through another program, are rejected. → Task 3 test `rejects_two_pays_in_one_transaction`.
3. **Edges of income.** A payment in the same second as joining sees zero income; a member untouched for years settles without overflow. → Task 1 tests `accrued_is_zero_for_zero_elapsed`, `accrued_does_not_overflow_over_a_century`.
4. **Bad counterparties and names.** Paying a non-member, paying yourself, a zero amount, or a name over 32 bytes is rejected; a multi-byte name within 32 bytes is accepted. → Task 3 tests `rejects_self_payment`, `rejects_zero_amount`, `rejects_long_name`, `accepts_multibyte_name`.
5. **Joining twice, or a wallet that already holds SOL.** A wallet that is already a member cannot be joined again; a newcomer wallet that already holds SOL is only topped up to the float. → Task 4 tests `rejects_join_of_existing_member`, `join_only_tops_up_a_funded_wallet`.

---

## File Structure

```
program/
  Anchor.toml
  Cargo.toml                      workspace
  programs/valpix/
    Cargo.toml
    src/
      lib.rs                      #[program] entry points only
      constants.rs                seeds and fixed numbers
      errors.rs                   ValpixError
      state.rs                    Config, Pool, Member
      math.rs                     accrued, quote_clpc_in, quote_clpc_out (pure)
      guard.rs                    one-valpix-instruction, top-level-only check
      vault.rs                    helpers that move SOL out of the vault
      instructions/
        mod.rs
        initialize.rs             initialize, update_config
        admin_create_member.rs
        pay.rs
        join.rs
        fund_pool.rs
        revive.rs                 revive, revive_self
    tests/
      common/mod.rs               LiteSVM harness
      setup.rs  pay.rs  join.rs  pool.rs
  scripts/
    package.json
    admin.ts                      init | member | fund | show | pay | join
```

## Shared definitions (used by every task)

```rust
// constants.rs
pub const UNIT: u64 = 1_000_000;
pub const SECONDS_PER_DAY: u64 = 86_400;
pub const MAX_NAME_BYTES: usize = 32;
pub const CONFIG_SEED: &[u8] = b"config";
pub const POOL_SEED: &[u8] = b"pool";
pub const VAULT_SEED: &[u8] = b"vault";
pub const MEMBER_SEED: &[u8] = b"member";   // + wallet pubkey

// state.rs
pub struct Config { admin: Pubkey, daily_rate: u64, fee_refund_lamports: u64,
    float_lamports: u64, join_surcharge: u64, revive_threshold_lamports: u64,
    revive_period_secs: i64, pool_floor_lamports: u64, bump: u8, vault_bump: u8 }
pub struct Pool   { clpc_reserve: u64, bump: u8 }
pub struct Member { wallet: Pubkey, settled: u64, last_settled_ts: i64, created_ts: i64,
    inviter: Pubkey, last_revive_ts: i64, bump: u8 }
```

Defaults set by `initialize`: `daily_rate = 1000 × UNIT`, `fee_refund_lamports = 5_000`, `join_surcharge = 0`, `revive_period_secs = 30 × 86_400`. `float_lamports`, `revive_threshold_lamports` and `pool_floor_lamports` are parameters.

The pool's SOL reserve is `vault.lamports − rent_exempt_minimum(0)`.

---

### Task 1: Workspace and the pure math

**Files:**
- Create: `program/` workspace via `anchor init`, then `programs/valpix/src/{constants,math}.rs`
- Test: unit tests inside `math.rs`

**Interfaces:**
- Produces:
  - `math::accrued(daily_rate: u64, from_ts: i64, to_ts: i64) -> u64` — floor; 0 when `to_ts <= from_ts`.
  - `math::quote_clpc_in(sol_reserve: u64, clpc_reserve: u64, lamports_out: u64) -> Option<u64>` — `ceil(clpc_reserve × lamports_out ÷ (sol_reserve − lamports_out))`; `None` when `lamports_out >= sol_reserve`.
  - `math::quote_clpc_out(sol_reserve: u64, clpc_reserve: u64, lamports_in: u64) -> u64` — floor of `clpc_reserve × lamports_in ÷ (sol_reserve + lamports_in)`.

- [ ] **Step 1: Scaffold.** `anchor init` into `program/`, rename the program crate to `valpix`, confirm `anchor build` produces `target/deploy/valpix.so`.
- [ ] **Step 2: Write the failing tests** in `math.rs`:

```rust
#[test] fn accrued_one_day_is_1000_clpc() { assert_eq!(accrued(1000 * UNIT, 0, 86_400), 1000 * UNIT); }
#[test] fn accrued_one_second_floors() { assert_eq!(accrued(1000 * UNIT, 0, 1), 11_574); }
#[test] fn accrued_is_zero_for_zero_elapsed() { assert_eq!(accrued(1000 * UNIT, 50, 50), 0); assert_eq!(accrued(1000 * UNIT, 50, 40), 0); }
#[test] fn accrued_does_not_overflow_over_a_century() { assert_eq!(accrued(1000 * UNIT, 0, 100 * 365 * 86_400), 36_500_000 * UNIT); }
#[test] fn fee_is_about_one_clpc_at_the_starting_pool() { assert_eq!(quote_clpc_in(1_000_000_000, 200_000 * UNIT, 5_000), Some(1_000_006)); }
#[test] fn fee_is_about_four_clpc_at_half_the_sol() { assert_eq!(quote_clpc_in(500_000_000, 400_000 * UNIT, 5_000), Some(4_000_041)); }
#[test] fn quote_in_is_none_when_asking_for_the_whole_reserve() { assert_eq!(quote_clpc_in(5_000, 1, 5_000), None); assert_eq!(quote_clpc_in(0, 1, 1), None); }
#[test] fn refill_restores_the_starting_price() { assert_eq!(quote_clpc_out(500_000_000, 400_000 * UNIT, 500_000_000), 200_000 * UNIT); }
#[test] fn rounding_never_shrinks_k() {
    let (s, c) = (1_000_000_000u64, 200_000 * UNIT);
    let fee = quote_clpc_in(s, c, 5_000).unwrap();
    assert!((s as u128 - 5_000) * (c as u128 + fee as u128) >= s as u128 * c as u128);
    let out = quote_clpc_out(s, c, 7_777);
    assert!((s as u128 + 7_777) * (c as u128 - out as u128) >= s as u128 * c as u128);
}
```

- [ ] **Step 3: Run** `cargo test -p valpix --lib` — expect failures (functions missing).
- [ ] **Step 4: Implement** the three functions with `u128` intermediates.
- [ ] **Step 5: Run** `cargo test -p valpix --lib` — expect all pass.
- [ ] **Step 6: Commit** `feat(program): workspace and pool/income math`.

### Task 2: State, setup instructions and the test harness

**Files:**
- Create: `state.rs`, `errors.rs`, `instructions/{mod,initialize,admin_create_member}.rs`, `tests/common/mod.rs`, `tests/setup.rs`
- Modify: `lib.rs`

**Interfaces:**
- Consumes: Task 1 math, shared definitions.
- Produces:
  - `initialize(ctx, params: InitParams)` — `InitParams { float_lamports, revive_threshold_lamports, pool_floor_lamports, initial_sol_lamports, initial_clpc_reserve }`. Admin signs and pays. Creates `Config`, `Pool`, and funds the vault with `rent_exempt_minimum(0) + initial_sol_lamports`.
  - `update_config(ctx, params: ConfigParams)` — admin only; every field of `Config` except `admin` and the bumps.
  - `admin_create_member(ctx)` — admin signs and pays the member record's rent; tops the wallet up to `float_lamports` from the admin's own SOL. `inviter` is recorded as the admin.
  - Errors: `ValpixError::{NotAdmin, AlreadyMember, NotMember, SelfPayment, ZeroAmount, NameTooLong, InsufficientFunds, SlippageExceeded, NotAlone, CalledFromProgram, ReviveNotNeeded, ReviveTooSoon, Overflow}`.
  - Test harness `common`: `Env::new() -> Env` (LiteSVM with the program loaded, initialised with a 1 SOL / 200_000 CLPC pool, `float_lamports = 1_500_000`, `revive_threshold_lamports = 1_000_000`, `pool_floor_lamports = 10_000`), `env.new_member() -> Keypair`, `env.advance(secs)`, `env.member(&Pubkey) -> Member`, `env.pool() -> Pool`, `env.sol_reserve() -> u64`, `env.balance(&Pubkey) -> u64` (settled plus income to now), `env.send(ixs, signers) -> Result<_, _>`.

- [ ] **Step 1: Write the failing tests** in `tests/setup.rs`:
  - `initialize_sets_defaults`: `daily_rate == 1000 × UNIT`, `fee_refund_lamports == 5_000`, `join_surcharge == 0`, `pool.clpc_reserve == 200_000 × UNIT`, `env.sol_reserve() == 1_000_000_000`.
  - `initialize_twice_fails`.
  - `admin_creates_a_member_with_a_float`: member exists with `settled == 0`, wallet holds `1_500_000` lamports.
  - `only_admin_can_create_members_or_update_config`.
  - `income_accrues_at_1000_a_day`: after `advance(86_400)`, `env.balance(member) == 1000 × UNIT`.
- [ ] **Step 2: Run** `anchor build && cargo test -p valpix --test setup` — expect failures.
- [ ] **Step 3: Implement** state, errors, the three instructions and the harness.
- [ ] **Step 4: Run** the same command — expect pass.
- [ ] **Step 5: Commit** `feat(program): config, pool, members and admin setup`.

### Task 3: Paying

**Files:**
- Create: `instructions/pay.rs`, `guard.rs`, `vault.rs`, `tests/pay.rs`
- Modify: `lib.rs`, `instructions/mod.rs`

**Interfaces:**
- Consumes: Task 2 state and harness.
- Produces:
  - `pay(ctx, amount: u64, payer_name: String)` — `amount` in micro-CLPC. Accounts: payer (signer, fee payer), payer member, recipient member, config, pool, vault, system program, instructions sysvar.
  - Event `Paid { payer: Pubkey, recipient: Pubkey, amount: u64, fee: u64, name: String, ts: i64 }`.
  - `guard::require_alone(instructions_sysvar: &AccountInfo) -> Result<()>` — passes only when the current instruction is top-level and every other instruction in the transaction belongs to the compute-budget program.
  - `vault::pay_out(vault, to, system_program, lamports, vault_bump) -> Result<()>`.
  - `vault::refund_quote(config, pool, vault_lamports, rent_min, extra_lamports_out) -> Option<(u64 /*lamports*/, u64 /*clpc*/)>` — `None` when the vault would fall below its floor.

- [ ] **Step 1: Write the failing tests** in `tests/pay.rs` (two members, a day of income each unless stated):
  - `pay_moves_amount_and_charges_the_pool_fee`: paying `300 × UNIT` leaves the payer with `1000 × UNIT − 300 × UNIT − 1_000_006`, gives the recipient `+300 × UNIT`, grows `pool.clpc_reserve` by `1_000_006`, shrinks `sol_reserve` by `5_000`, and leaves the payer's wallet lamports unchanged.
  - `recipient_income_is_untouched_by_incoming_payment`: the recipient's `last_settled_ts` does not change.
  - `pay_without_refund_when_pool_is_low`: with the vault drained to the floor, the payment succeeds, `fee == 0`, the wallet is down `5_000` lamports.
  - `rejects_overspend`: amount equal to the whole balance fails with `InsufficientFunds` (the fee cannot be covered).
  - `rejects_self_payment`, `rejects_zero_amount`, `rejects_payment_to_non_member`.
  - `rejects_long_name` (33 bytes), `accepts_multibyte_name` (`"Ñandú café ☕"`).
  - `rejects_two_pays_in_one_transaction` → `NotAlone`.
  - `allows_compute_budget_instructions_alongside`.
- [ ] **Step 2: Run** `anchor build && cargo test -p valpix --test pay` — expect failures.
- [ ] **Step 3: Implement.** Order inside `pay`: guard, validate, settle payer income, quote, check funds, move CLPC, grow pool reserve, refund from vault, emit `Paid`.
- [ ] **Step 4: Run** — expect pass.
- [ ] **Step 5: Commit** `feat(program): pay with pool-funded fee refund`.

### Task 4: Joining

**Files:**
- Create: `instructions/join.rs`, `tests/join.rs`
- Modify: `lib.rs`, `instructions/mod.rs`

**Interfaces:**
- Consumes: Task 3 `guard`, `vault`.
- Produces: `join(ctx)` — accounts: inviter (signer), inviter member, newcomer wallet, newcomer member (uninitialised PDA), config, pool, vault, system program, instructions sysvar. The vault pays the newcomer record's rent, tops the newcomer wallet up to `float_lamports`, and refunds the inviter's fee; the inviter is charged `quote_clpc_in(total lamports out) + join_surcharge`. Event `Joined { inviter, newcomer, cost: u64, ts: i64 }`.

- [ ] **Step 1: Write the failing tests** in `tests/join.rs`:
  - `member_brings_in_a_newcomer`: the newcomer member exists with `settled == 0`, `inviter == inviter wallet`; the newcomer wallet holds `1_500_000` lamports; the inviter's wallet lamports are unchanged; the inviter's CLPC fell by exactly the quoted cost; `sol_reserve` fell by rent + float + `5_000`.
  - `newcomer_starts_earning_at_once`: a day later the newcomer's balance is `1000 × UNIT`.
  - `join_charges_the_surcharge_when_set`.
  - `rejects_join_of_existing_member` → `AlreadyMember`.
  - `join_only_tops_up_a_funded_wallet`: a newcomer wallet already holding `1_000_000` lamports receives `500_000`.
  - `rejects_join_when_inviter_cannot_afford_it` → `InsufficientFunds`.
  - `rejects_join_when_pool_cannot_cover_it` → `InsufficientFunds` is wrong here; expect `PoolTooLow` (add this error).
  - `non_member_cannot_invite`.
- [ ] **Step 2: Run** `anchor build && cargo test -p valpix --test join` — expect failures.
- [ ] **Step 3: Implement.** The newcomer record is created by a system-program call signed by both the vault and the new record's seeds, then written by hand.
- [ ] **Step 4: Run** — expect pass.
- [ ] **Step 5: Commit** `feat(program): join through an existing member`.

### Task 5: Refilling the pool and reviving a float

**Files:**
- Create: `instructions/{fund_pool,revive}.rs`, `tests/pool.rs`
- Modify: `lib.rs`, `instructions/mod.rs`

**Interfaces:**
- Produces:
  - `fund_pool(ctx, lamports: u64, min_clpc_out: u64)` — a member sends SOL to the vault and receives `quote_clpc_out` CLPC; fails with `SlippageExceeded` below `min_clpc_out`.
  - `revive(ctx)` — caller member pays for another member's wallet; `revive_self(ctx)` — for the caller's own. Both require the target wallet below `revive_threshold_lamports` and `now − last_revive_ts >= revive_period_secs`, top the wallet up to `float_lamports`, refund the caller's fee, and charge the caller the pool price. Event `Revived { payer, target, cost, ts }`.
  - A gift needs no instruction: SOL sent straight to the vault address lowers the fee.

- [ ] **Step 1: Write the failing tests** in `tests/pool.rs`:
  - `funding_gives_clpc_and_lowers_the_fee`.
  - `funding_respects_min_out` → `SlippageExceeded`.
  - `a_plain_transfer_to_the_vault_lowers_the_fee`.
  - `revive_tops_up_a_drained_neighbour`.
  - `revive_self_tops_up_own_wallet`.
  - `rejects_revive_above_threshold` → `ReviveNotNeeded`.
  - `rejects_second_revive_within_period` → `ReviveTooSoon`; succeeds after `advance(30 × 86_400)`.
- [ ] **Step 2: Run** `anchor build && cargo test -p valpix --test pool` — expect failures.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `cargo test -p valpix` (everything) — expect pass.
- [ ] **Step 5: Commit** `feat(program): pool refill and float revival`.

### Task 6: Admin script, local run, devnet

**Files:**
- Create: `program/scripts/{package.json,admin.ts}`, `program/README.md`

**Interfaces:**
- Produces: `pnpm admin <cmd> [--url <rpc>] [--keypair <path>]` with `init --sol <n> --clpc <n>`, `member <wallet>`, `fund --sol <n>`, `show [wallet]`, `pay --to <wallet> --amount <clpc> --name <s> --keypair <payer>`, `join <wallet> --keypair <inviter>`. `show` prints config, pool reserves, the current fee, and a member's live balance.

- [ ] **Step 1: Write** `admin.ts` against the generated IDL.
- [ ] **Step 2: Local run.** Start a local validator with the program loaded; run `init`, `member` ×2, `pay`, `join`, `show`; confirm the printed balances match the rules.
- [ ] **Step 3: Devnet.** Create a deploy key outside the repo, obtain devnet SOL, `anchor deploy --provider.cluster devnet`, run `init` and create the first member. Record the program ID in `program/README.md` and `Anchor.toml`.
- [ ] **Step 4: Commit** `feat(program): admin script and devnet deployment`.
