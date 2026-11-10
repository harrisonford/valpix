pub mod constants;
pub mod error;
pub mod events;
pub mod guard;
pub mod instructions;
pub mod math;
pub mod state;
pub mod vault;

use anchor_lang::prelude::*;

pub use constants::*;
pub use events::*;
pub use instructions::*;
pub use state::*;

declare_id!("5AVhdmuAbx8HWJdR6h7QAPojfJHt7Z6TAa9pwj12wqcx");

#[program]
pub mod valpix {
    use super::*;

    pub fn initialize(ctx: Context<Initialize>, params: InitParams) -> Result<()> {
        crate::instructions::initialize::handle_initialize(ctx, params)
    }

    pub fn update_config(ctx: Context<UpdateConfig>, params: ConfigParams) -> Result<()> {
        crate::instructions::initialize::handle_update_config(ctx, params)
    }

    pub fn admin_create_member(ctx: Context<AdminCreateMember>) -> Result<()> {
        crate::instructions::admin_create_member::handle_admin_create_member(ctx)
    }

    /// Creates an account for a newcomer, paid for by an existing member.
    pub fn join(ctx: Context<Join>) -> Result<()> {
        crate::instructions::join::handle_join(ctx)
    }

    /// Moves `amount` micro-CLPC to another member. The pool returns the
    /// Solana base fee to the payer and charges its price in CLPC.
    pub fn pay(ctx: Context<Pay>, amount: u64, payer_name: String) -> Result<()> {
        crate::instructions::pay::handle_pay(ctx, amount, payer_name)
    }
}
