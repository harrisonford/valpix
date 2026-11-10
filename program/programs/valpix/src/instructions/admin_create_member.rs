use anchor_lang::{prelude::*, system_program};

use crate::{constants::*, error::ValpixError, state::*};

/// How the first members come to exist: the admin pays for them out of the
/// admin's own SOL, without touching the pool.
#[derive(Accounts)]
pub struct AdminCreateMember<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump, has_one = admin @ ValpixError::NotAdmin)]
    pub config: Account<'info, Config>,
    /// The new member's wallet.
    #[account(mut)]
    pub wallet: SystemAccount<'info>,
    #[account(
        init,
        payer = admin,
        space = 8 + Member::INIT_SPACE,
        seeds = [MEMBER_SEED, wallet.key().as_ref()],
        bump
    )]
    pub member: Account<'info, Member>,
    pub system_program: Program<'info, System>,
}

pub fn handle_admin_create_member(ctx: Context<AdminCreateMember>) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    let member = &mut ctx.accounts.member;
    member.wallet = ctx.accounts.wallet.key();
    member.settled = 0;
    member.last_settled_ts = now;
    member.created_ts = now;
    member.inviter = ctx.accounts.admin.key();
    member.last_revive_ts = 0;
    member.bump = ctx.bumps.member;

    let top_up = ctx
        .accounts
        .config
        .float_lamports
        .saturating_sub(ctx.accounts.wallet.lamports());
    if top_up > 0 {
        system_program::transfer(
            CpiContext::new(
                system_program::ID,
                system_program::Transfer {
                    from: ctx.accounts.admin.to_account_info(),
                    to: ctx.accounts.wallet.to_account_info(),
                },
            ),
            top_up,
        )?;
    }
    Ok(())
}
