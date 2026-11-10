use anchor_lang::prelude::*;

#[error_code]
pub enum ValpixError {
    #[msg("Only the admin can do this")]
    NotAdmin,
    #[msg("This wallet already has an account")]
    AlreadyMember,
    #[msg("This wallet has no account")]
    NotMember,
    #[msg("You cannot pay yourself")]
    SelfPayment,
    #[msg("The amount must be more than zero")]
    ZeroAmount,
    #[msg("The name is longer than 32 bytes")]
    NameTooLong,
    #[msg("Not enough CLPC for the amount and its fee")]
    InsufficientFunds,
    #[msg("The pool would pay out less CLPC than the minimum asked for")]
    SlippageExceeded,
    #[msg("A transaction may hold only one valpix instruction")]
    NotAlone,
    #[msg("This instruction cannot be called from another program")]
    CalledFromProgram,
    #[msg("This wallet still holds enough SOL")]
    ReviveNotNeeded,
    #[msg("This wallet was revived too recently")]
    ReviveTooSoon,
    #[msg("The pool does not hold enough SOL for this")]
    PoolTooLow,
    #[msg("The pool must start with both SOL and CLPC")]
    EmptyPool,
    #[msg("Arithmetic overflow")]
    Overflow,
}
