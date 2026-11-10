use anchor_lang::prelude::*;

use crate::{constants::*, error::ValpixError, events::Paid, guard, state::*, vault};

#[derive(Accounts)]
pub struct Pay<'info> {
    /// Pays the Solana fee and gets it back from the pool.
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(mut, seeds = [MEMBER_SEED, payer.key().as_ref()], bump = payer_member.bump)]
    pub payer_member: Account<'info, Member>,
    #[account(
        mut,
        dup,
        constraint = recipient_member.key() != payer_member.key() @ ValpixError::SelfPayment
    )]
    pub recipient_member: Account<'info, Member>,
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

pub fn handle_pay(ctx: Context<Pay>, amount: u64, payer_name: String) -> Result<()> {
    guard::require_alone(&ctx.accounts.instructions)?;
    require!(amount > 0, ValpixError::ZeroAmount);
    require!(payer_name.len() <= MAX_NAME_BYTES, ValpixError::NameTooLong);

    let now = Clock::get()?.unix_timestamp;
    let config = &ctx.accounts.config;
    let payer_member = &mut ctx.accounts.payer_member;
    payer_member.settle(config.daily_rate, now)?;

    // The fee buys back this transaction's base fee from the pool. When the
    // pool cannot afford it, or asks more than the cap, the payment goes
    // through without fee or refund.
    let refund = config.fee_refund_lamports;
    let reserve = vault::sol_reserve(&ctx.accounts.vault)?;
    let fee = vault::fee_quote(config, &ctx.accounts.pool, reserve);

    let total = amount
        .checked_add(fee.unwrap_or(0))
        .ok_or(ValpixError::Overflow)?;
    require!(payer_member.settled >= total, ValpixError::InsufficientFunds);
    payer_member.settled -= total;

    let recipient = &mut ctx.accounts.recipient_member;
    recipient.settled = recipient
        .settled
        .checked_add(amount)
        .ok_or(ValpixError::Overflow)?;

    if let Some(fee) = fee {
        let pool = &mut ctx.accounts.pool;
        pool.clpc_reserve = pool
            .clpc_reserve
            .checked_add(fee)
            .ok_or(ValpixError::Overflow)?;
        vault::pay_out(
            &ctx.accounts.vault,
            &ctx.accounts.payer,
            refund,
            config.vault_bump,
        )?;
    }

    emit!(Paid {
        payer: ctx.accounts.payer.key(),
        recipient: recipient.wallet,
        amount,
        fee: fee.unwrap_or(0),
        name: payer_name,
        ts: now,
    });
    Ok(())
}
