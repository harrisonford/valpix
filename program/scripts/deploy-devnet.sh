#!/usr/bin/env bash
# Deploys valpix to devnet and sets up the pool.
#
# Needs devnet SOL in the admin wallet first: about 3 SOL to deploy, plus
# whatever goes into the pool. The command-line faucet is often rate-limited;
# https://faucet.solana.com works with a GitHub login.
#
#   ./scripts/deploy-devnet.sh [pool SOL, default 5] [pool CLPC, default 1000000]
set -euo pipefail
cd "$(dirname "$0")/.."

POOL_SOL="${1:-5}"
POOL_CLPC="${2:-1000000}"
URL="https://api.devnet.solana.com"
PROGRAM_KEYPAIR="${VALPIX_PROGRAM_KEYPAIR:-$HOME/.config/solana/valpix-program-keypair.json}"

echo "admin wallet: $(solana-keygen pubkey)"
echo "balance:      $(solana balance -u "$URL")"

anchor build
solana program deploy target/deploy/valpix.so \
  --program-id "$PROGRAM_KEYPAIR" -u "$URL"

cd scripts
pnpm -s admin init --sol "$POOL_SOL" --clpc "$POOL_CLPC" --url "$URL"
