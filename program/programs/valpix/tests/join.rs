mod common;

use {
    anchor_lang::Space,
    common::*,
    solana_keypair::Keypair,
    solana_signer::Signer,
    valpix::{constants::UNIT, state::Member},
};

/// One member with a day of income, and the rent a new member record costs.
fn inviter() -> (Env, Keypair, u64) {
    let mut env = Env::new();
    let a = env.new_member();
    env.advance(DAY);
    let rent = env.rent_min(8 + Member::INIT_SPACE);
    (env, a, rent)
}

#[test]
fn member_brings_in_a_newcomer() {
    let (mut env, a, rent) = inviter();
    let newcomer = Keypair::new().pubkey();
    let sol_out = rent + FLOAT + FEE;
    let cost = env.price_of(sol_out);
    let reserve_before = env.pool().clpc_reserve;

    let ix = env.ix_join(&a.pubkey(), &newcomer);
    env.send(&[ix], &[&a]).unwrap();

    let member = env.member(&newcomer);
    assert_eq!(member.wallet, newcomer);
    assert_eq!(member.settled, 0);
    assert_eq!(member.created_ts, env.now());
    assert_eq!(member.last_settled_ts, env.now());
    assert_eq!(member.inviter, a.pubkey());
    assert_eq!(env.lamports(&newcomer), FLOAT);
    // The inviter paid in CLPC only; the pool covered every lamport.
    assert_eq!(env.lamports(&a.pubkey()), FLOAT);
    assert_eq!(env.balance(&a.pubkey()), 1000 * UNIT - cost);
    assert_eq!(env.pool().clpc_reserve, reserve_before + cost);
    assert_eq!(env.sol_reserve(), SOL - sol_out);
}

#[test]
fn newcomer_starts_earning_at_once() {
    let (mut env, a, _) = inviter();
    let newcomer = Keypair::new().pubkey();
    let ix = env.ix_join(&a.pubkey(), &newcomer);
    env.send(&[ix], &[&a]).unwrap();
    env.advance(DAY);
    assert_eq!(env.balance(&newcomer), 1000 * UNIT);
}

#[test]
fn join_charges_the_surcharge_when_set() {
    let (mut env, a, rent) = inviter();
    let mut params = env.config_params();
    params.join_surcharge = 100 * UNIT;
    let ix = env.ix_update_config(&env.admin.pubkey(), params);
    let admin = env.admin.insecure_clone();
    env.send(&[ix], &[&admin]).unwrap();

    let newcomer = Keypair::new().pubkey();
    let cost = env.price_of(rent + FLOAT + FEE) + 100 * UNIT;
    let reserve_before = env.pool().clpc_reserve;
    let ix = env.ix_join(&a.pubkey(), &newcomer);
    env.send(&[ix], &[&a]).unwrap();

    assert_eq!(env.balance(&a.pubkey()), 1000 * UNIT - cost);
    assert_eq!(env.pool().clpc_reserve, reserve_before + cost);
}

#[test]
fn rejects_join_of_existing_member() {
    let (mut env, a, _) = inviter();
    let b = env.new_member();
    let ix = env.ix_join(&a.pubkey(), &b.pubkey());
    expect_err(env.send(&[ix], &[&a]), "AlreadyMember");
}

#[test]
fn join_only_tops_up_a_funded_wallet() {
    let (mut env, a, rent) = inviter();
    let newcomer = Keypair::new().pubkey();
    env.svm.airdrop(&newcomer, 1_000_000).unwrap();

    let ix = env.ix_join(&a.pubkey(), &newcomer);
    env.send(&[ix], &[&a]).unwrap();

    assert_eq!(env.lamports(&newcomer), FLOAT);
    assert_eq!(env.sol_reserve(), SOL - (rent + 500_000 + FEE));
}

#[test]
fn join_works_when_the_record_address_already_holds_sol() {
    let (mut env, a, rent) = inviter();
    let newcomer = Keypair::new().pubkey();
    // Anyone can send SOL to the address the record will live at. A plain
    // "create account" would then fail, which would lock the newcomer out.
    let record = env.member_pda(&newcomer);
    let already_there = env.rent_min(0);
    env.set_lamports(&record, already_there);

    let ix = env.ix_join(&a.pubkey(), &newcomer);
    env.send(&[ix], &[&a]).unwrap();

    assert_eq!(env.member(&newcomer).wallet, newcomer);
    assert_eq!(env.lamports(&record), rent);
    // The pool only made up the difference.
    assert_eq!(env.sol_reserve(), SOL - (rent - already_there + FLOAT + FEE));
}

#[test]
fn rejects_join_when_inviter_cannot_afford_it() {
    let mut env = Env::new();
    let a = env.new_member(); // no income yet
    let ix = env.ix_join(&a.pubkey(), &Keypair::new().pubkey());
    expect_err(env.send(&[ix], &[&a]), "InsufficientFunds");
}

#[test]
fn rejects_join_when_pool_cannot_cover_it() {
    let (mut env, a, _) = inviter();
    env.drain_pool_to_floor();
    let newcomer = Keypair::new().pubkey();
    let ix = env.ix_join(&a.pubkey(), &newcomer);
    expect_err(env.send(&[ix], &[&a]), "PoolTooLow");
    assert!(!env.is_member(&newcomer));
}

#[test]
fn non_member_cannot_invite() {
    let (mut env, _, _) = inviter();
    let stranger = Keypair::new();
    env.svm.airdrop(&stranger.pubkey(), SOL).unwrap();
    let ix = env.ix_join(&stranger.pubkey(), &Keypair::new().pubkey());
    assert!(env.send(&[ix], &[&stranger]).is_err());
}

#[test]
fn rejects_join_alongside_another_valpix_instruction() {
    let (mut env, a, _) = inviter();
    let b = env.new_member();
    let join = env.ix_join(&a.pubkey(), &Keypair::new().pubkey());
    let pay = env.ix_pay(&a.pubkey(), &b.pubkey(), UNIT, "");
    expect_err(env.send(&[join, pay], &[&a]), "NotAlone");
}
