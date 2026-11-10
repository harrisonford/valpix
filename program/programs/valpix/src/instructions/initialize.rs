use anchor_lang::{prelude::*, system_program};

use crate::{constants::*, error::ValpixError, state::*};

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Debug)]
pub struct InitParams {
    pub float_lamports: u64,
    pub revive_threshold_lamports: u64,
    pub pool_floor_lamports: u64,
    /// SOL the admin puts into the pool to start it.
    pub initial_sol_lamports: u64,
    /// CLPC created for the pool. With the SOL above, this sets the first fee.
    pub initial_clpc_reserve: u64,
}

/// Every rule the admin may change after setup.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Debug)]
pub struct ConfigParams {
    pub daily_rate: u64,
    pub fee_refund_lamports: u64,
    pub float_lamports: u64,
    pub join_surcharge: u64,
    pub revive_threshold_lamports: u64,
    pub revive_period_secs: i64,
    pub pool_floor_lamports: u64,
}

#[derive(Accounts)]
pub struct Initialize<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    #[account(init, payer = admin, space = 8 + Config::INIT_SPACE, seeds = [CONFIG_SEED], bump)]
    pub config: Account<'info, Config>,
    #[account(init, payer = admin, space = 8 + Pool::INIT_SPACE, seeds = [POOL_SEED], bump)]
    pub pool: Account<'info, Pool>,
    /// Holds the pool's SOL. Owned by the system program; only this program
    /// can sign for it.
    #[account(mut, seeds = [VAULT_SEED], bump)]
    pub vault: SystemAccount<'info>,
    pub system_program: Program<'info, System>,
}

pub fn handle_initialize(ctx: Context<Initialize>, params: InitParams) -> Result<()> {
    let config = &mut ctx.accounts.config;
    config.admin = ctx.accounts.admin.key();
    config.daily_rate = DEFAULT_DAILY_RATE;
    config.fee_refund_lamports = DEFAULT_FEE_REFUND_LAMPORTS;
    config.float_lamports = params.float_lamports;
    config.join_surcharge = 0;
    config.revive_threshold_lamports = params.revive_threshold_lamports;
    config.revive_period_secs = DEFAULT_REVIVE_PERIOD_SECS;
    config.pool_floor_lamports = params.pool_floor_lamports;
    config.bump = ctx.bumps.config;
    config.vault_bump = ctx.bumps.vault;

    let pool = &mut ctx.accounts.pool;
    pool.clpc_reserve = params.initial_clpc_reserve;
    pool.bump = ctx.bumps.pool;

    // The vault keeps its own rent minimum on top of the pool's SOL, so the
    // reserve can be spent down to zero without the address disappearing.
    let lamports = Rent::get()?
        .minimum_balance(0)
        .checked_add(params.initial_sol_lamports)
        .ok_or(ValpixError::Overflow)?;
    system_program::transfer(
        CpiContext::new(
            system_program::ID,
            system_program::Transfer {
                from: ctx.accounts.admin.to_account_info(),
                to: ctx.accounts.vault.to_account_info(),
            },
        ),
        lamports,
    )
}

#[derive(Accounts)]
pub struct UpdateConfig<'info> {
    pub admin: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump, has_one = admin @ ValpixError::NotAdmin)]
    pub config: Account<'info, Config>,
}

pub fn handle_update_config(ctx: Context<UpdateConfig>, params: ConfigParams) -> Result<()> {
    let config = &mut ctx.accounts.config;
    config.daily_rate = params.daily_rate;
    config.fee_refund_lamports = params.fee_refund_lamports;
    config.float_lamports = params.float_lamports;
    config.join_surcharge = params.join_surcharge;
    config.revive_threshold_lamports = params.revive_threshold_lamports;
    config.revive_period_secs = params.revive_period_secs;
    config.pool_floor_lamports = params.pool_floor_lamports;
    Ok(())
}
