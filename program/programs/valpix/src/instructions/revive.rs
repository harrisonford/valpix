use anchor_lang::prelude::*;

use crate::{constants::*, error::ValpixError, events::Revived, guard, state::*, vault};

/// Restores a neighbour's drained wallet to the starting float. A wallet
/// with no SOL cannot send anything, so someone else has to do this for it.
#[derive(Accounts)]
pub struct Revive<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(mut, seeds = [MEMBER_SEED, payer.key().as_ref()], bump = payer_member.bump)]
    pub payer_member: Account<'info, Member>,
    /// The wallet to top up.
    #[account(mut)]
    pub target: SystemAccount<'info>,
    #[account(mut, seeds = [MEMBER_SEED, target.key().as_ref()], bump = target_member.bump)]
    pub target_member: Account<'info, Member>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [POOL_SEED], bump = pool.bump)]
    pub pool: Account<'info, Pool>,
    #[account(mut, seeds = [VAULT_SEED], bump = config.vault_bump)]
    pub vault: SystemAccount<'info>,
    pub system_program: Program<'info, System>,
    /// CHECK: the instructions sysvar, checked by address.
    #[account(address = solana_sdk_ids::sysvar::instructions::ID)]
    pub instructions: UncheckedAccount<'info>,
}

/// Restores the caller's own wallet, while it can still afford to ask.
#[derive(Accounts)]
pub struct ReviveSelf<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(mut, seeds = [MEMBER_SEED, payer.key().as_ref()], bump = payer_member.bump)]
    pub payer_member: Account<'info, Member>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [POOL_SEED], bump = pool.bump)]
    pub pool: Account<'info, Pool>,
    #[account(mut, seeds = [VAULT_SEED], bump = config.vault_bump)]
    pub vault: SystemAccount<'info>,
    pub system_program: Program<'info, System>,
    /// CHECK: the instructions sysvar, checked by address.
    #[account(address = solana_sdk_ids::sysvar::instructions::ID)]
    pub instructions: UncheckedAccount<'info>,
}

pub fn handle_revive(ctx: Context<Revive>) -> Result<()> {
    guard::require_alone(&ctx.accounts.instructions)?;
    let now = Clock::get()?.unix_timestamp;
    let config = &ctx.accounts.config;
    let target_wallet = ctx.accounts.target.to_account_info();
    let payer_wallet = ctx.accounts.payer.to_account_info();

    check_revivable(config, &target_wallet, ctx.accounts.target_member.last_revive_ts, now)?;
    let top_up = config.float_lamports.saturating_sub(target_wallet.lamports());
    // The payer's own fee is refunded as well, like any other pool-funded call.
    let refund = config.fee_refund_lamports;

    let cost = charge(
        config,
        &mut ctx.accounts.pool,
        &ctx.accounts.vault,
        &mut ctx.accounts.payer_member,
        top_up.checked_add(refund).ok_or(ValpixError::Overflow)?,
        now,
    )?;
    ctx.accounts.target_member.last_revive_ts = now;

    let vault_ai = ctx.accounts.vault.to_account_info();
    vault::pay_out(&vault_ai, &target_wallet, top_up, config.vault_bump)?;
    vault::pay_out(&vault_ai, &payer_wallet, refund, config.vault_bump)?;

    emit!(Revived { payer: payer_wallet.key(), target: target_wallet.key(), cost, ts: now });
    Ok(())
}

pub fn handle_revive_self(ctx: Context<ReviveSelf>) -> Result<()> {
    guard::require_alone(&ctx.accounts.instructions)?;
    let now = Clock::get()?.unix_timestamp;
    let config = &ctx.accounts.config;
    let wallet = ctx.accounts.payer.to_account_info();

    check_revivable(config, &wallet, ctx.accounts.payer_member.last_revive_ts, now)?;
    // The wallet has already paid this transaction's fee, so topping it back
    // up to the float covers the fee too. No separate refund.
    let top_up = config.float_lamports.saturating_sub(wallet.lamports());

    let cost = charge(
        config,
        &mut ctx.accounts.pool,
        &ctx.accounts.vault,
        &mut ctx.accounts.payer_member,
        top_up,
        now,
    )?;
    ctx.accounts.payer_member.last_revive_ts = now;

    vault::pay_out(
        &ctx.accounts.vault.to_account_info(),
        &wallet,
        top_up,
        config.vault_bump,
    )?;

    emit!(Revived { payer: wallet.key(), target: wallet.key(), cost, ts: now });
    Ok(())
}

/// A wallet may be revived only when it is running low, and not again until
/// the period has passed: each revival moves a little SOL into a wallet.
fn check_revivable(config: &Config, wallet: &AccountInfo, last_revive_ts: i64, now: i64) -> Result<()> {
    require!(
        wallet.lamports() < config.revive_threshold_lamports,
        ValpixError::ReviveNotNeeded
    );
    require!(
        last_revive_ts == 0 || now.saturating_sub(last_revive_ts) >= config.revive_period_secs,
        ValpixError::ReviveTooSoon
    );
    Ok(())
}

/// Takes the pool price for `sol_out` from the payer's CLPC. Returns the cost.
fn charge<'info>(
    config: &Config,
    pool: &mut Account<'info, Pool>,
    vault: &SystemAccount<'info>,
    payer_member: &mut Account<'info, Member>,
    sol_out: u64,
    now: i64,
) -> Result<u64> {
    payer_member.settle(config.daily_rate, now)?;
    let reserve = vault::sol_reserve(&vault.to_account_info())?;
    let cost = vault::quote(config, pool, reserve, sol_out).ok_or(ValpixError::PoolTooLow)?;
    require!(payer_member.settled >= cost, ValpixError::InsufficientFunds);
    payer_member.settled -= cost;
    pool.clpc_reserve = pool
        .clpc_reserve
        .checked_add(cost)
        .ok_or(ValpixError::Overflow)?;
    Ok(cost)
}
