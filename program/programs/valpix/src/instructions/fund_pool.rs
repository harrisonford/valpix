use anchor_lang::{prelude::*, system_program};

use crate::{constants::*, error::ValpixError, events::Funded, math, state::*, vault};

/// A member adds SOL to the pool and takes CLPC out at the current price.
/// This is how the pool is refilled. (A gift needs no instruction: SOL sent
/// straight to the vault address lowers the fee for everyone.)
#[derive(Accounts)]
pub struct FundPool<'info> {
    #[account(mut)]
    pub funder: Signer<'info>,
    #[account(mut, seeds = [MEMBER_SEED, funder.key().as_ref()], bump = funder_member.bump)]
    pub funder_member: Account<'info, Member>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [POOL_SEED], bump = pool.bump)]
    pub pool: Account<'info, Pool>,
    #[account(mut, seeds = [VAULT_SEED], bump = config.vault_bump)]
    pub vault: SystemAccount<'info>,
    pub system_program: Program<'info, System>,
}

pub fn handle_fund_pool(ctx: Context<FundPool>, lamports: u64, min_clpc_out: u64) -> Result<()> {
    require!(lamports > 0, ValpixError::ZeroAmount);

    let reserve = vault::sol_reserve(&ctx.accounts.vault)?;
    let pool = &mut ctx.accounts.pool;
    let clpc_out = math::quote_clpc_out(reserve, pool.clpc_reserve, lamports);
    require!(clpc_out >= min_clpc_out, ValpixError::SlippageExceeded);

    system_program::transfer(
        CpiContext::new(
            system_program::ID,
            system_program::Transfer {
                from: ctx.accounts.funder.to_account_info(),
                to: ctx.accounts.vault.to_account_info(),
            },
        ),
        lamports,
    )?;

    // `clpc_out` never exceeds the reserve, by construction of the quote.
    pool.clpc_reserve -= clpc_out;
    let member = &mut ctx.accounts.funder_member;
    member.settled = member
        .settled
        .checked_add(clpc_out)
        .ok_or(ValpixError::Overflow)?;

    emit!(Funded {
        funder: ctx.accounts.funder.key(),
        lamports,
        clpc_out,
        ts: Clock::get()?.unix_timestamp,
    });
    Ok(())
}
