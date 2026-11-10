mod common;

use {
    common::*,
    solana_keypair::Keypair,
    solana_signer::Signer,
    valpix::constants::UNIT,
};

#[test]
fn initialize_sets_defaults() {
    let env = Env::new();
    let config = env.config();
    assert_eq!(config.admin, env.admin.pubkey());
    assert_eq!(config.daily_rate, 1000 * UNIT);
    assert_eq!(config.fee_refund_lamports, 5_000);
    assert_eq!(config.join_surcharge, 0);
    assert_eq!(config.max_fee, 20 * UNIT);
    assert_eq!(config.revive_period_secs, 30 * DAY);
    assert_eq!(config.float_lamports, FLOAT);
    assert_eq!(config.revive_threshold_lamports, REVIVE_THRESHOLD);
    assert_eq!(config.pool_floor_lamports, POOL_FLOOR);
    assert_eq!(env.pool().clpc_reserve, 200_000 * UNIT);
    assert_eq!(env.sol_reserve(), SOL);
}

#[test]
fn initialize_rejects_an_empty_pool() {
    // With either reserve at zero the price is zero or undefined, and no
    // instruction can repair a pool once it exists.
    for (sol, clpc) in [(0, 200_000 * UNIT), (SOL, 0)] {
        let mut env = Env::bare();
        let mut params = default_init();
        params.initial_sol_lamports = sol;
        params.initial_clpc_reserve = clpc;
        let ix = env.ix_initialize(params);
        let admin = env.admin.insecure_clone();
        expect_err(env.send(&[ix], &[&admin]), "EmptyPool");
    }
}

#[test]
fn initialize_twice_fails() {
    let mut env = Env::new();
    let ix = env.ix_initialize(default_init());
    let admin = env.admin.insecure_clone();
    assert!(env.send(&[ix], &[&admin]).is_err());
}

#[test]
fn admin_creates_a_member_with_a_float() {
    let mut env = Env::new();
    let wallet = env.new_member();
    let member = env.member(&wallet.pubkey());
    assert_eq!(member.wallet, wallet.pubkey());
    assert_eq!(member.settled, 0);
    assert_eq!(member.last_settled_ts, START_TS);
    assert_eq!(member.created_ts, START_TS);
    assert_eq!(member.inviter, env.admin.pubkey());
    assert_eq!(env.lamports(&wallet.pubkey()), FLOAT);
}

#[test]
fn admin_only_tops_up_a_wallet_that_already_holds_sol() {
    let mut env = Env::new();
    let wallet = Keypair::new();
    env.svm.airdrop(&wallet.pubkey(), 1_000_000).unwrap();
    let ix = env.ix_admin_create_member(&wallet.pubkey());
    let admin = env.admin.insecure_clone();
    env.send(&[ix], &[&admin]).unwrap();
    assert_eq!(env.lamports(&wallet.pubkey()), FLOAT);
}

#[test]
fn only_admin_can_create_members() {
    let mut env = Env::new();
    let stranger = Keypair::new();
    env.svm.airdrop(&stranger.pubkey(), SOL).unwrap();
    let ix = env.ix_admin_create_member_as(&stranger.pubkey(), &Keypair::new().pubkey());
    expect_err(env.send(&[ix], &[&stranger]), "NotAdmin");
}

#[test]
fn only_admin_can_update_config() {
    let mut env = Env::new();
    let stranger = Keypair::new();
    env.svm.airdrop(&stranger.pubkey(), SOL).unwrap();
    let mut params = env.config_params();
    params.join_surcharge = 7 * UNIT;
    let ix = env.ix_update_config(&stranger.pubkey(), params.clone());
    expect_err(env.send(&[ix], &[&stranger]), "NotAdmin");

    let ix = env.ix_update_config(&env.admin.pubkey(), params);
    let admin = env.admin.insecure_clone();
    env.send(&[ix], &[&admin]).unwrap();
    assert_eq!(env.config().join_surcharge, 7 * UNIT);
}

#[test]
fn income_accrues_at_1000_a_day() {
    let mut env = Env::new();
    let wallet = env.new_member();
    assert_eq!(env.balance(&wallet.pubkey()), 0);
    env.advance(DAY);
    assert_eq!(env.balance(&wallet.pubkey()), 1000 * UNIT);
}
