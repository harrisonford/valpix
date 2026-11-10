mod common;

use {
    common::*,
    solana_keypair::Keypair,
    solana_signer::Signer,
    solana_system_interface::instruction::transfer,
    valpix::constants::UNIT,
};

/// Two members with a day of income each.
fn two_members() -> (Env, Keypair, Keypair) {
    let mut env = Env::new();
    let a = env.new_member();
    let b = env.new_member();
    env.advance(DAY);
    (env, a, b)
}

#[test]
fn funding_gives_clpc_and_lowers_the_fee() {
    let (mut env, a, _) = two_members();
    env.svm.airdrop(&a.pubkey(), SOL).unwrap();
    let fee_before = env.price_of(FEE);
    let reserve_before = env.pool().clpc_reserve;
    // 200,000 CLPC x 0.5 / 1.5, rounded down.
    let expected = 66_666_666_666;

    let ix = env.ix_fund_pool(&a.pubkey(), SOL / 2, expected);
    env.send(&[ix], &[&a]).unwrap();

    assert_eq!(env.balance(&a.pubkey()), 1000 * UNIT + expected);
    assert_eq!(env.pool().clpc_reserve, reserve_before - expected);
    assert_eq!(env.sol_reserve(), SOL + SOL / 2);
    assert!(env.price_of(FEE) < fee_before);
}

#[test]
fn funding_respects_min_out() {
    let (mut env, a, _) = two_members();
    env.svm.airdrop(&a.pubkey(), SOL).unwrap();
    let ix = env.ix_fund_pool(&a.pubkey(), SOL / 2, 66_666_666_667);
    expect_err(env.send(&[ix], &[&a]), "SlippageExceeded");
    assert_eq!(env.sol_reserve(), SOL);
}

#[test]
fn rejects_funding_zero() {
    let (mut env, a, _) = two_members();
    let ix = env.ix_fund_pool(&a.pubkey(), 0, 0);
    expect_err(env.send(&[ix], &[&a]), "ZeroAmount");
}

#[test]
fn non_member_cannot_fund_for_clpc() {
    let (mut env, _, _) = two_members();
    let stranger = Keypair::new();
    env.svm.airdrop(&stranger.pubkey(), 2 * SOL).unwrap();
    let ix = env.ix_fund_pool(&stranger.pubkey(), SOL, 0);
    assert!(env.send(&[ix], &[&stranger]).is_err());
}

#[test]
fn a_plain_transfer_to_the_vault_lowers_the_fee() {
    let (mut env, _, _) = two_members();
    let fee_before = env.price_of(FEE);
    let reserve_before = env.pool().clpc_reserve;

    let admin = env.admin.insecure_clone();
    let gift = transfer(&admin.pubkey(), &env.vault_pda(), SOL);
    env.send(&[gift], &[&admin]).unwrap();

    assert_eq!(env.sol_reserve(), 2 * SOL);
    assert_eq!(env.pool().clpc_reserve, reserve_before);
    assert!(env.price_of(FEE) < fee_before);
}

#[test]
fn revive_tops_up_a_drained_neighbour() {
    let (mut env, a, b) = two_members();
    env.set_lamports(&b.pubkey(), 900_000);
    let cost = env.price_of(FLOAT - 900_000 + FEE);

    let ix = env.ix_revive(&a.pubkey(), &b.pubkey());
    env.send(&[ix], &[&a]).unwrap();

    assert_eq!(env.lamports(&b.pubkey()), FLOAT);
    assert_eq!(env.lamports(&a.pubkey()), FLOAT);
    assert_eq!(env.balance(&a.pubkey()), 1000 * UNIT - cost);
    assert_eq!(env.balance(&b.pubkey()), 1000 * UNIT);
    assert_eq!(env.member(&b.pubkey()).last_revive_ts, env.now());
    assert_eq!(env.sol_reserve(), SOL - (FLOAT - 900_000 + FEE));
}

#[test]
fn revive_self_tops_up_own_wallet() {
    let (mut env, a, _) = two_members();
    env.set_lamports(&a.pubkey(), 950_000);
    // The wallet pays this transaction's fee before the program runs, and
    // the top-up back to the float covers it.
    let sol_out = FLOAT - (950_000 - FEE);
    let cost = env.price_of(sol_out);

    let ix = env.ix_revive_self(&a.pubkey());
    env.send(&[ix], &[&a]).unwrap();

    assert_eq!(env.lamports(&a.pubkey()), FLOAT);
    assert_eq!(env.balance(&a.pubkey()), 1000 * UNIT - cost);
    assert_eq!(env.member(&a.pubkey()).last_revive_ts, env.now());
    assert_eq!(env.sol_reserve(), SOL - sol_out);
}

#[test]
fn rejects_revive_above_threshold() {
    let (mut env, a, b) = two_members();
    let ix = env.ix_revive(&a.pubkey(), &b.pubkey());
    expect_err(env.send(&[ix], &[&a]), "ReviveNotNeeded");
    let ix = env.ix_revive_self(&a.pubkey());
    expect_err(env.send(&[ix], &[&a]), "ReviveNotNeeded");
}

#[test]
fn rejects_second_revive_within_period() {
    let (mut env, a, b) = two_members();
    env.set_lamports(&b.pubkey(), 900_000);
    let ix = env.ix_revive(&a.pubkey(), &b.pubkey());
    env.send(&[ix], &[&a]).unwrap();

    env.advance(29 * DAY);
    env.set_lamports(&b.pubkey(), 900_000);
    let ix = env.ix_revive(&a.pubkey(), &b.pubkey());
    expect_err(env.send(&[ix], &[&a]), "ReviveTooSoon");

    env.advance(DAY);
    let ix = env.ix_revive(&a.pubkey(), &b.pubkey());
    env.send(&[ix], &[&a]).unwrap();
    assert_eq!(env.lamports(&b.pubkey()), FLOAT);
}

#[test]
fn rejects_revive_when_pool_is_low() {
    let (mut env, a, b) = two_members();
    env.set_lamports(&b.pubkey(), 900_000);
    env.drain_pool_to_floor();
    let ix = env.ix_revive(&a.pubkey(), &b.pubkey());
    expect_err(env.send(&[ix], &[&a]), "PoolTooLow");
}

#[test]
fn rejects_revive_alongside_another_valpix_instruction() {
    let (mut env, a, b) = two_members();
    env.set_lamports(&b.pubkey(), 900_000);
    let revive = env.ix_revive(&a.pubkey(), &b.pubkey());
    let pay = env.ix_pay(&a.pubkey(), &b.pubkey(), UNIT, "");
    expect_err(env.send(&[revive, pay], &[&a]), "NotAlone");
}
