# valpix program

The Solana program that holds every valpix balance and rule. Design:
`../docs/superpowers/specs/2026-11-10-valpix-design.md`, section 5.

Program address: `5AVhdmuAbx8HWJdR6h7QAPojfJHt7Z6TAa9pwj12wqcx`

**Devnet: deployed on 10 November 2026**, with a pool of 5 SOL against
1,000,000 CLPC (a fee of about 1 CLPC).

## What it does

| Instruction | Who | What |
|---|---|---|
| `initialize` | admin | Creates the rules and the fee pool, and puts the first SOL in. |
| `update_config` | admin | Changes any rule. |
| `admin_create_member` | admin | Creates a first member, paid from the admin's own SOL. |
| `pay` | member | Moves CLPC to another member. The pool returns the Solana fee and charges its price in CLPC. |
| `join` | member | Creates an account for a newcomer. The pool supplies the SOL; the inviter pays in CLPC. |
| `fund_pool` | member | Adds SOL to the pool and takes CLPC out at the current price. |
| `revive`, `revive_self` | member | Tops a drained wallet back up, at most once per period. |

Sending SOL straight to the vault address is a gift: it lowers the fee and
gives nothing back.

Income is 1000 CLPC a day per member, worked out from the chain's clock and
written only when that member spends. CLPC has 6 decimal places.

`pay`, `join` and `revive` must stand alone: the only other instructions
allowed in the same transaction are compute-budget ones, and they cannot be
called from another program. This keeps one Solana fee from collecting more
than one refund.

A payment's fee is capped (`max_fee`, 20 CLPC to start). When the pool is
below its floor, or its price for a refund is above the cap, the payment
still goes through: it costs no CLPC and the payer's own wallet covers the
Solana fee.

## Requirements

- Rust (the toolchain is pinned in `rust-toolchain.toml`)
- Solana CLI 4.x: `sh -c "$(curl -sSfL https://release.anza.xyz/stable/install)"`
- Anchor CLI 1.2.2 (a prebuilt binary is on the Anchor releases page)
- Node and pnpm, for the admin script

## Build and test

```sh
anchor build          # program + idl
cargo test -p valpix  # 55 tests, in-process, no validator needed
```

After changing the program's interface, copy the new interface file so the
apps can read it: `cp target/idl/valpix.json idl/valpix.json`.

## Run locally

```sh
solana-test-validator --reset \
  --bpf-program 5AVhdmuAbx8HWJdR6h7QAPojfJHt7Z6TAa9pwj12wqcx target/deploy/valpix.so

solana airdrop 100 -u localhost
cd scripts && pnpm install
pnpm admin init --sol 10 --clpc 2000000
pnpm admin member <wallet address>
pnpm admin show <wallet address>
```

Other commands: `pay`, `join`, `fund`, `gift`. Run `pnpm admin` for the list.
Every command takes `--url` and `--keypair`.

## Devnet

```sh
./scripts/deploy-devnet.sh 5 1000000   # pool: 5 SOL against 1,000,000 CLPC
```

The program's keypair is kept outside the repo, by default at
`~/.config/solana/valpix-program-keypair.json`. Whoever holds it and the
admin wallet controls the program. Neither belongs in git.
