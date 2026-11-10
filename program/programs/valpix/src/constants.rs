use anchor_lang::prelude::*;

/// Micro-CLPC per CLPC: the coin has 6 decimal places.
pub const UNIT: u64 = 1_000_000;
pub const SECONDS_PER_DAY: u64 = 86_400;
/// Longest payer name, in UTF-8 bytes.
pub const MAX_NAME_BYTES: usize = 32;

pub const CONFIG_SEED: &[u8] = b"config";
pub const POOL_SEED: &[u8] = b"pool";
pub const VAULT_SEED: &[u8] = b"vault";
/// Followed by the member's wallet key.
pub const MEMBER_SEED: &[u8] = b"member";

pub const DEFAULT_DAILY_RATE: u64 = 1000 * UNIT;
/// Solana's base fee for one signature.
pub const DEFAULT_FEE_REFUND_LAMPORTS: u64 = 5_000;
/// 2% of a day's income.
pub const DEFAULT_MAX_FEE: u64 = 20 * UNIT;
pub const DEFAULT_REVIVE_PERIOD_SECS: i64 = 30 * 86_400;
