use anchor_lang::{prelude::*, system_program};

use crate::{constants::*, error::ValpixError, events::Joined, guard, state::*, vault};

/// An existing member brings a newcomer in. The pool supplies every lamport
/// the new account needs; the inviter pays the pool's price in CLPC.
#[derive(Accounts)]
pub struct Join<'info> {
    #[account(mut)]
    pub inviter: Signer<'info>,
    #[account(mut, seeds = [MEMBER_SEED, inviter.key().as_ref()], bump = inviter_member.bump)]
    pub inviter_member: Account<'info, Member>,
    /// The newcomer's wallet. It does not sign: the key came from their QR.
    #[account(mut)]
    pub newcomer: SystemAccount<'info>,
    /// CHECK: the newcomer's record, created here. The seeds fix its address.
    #[account(mut, seeds = [MEMBER_SEED, newcomer.key().as_ref()], bump)]
    pub newcomer_member: UncheckedAccount<'info>,
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

pub fn handle_join(ctx: Context<Join>) -> Result<()> {
    guard::require_alone(&ctx.accounts.instructions)?;

    let record = ctx.accounts.newcomer_member.to_account_info();
    require!(
        record.data_is_empty() && record.owner == &system_program::ID,
        ValpixError::AlreadyMember
    );

    let now = Clock::get()?.unix_timestamp;
    let config = &ctx.accounts.config;
    let inviter_member = &mut ctx.accounts.inviter_member;
    inviter_member.settle(config.daily_rate, now)?;

    // Everything the pool has to hand over. Each part only tops up, so SOL
    // already sitting at either address is not paid for twice.
    let space = 8 + Member::INIT_SPACE;
    let record_rent = Rent::get()?
        .minimum_balance(space)
        .saturating_sub(record.lamports());
    let float = config
        .float_lamports
        .saturating_sub(ctx.accounts.newcomer.lamports());
    let refund = config.fee_refund_lamports;
    let sol_out = record_rent
        .checked_add(float)
        .and_then(|v| v.checked_add(refund))
        .ok_or(ValpixError::Overflow)?;

    let reserve = vault::sol_reserve(&ctx.accounts.vault)?;
    let price =
        vault::quote(config, &ctx.accounts.pool, reserve, sol_out).ok_or(ValpixError::PoolTooLow)?;
    let cost = price
        .checked_add(config.join_surcharge)
        .ok_or(ValpixError::Overflow)?;
    require!(inviter_member.settled >= cost, ValpixError::InsufficientFunds);
    inviter_member.settled -= cost;

    let pool = &mut ctx.accounts.pool;
    pool.clpc_reserve = pool
        .clpc_reserve
        .checked_add(cost)
        .ok_or(ValpixError::Overflow)?;

    // Create the record: fund it from the vault, then claim the address.
    let vault_ai = ctx.accounts.vault.to_account_info();
    vault::pay_out(&vault_ai, &record, record_rent, config.vault_bump)?;
    let newcomer_key = ctx.accounts.newcomer.key();
    let record_seeds: &[&[u8]] = &[
        MEMBER_SEED,
        newcomer_key.as_ref(),
        &[ctx.bumps.newcomer_member],
    ];
    system_program::allocate(
        CpiContext::new_with_signer(
            system_program::ID,
            system_program::Allocate { account_to_allocate: record.clone() },
            &[record_seeds],
        ),
        space as u64,
    )?;
    system_program::assign(
        CpiContext::new_with_signer(
            system_program::ID,
            system_program::Assign { account_to_assign: record.clone() },
            &[record_seeds],
        ),
        &crate::ID,
    )?;
    let member = Member {
        wallet: newcomer_key,
        settled: 0,
        last_settled_ts: now,
        created_ts: now,
        inviter: ctx.accounts.inviter.key(),
        last_revive_ts: 0,
        bump: ctx.bumps.newcomer_member,
    };
    member.try_serialize(&mut &mut record.try_borrow_mut_data()?[..])?;

    vault::pay_out(
        &vault_ai,
        &ctx.accounts.newcomer.to_account_info(),
        float,
        config.vault_bump,
    )?;
    vault::pay_out(
        &vault_ai,
        &ctx.accounts.inviter.to_account_info(),
        refund,
        config.vault_bump,
    )?;

    emit!(Joined {
        inviter: ctx.accounts.inviter.key(),
        newcomer: newcomer_key,
        cost,
        ts: now,
    });
    Ok(())
}
