use anchor_lang::prelude::*;

use crate::{error::ValpixError, math};

/// The rules. One per program.
#[account]
#[derive(InitSpace)]
pub struct Config {
    /// The only key that may change these rules or create members directly.
    pub admin: Pubkey,
    /// Income per member per day, in micro-CLPC.
    pub daily_rate: u64,
    /// SOL returned to whoever sends a payment, join or revival: Solana's
    /// base fee for one signature.
    pub fee_refund_lamports: u64,
    /// SOL a member's wallet starts with, and is topped back up to.
    pub float_lamports: u64,
    /// Extra micro-CLPC charged for a join, on top of the pool price.
    pub join_surcharge: u64,
    /// A wallet may be revived only while it holds less than this.
    pub revive_threshold_lamports: u64,
    /// Minimum seconds between two revivals of the same member.
    pub revive_period_secs: i64,
    /// The pool stops paying out once its SOL would fall below this.
    pub pool_floor_lamports: u64,
    pub bump: u8,
    pub vault_bump: u8,
}

/// The CLPC side of the fee pool. The SOL side is whatever the vault
/// address holds above its own rent minimum.
#[account]
#[derive(InitSpace)]
pub struct Pool {
    pub clpc_reserve: u64,
    pub bump: u8,
}

/// One person's account.
#[account]
#[derive(InitSpace)]
pub struct Member {
    pub wallet: Pubkey,
    /// Micro-CLPC as of `last_settled_ts`. Income since then is added on top.
    pub settled: u64,
    pub last_settled_ts: i64,
    pub created_ts: i64,
    pub inviter: Pubkey,
    /// Zero until the member's wallet is first revived.
    pub last_revive_ts: i64,
    pub bump: u8,
}

impl Member {
    /// Adds the income earned since the last settlement to the settled
    /// balance. Called only when this member spends.
    pub fn settle(&mut self, daily_rate: u64, now: i64) -> Result<()> {
        let earned = math::accrued(daily_rate, self.last_settled_ts, now);
        self.settled = self
            .settled
            .checked_add(earned)
            .ok_or(ValpixError::Overflow)?;
        if now > self.last_settled_ts {
            self.last_settled_ts = now;
        }
        Ok(())
    }
}
