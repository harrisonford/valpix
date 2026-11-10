//! The SOL side of the pool. The vault is a system-owned address that only
//! this program can sign for; these are the only functions that spend it.

use anchor_lang::{prelude::*, system_program};

use crate::{constants::VAULT_SEED, math, state::*};

/// The pool's spendable SOL: what the vault holds above its own rent minimum.
pub fn sol_reserve(vault: &AccountInfo) -> Result<u64> {
    let rent_min = Rent::get()?.minimum_balance(0);
    Ok(vault.lamports().saturating_sub(rent_min))
}

/// Micro-CLPC the pool charges for `lamports_out`, or `None` when paying it
/// would leave the pool below its floor.
pub fn quote(config: &Config, pool: &Pool, sol_reserve: u64, lamports_out: u64) -> Option<u64> {
    let remaining = sol_reserve.checked_sub(lamports_out)?;
    if remaining < config.pool_floor_lamports {
        return None;
    }
    math::quote_clpc_in(sol_reserve, pool.clpc_reserve, lamports_out)
}

/// The CLPC fee for refunding one payment, or `None` when the pool cannot
/// or should not refund it: it would fall below its floor, or its price has
/// climbed past the cap. The payment then costs no CLPC and gets no refund.
pub fn fee_quote(config: &Config, pool: &Pool, sol_reserve: u64) -> Option<u64> {
    quote(config, pool, sol_reserve, config.fee_refund_lamports).filter(|fee| *fee <= config.max_fee)
}

/// Moves SOL from the vault to `to`.
pub fn pay_out<'info>(
    vault: &AccountInfo<'info>,
    to: &AccountInfo<'info>,
    lamports: u64,
    vault_bump: u8,
) -> Result<()> {
    if lamports == 0 {
        return Ok(());
    }
    system_program::transfer(
        CpiContext::new_with_signer(
            system_program::ID,
            system_program::Transfer { from: vault.clone(), to: to.clone() },
            &[&[VAULT_SEED, &[vault_bump]]],
        ),
        lamports,
    )
}
