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
