mod common;

use {
    anchor_lang::{prelude::Pubkey, solana_program::instruction::Instruction, AnchorDeserialize},
    base64::Engine,
    common::*,
    solana_keypair::Keypair,
    solana_signer::Signer,
    valpix::constants::UNIT,
};

/// Two members who have each earned exactly one day of income.
fn two_members() -> (Env, Keypair, Keypair) {
    let mut env = Env::new();
    let a = env.new_member();
    let b = env.new_member();
    env.advance(DAY);
    (env, a, b)
}

fn compute_budget_limit(units: u32) -> Instruction {
    let mut data = vec![2u8];
    data.extend_from_slice(&units.to_le_bytes());
    Instruction::new_with_bytes(solana_sdk_ids::compute_budget::ID, &data, vec![])
}

#[test]
fn pay_moves_amount_and_charges_the_pool_fee() {
    let (mut env, a, b) = two_members();
    let fee = env.price_of(FEE);
    assert_eq!(fee, 1_000_006);

    let ix = env.ix_pay(&a.pubkey(), &b.pubkey(), 300 * UNIT, "Camila");
    env.send(&[ix], &[&a]).unwrap();

    assert_eq!(env.balance(&a.pubkey()), 1000 * UNIT - 300 * UNIT - fee);
    assert_eq!(env.balance(&b.pubkey()), 1000 * UNIT + 300 * UNIT);
    assert_eq!(env.pool().clpc_reserve, 200_000 * UNIT + fee);
    assert_eq!(env.sol_reserve(), SOL - FEE);
    // The pool gave back exactly what the transaction cost.
    assert_eq!(env.lamports(&a.pubkey()), FLOAT);
}

#[test]
fn recipient_income_is_untouched_by_incoming_payment() {
    let (mut env, a, b) = two_members();
    let before = env.member(&b.pubkey()).last_settled_ts;
    let ix = env.ix_pay(&a.pubkey(), &b.pubkey(), UNIT, "");
    env.send(&[ix], &[&a]).unwrap();
    assert_eq!(env.member(&b.pubkey()).last_settled_ts, before);
    assert_eq!(env.member(&a.pubkey()).last_settled_ts, env.now());
}

#[test]
fn pay_without_refund_when_pool_is_low() {
    let (mut env, a, b) = two_members();
    env.drain_pool_to_floor();
    let reserve_before = env.pool().clpc_reserve;

    let ix = env.ix_pay(&a.pubkey(), &b.pubkey(), 300 * UNIT, "Camila");
    env.send(&[ix], &[&a]).unwrap();

    assert_eq!(env.balance(&a.pubkey()), 700 * UNIT);
    assert_eq!(env.balance(&b.pubkey()), 1300 * UNIT);
    assert_eq!(env.pool().clpc_reserve, reserve_before);
    assert_eq!(env.sol_reserve(), POOL_FLOOR);
    assert_eq!(env.lamports(&a.pubkey()), FLOAT - FEE);
}

#[test]
fn can_spend_the_whole_balance_when_no_fee_is_charged() {
    let (mut env, a, b) = two_members();
    env.drain_pool_to_floor();
    let ix = env.ix_pay(&a.pubkey(), &b.pubkey(), 1000 * UNIT, "");
    env.send(&[ix], &[&a]).unwrap();
    assert_eq!(env.balance(&a.pubkey()), 0);
}

#[test]
fn rejects_overspend() {
    let (mut env, a, b) = two_members();
    // The whole balance leaves nothing for the fee.
    let ix = env.ix_pay(&a.pubkey(), &b.pubkey(), 1000 * UNIT, "");
    expect_err(env.send(&[ix], &[&a]), "InsufficientFunds");
}

#[test]
fn rejects_payment_in_the_second_of_joining() {
    let mut env = Env::new();
    let a = env.new_member();
    let b = env.new_member();
    let ix = env.ix_pay(&a.pubkey(), &b.pubkey(), 1, "");
    expect_err(env.send(&[ix], &[&a]), "InsufficientFunds");
}

#[test]
fn rejects_self_payment() {
    let (mut env, a, _) = two_members();
    let ix = env.ix_pay(&a.pubkey(), &a.pubkey(), UNIT, "");
    expect_err(env.send(&[ix], &[&a]), "SelfPayment");
}

#[test]
fn rejects_zero_amount() {
    let (mut env, a, b) = two_members();
    let ix = env.ix_pay(&a.pubkey(), &b.pubkey(), 0, "");
    expect_err(env.send(&[ix], &[&a]), "ZeroAmount");
}

#[test]
fn rejects_payment_to_non_member() {
    let (mut env, a, _) = two_members();
    let stranger = Pubkey::new_unique();
    let ix = env.ix_pay(&a.pubkey(), &stranger, UNIT, "");
    assert!(env.send(&[ix], &[&a]).is_err());
    assert_eq!(env.balance(&a.pubkey()), 1000 * UNIT);
}

#[test]
fn non_member_cannot_pay() {
    let (mut env, _, b) = two_members();
    let stranger = Keypair::new();
    env.svm.airdrop(&stranger.pubkey(), SOL).unwrap();
    let ix = env.ix_pay(&stranger.pubkey(), &b.pubkey(), UNIT, "");
    assert!(env.send(&[ix], &[&stranger]).is_err());
}

#[test]
fn rejects_long_name() {
    let (mut env, a, b) = two_members();
    let name = "x".repeat(33);
    let ix = env.ix_pay(&a.pubkey(), &b.pubkey(), UNIT, &name);
    expect_err(env.send(&[ix], &[&a]), "NameTooLong");
}

#[test]
fn accepts_multibyte_name() {
    let (mut env, a, b) = two_members();
    let name = "Ñandú café ☕";
    assert!(name.len() <= 32 && name.len() > name.chars().count());
    let ix = env.ix_pay(&a.pubkey(), &b.pubkey(), UNIT, name);
    env.send(&[ix], &[&a]).unwrap();
}

#[test]
fn rejects_two_pays_in_one_transaction() {
    let (mut env, a, b) = two_members();
    let ix1 = env.ix_pay(&a.pubkey(), &b.pubkey(), UNIT, "one");
    let ix2 = env.ix_pay(&a.pubkey(), &b.pubkey(), 2 * UNIT, "two");
    expect_err(env.send(&[ix1, ix2], &[&a]), "NotAlone");
    // Nothing moved, and the pool paid nothing.
    assert_eq!(env.balance(&b.pubkey()), 1000 * UNIT);
    assert_eq!(env.sol_reserve(), SOL);
}

#[test]
fn allows_compute_budget_instructions_alongside() {
    let (mut env, a, b) = two_members();
    let pay = env.ix_pay(&a.pubkey(), &b.pubkey(), UNIT, "");
    env.send(&[compute_budget_limit(200_000), pay], &[&a]).unwrap();
    assert_eq!(env.balance(&b.pubkey()), 1001 * UNIT);
}

#[test]
fn emits_paid_event_with_the_payer_name() {
    let (mut env, a, b) = two_members();
    let fee = env.price_of(FEE);
    let ix = env.ix_pay(&a.pubkey(), &b.pubkey(), 300 * UNIT, "Camila");
    let meta = env.send(&[ix], &[&a]).unwrap();

    let line = meta
        .logs
        .iter()
        .find_map(|l| l.strip_prefix("Program data: "))
        .expect("an event was logged");
    let bytes = base64::engine::general_purpose::STANDARD.decode(line).unwrap();
    let event = valpix::Paid::deserialize(&mut &bytes[8..]).unwrap();
    assert_eq!(event.payer, a.pubkey());
    assert_eq!(event.recipient, b.pubkey());
    assert_eq!(event.amount, 300 * UNIT);
    assert_eq!(event.fee, fee);
    assert_eq!(event.name, "Camila");
    assert_eq!(event.ts, env.now());
}
