//! Keeps a fee refund from being collected more than once per transaction.
//!
//! A transaction pays one base fee however many instructions it carries. If
//! it could hold several refunding valpix instructions, or reach one through
//! another program in a loop, each would draw a refund and the surplus SOL
//! would leave the pool. So a refunding instruction must be the only valpix
//! instruction in its transaction and must be called directly.

use anchor_lang::{
    prelude::*,
    solana_program::instruction::{get_stack_height, TRANSACTION_LEVEL_STACK_HEIGHT},
};
use solana_instructions_sysvar::{load_current_index_checked, load_instruction_at_checked};

use crate::error::ValpixError;

/// Passes only when the running instruction is top-level and every other
/// instruction in the transaction belongs to the compute-budget program.
pub fn require_alone(instructions_sysvar: &AccountInfo) -> Result<()> {
    require!(
        get_stack_height() == TRANSACTION_LEVEL_STACK_HEIGHT,
        ValpixError::CalledFromProgram
    );

    let current = load_current_index_checked(instructions_sysvar)? as usize;
    let count = {
        let data = instructions_sysvar.try_borrow_data()?;
        require!(data.len() >= 2, ValpixError::NotAlone);
        u16::from_le_bytes([data[0], data[1]]) as usize
    };
    for index in 0..count {
        if index == current {
            continue;
        }
        let other = load_instruction_at_checked(index, instructions_sysvar)?;
        require_keys_eq!(
            other.program_id,
            solana_sdk_ids::compute_budget::ID,
            ValpixError::NotAlone
        );
    }
    Ok(())
}
