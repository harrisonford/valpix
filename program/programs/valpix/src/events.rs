use anchor_lang::prelude::*;

#[event]
pub struct Paid {
    pub payer: Pubkey,
    pub recipient: Pubkey,
    /// Micro-CLPC that reached the recipient.
    pub amount: u64,
    /// Micro-CLPC the payer gave the pool. Zero when the pool could not refund.
    pub fee: u64,
    /// The name the payer chose to show. Unverified.
    pub name: String,
    pub ts: i64,
}

#[event]
pub struct Joined {
    pub inviter: Pubkey,
    pub newcomer: Pubkey,
    /// Micro-CLPC the inviter gave the pool.
    pub cost: u64,
    pub ts: i64,
}

#[event]
pub struct Funded {
    pub funder: Pubkey,
    pub lamports: u64,
    /// Micro-CLPC the funder received from the pool.
    pub clpc_out: u64,
    pub ts: i64,
}

#[event]
pub struct Revived {
    pub payer: Pubkey,
    pub target: Pubkey,
    /// Micro-CLPC the payer gave the pool.
    pub cost: u64,
    pub ts: i64,
}
