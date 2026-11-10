//! In-process test harness: a LiteSVM with the valpix program loaded and a
//! pool already set up, plus builders for every instruction.
#![allow(dead_code)]

use {
    anchor_lang::{
        prelude::{Clock, Pubkey},
        solana_program::{instruction::Instruction, system_program},
        AccountDeserialize, InstructionData, ToAccountMetas,
    },
    litesvm::{types::TransactionResult, LiteSVM},
    solana_keypair::Keypair,
    solana_message::{Message, VersionedMessage},
    solana_signer::Signer,
    solana_transaction::versioned::VersionedTransaction,
    valpix::{
        constants::*,
        math,
        state::{Config, Member, Pool},
        ConfigParams, InitParams,
    },
};

pub const START_TS: i64 = 1_800_000_000;
pub const DAY: i64 = 86_400;
pub const SOL: u64 = 1_000_000_000;
pub const FLOAT: u64 = 1_500_000;
pub const REVIVE_THRESHOLD: u64 = 1_000_000;
pub const POOL_FLOOR: u64 = 10_000;
pub const FEE: u64 = 5_000;

pub fn default_init() -> InitParams {
    InitParams {
        float_lamports: FLOAT,
        revive_threshold_lamports: REVIVE_THRESHOLD,
        pool_floor_lamports: POOL_FLOOR,
        initial_sol_lamports: SOL,
        initial_clpc_reserve: 200_000 * UNIT,
    }
}

pub struct Env {
    pub svm: LiteSVM,
    pub admin: Keypair,
    pub program_id: Pubkey,
}

impl Env {
    /// Program loaded, admin funded, clock set, nothing initialised.
    pub fn bare() -> Self {
        let program_id = valpix::id();
        let mut svm = LiteSVM::new();
        let bytes = include_bytes!(concat!(env!("CARGO_TARGET_TMPDIR"), "/../deploy/valpix.so"));
        svm.add_program(program_id, bytes).unwrap();
        let admin = Keypair::new();
        svm.airdrop(&admin.pubkey(), 100 * SOL).unwrap();
        let mut clock: Clock = svm.get_sysvar();
        clock.unix_timestamp = START_TS;
        svm.set_sysvar(&clock);
        Env { svm, admin, program_id }
    }

    /// Initialised with a 1 SOL / 200,000 CLPC pool.
    pub fn new() -> Self {
        let mut env = Self::bare();
        let ix = env.ix_initialize(default_init());
        let admin = env.admin.insecure_clone();
        env.send(&[ix], &[&admin]).expect("initialize");
        env
    }

    // ---- addresses ----

    pub fn config_pda(&self) -> Pubkey {
        Pubkey::find_program_address(&[CONFIG_SEED], &self.program_id).0
    }
    pub fn pool_pda(&self) -> Pubkey {
        Pubkey::find_program_address(&[POOL_SEED], &self.program_id).0
    }
    pub fn vault_pda(&self) -> Pubkey {
        Pubkey::find_program_address(&[VAULT_SEED], &self.program_id).0
    }
    pub fn member_pda(&self, wallet: &Pubkey) -> Pubkey {
        Pubkey::find_program_address(&[MEMBER_SEED, wallet.as_ref()], &self.program_id).0
    }

    // ---- sending ----

    /// Sends one transaction. The first signer pays the fee.
    pub fn send(&mut self, ixs: &[Instruction], signers: &[&Keypair]) -> TransactionResult {
        let blockhash = self.svm.latest_blockhash();
        let msg = Message::new_with_blockhash(ixs, Some(&signers[0].pubkey()), &blockhash);
        let tx = VersionedTransaction::try_new(VersionedMessage::Legacy(msg), signers).unwrap();
        let res = self.svm.send_transaction(tx);
        self.svm.expire_blockhash();
        res
    }

    // ---- time ----

    pub fn now(&self) -> i64 {
        self.svm.get_sysvar::<Clock>().unix_timestamp
    }
    pub fn advance(&mut self, secs: i64) {
        let mut clock: Clock = self.svm.get_sysvar();
        clock.unix_timestamp += secs;
        self.svm.set_sysvar(&clock);
    }

    // ---- reading state ----

    fn read<T: AccountDeserialize>(&self, address: &Pubkey) -> T {
        let account = self.svm.get_account(address).expect("account exists");
        T::try_deserialize(&mut account.data.as_slice()).expect("account decodes")
    }
    pub fn config(&self) -> Config {
        self.read(&self.config_pda())
    }
    pub fn pool(&self) -> Pool {
        self.read(&self.pool_pda())
    }
    pub fn member(&self, wallet: &Pubkey) -> Member {
        self.read(&self.member_pda(wallet))
    }
    pub fn is_member(&self, wallet: &Pubkey) -> bool {
        self.svm
            .get_account(&self.member_pda(wallet))
            .map(|a| !a.data.is_empty())
            .unwrap_or(false)
    }
    pub fn lamports(&self, address: &Pubkey) -> u64 {
        self.svm.get_balance(address).unwrap_or(0)
    }
    pub fn rent_min(&self, len: usize) -> u64 {
        self.svm.minimum_balance_for_rent_exemption(len)
    }
    /// The pool's SOL: what the vault holds above its own rent minimum.
    pub fn sol_reserve(&self) -> u64 {
        self.lamports(&self.vault_pda()) - self.rent_min(0)
    }
    /// A member's spendable CLPC right now: settled plus income since.
    pub fn balance(&self, wallet: &Pubkey) -> u64 {
        let m = self.member(wallet);
        m.settled + math::accrued(self.config().daily_rate, m.last_settled_ts, self.now())
    }
    /// What the pool charges, in micro-CLPC, for `lamports` of SOL right now.
    pub fn price_of(&self, lamports: u64) -> u64 {
        math::quote_clpc_in(self.sol_reserve(), self.pool().clpc_reserve, lamports).unwrap()
    }

    /// Leaves the pool with exactly its floor, so it can pay nothing out.
    pub fn drain_pool_to_floor(&mut self) {
        let vault = self.vault_pda();
        let mut account = self.svm.get_account(&vault).unwrap();
        account.lamports = self.rent_min(0) + POOL_FLOOR;
        self.svm.set_account(vault, account).unwrap();
    }
    /// Sets how much SOL a wallet holds.
    pub fn set_lamports(&mut self, address: &Pubkey, lamports: u64) {
        let mut account = self.svm.get_account(address).unwrap_or_default();
        account.lamports = lamports;
        self.svm.set_account(*address, account).unwrap();
    }

    // ---- people ----

    /// A fresh wallet made a member by the admin.
    pub fn new_member(&mut self) -> Keypair {
        let wallet = Keypair::new();
        let ix = self.ix_admin_create_member(&wallet.pubkey());
        let admin = self.admin.insecure_clone();
        self.send(&[ix], &[&admin]).expect("admin_create_member");
        wallet
    }

    // ---- instruction builders ----

    fn ix(&self, data: Vec<u8>, accounts: impl ToAccountMetas) -> Instruction {
        Instruction::new_with_bytes(self.program_id, &data, accounts.to_account_metas(None))
    }

    pub fn ix_initialize(&self, params: InitParams) -> Instruction {
        self.ix_initialize_as(&self.admin.pubkey(), params)
    }
    pub fn ix_initialize_as(&self, admin: &Pubkey, params: InitParams) -> Instruction {
        self.ix(
            valpix::instruction::Initialize { params }.data(),
            valpix::accounts::Initialize {
                admin: *admin,
                config: self.config_pda(),
                pool: self.pool_pda(),
                vault: self.vault_pda(),
                system_program: system_program::ID,
            },
        )
    }
    pub fn ix_update_config(&self, admin: &Pubkey, params: ConfigParams) -> Instruction {
        self.ix(
            valpix::instruction::UpdateConfig { params }.data(),
            valpix::accounts::UpdateConfig { admin: *admin, config: self.config_pda() },
        )
    }
    pub fn ix_admin_create_member(&self, wallet: &Pubkey) -> Instruction {
        self.ix_admin_create_member_as(&self.admin.pubkey(), wallet)
    }
    pub fn ix_admin_create_member_as(&self, admin: &Pubkey, wallet: &Pubkey) -> Instruction {
        self.ix(
            valpix::instruction::AdminCreateMember {}.data(),
            valpix::accounts::AdminCreateMember {
                admin: *admin,
                config: self.config_pda(),
                wallet: *wallet,
                member: self.member_pda(wallet),
                system_program: system_program::ID,
            },
        )
    }
    pub fn ix_pay(&self, payer: &Pubkey, recipient: &Pubkey, amount: u64, name: &str) -> Instruction {
        self.ix(
            valpix::instruction::Pay { amount, payer_name: name.to_string() }.data(),
            valpix::accounts::Pay {
                payer: *payer,
                payer_member: self.member_pda(payer),
                recipient_member: self.member_pda(recipient),
                config: self.config_pda(),
                pool: self.pool_pda(),
                vault: self.vault_pda(),
                system_program: system_program::ID,
                instructions: instructions_sysvar(),
            },
        )
    }
    pub fn ix_join(&self, inviter: &Pubkey, newcomer: &Pubkey) -> Instruction {
        self.ix(
            valpix::instruction::Join {}.data(),
            valpix::accounts::Join {
                inviter: *inviter,
                inviter_member: self.member_pda(inviter),
                newcomer: *newcomer,
                newcomer_member: self.member_pda(newcomer),
                config: self.config_pda(),
                pool: self.pool_pda(),
                vault: self.vault_pda(),
                system_program: system_program::ID,
                instructions: instructions_sysvar(),
            },
        )
    }
    pub fn ix_fund_pool(&self, funder: &Pubkey, lamports: u64, min_clpc_out: u64) -> Instruction {
        self.ix(
            valpix::instruction::FundPool { lamports, min_clpc_out }.data(),
            valpix::accounts::FundPool {
                funder: *funder,
                funder_member: self.member_pda(funder),
                config: self.config_pda(),
                pool: self.pool_pda(),
                vault: self.vault_pda(),
                system_program: system_program::ID,
            },
        )
    }
    pub fn ix_revive(&self, payer: &Pubkey, target: &Pubkey) -> Instruction {
        self.ix(
            valpix::instruction::Revive {}.data(),
            valpix::accounts::Revive {
                payer: *payer,
                payer_member: self.member_pda(payer),
                target: *target,
                target_member: self.member_pda(target),
                config: self.config_pda(),
                pool: self.pool_pda(),
                vault: self.vault_pda(),
                system_program: system_program::ID,
                instructions: instructions_sysvar(),
            },
        )
    }
    pub fn ix_revive_self(&self, payer: &Pubkey) -> Instruction {
        self.ix(
            valpix::instruction::ReviveSelf {}.data(),
            valpix::accounts::ReviveSelf {
                payer: *payer,
                payer_member: self.member_pda(payer),
                config: self.config_pda(),
                pool: self.pool_pda(),
                vault: self.vault_pda(),
                system_program: system_program::ID,
                instructions: instructions_sysvar(),
            },
        )
    }
    /// The current config as update parameters, ready to tweak one field.
    pub fn config_params(&self) -> ConfigParams {
        let c = self.config();
        ConfigParams {
            daily_rate: c.daily_rate,
            fee_refund_lamports: c.fee_refund_lamports,
            float_lamports: c.float_lamports,
            join_surcharge: c.join_surcharge,
            revive_threshold_lamports: c.revive_threshold_lamports,
            revive_period_secs: c.revive_period_secs,
            pool_floor_lamports: c.pool_floor_lamports,
            max_fee: c.max_fee,
        }
    }
}

/// Asserts the transaction failed with the named valpix error.
pub fn expect_err(res: TransactionResult, name: &str) {
    match res {
        Ok(_) => panic!("expected {name}, but the transaction succeeded"),
        Err(e) => {
            let logs = e.meta.logs.join("\n");
            assert!(
                logs.contains(&format!("Error Code: {name}.")),
                "expected {name}, got {:?}\n{logs}",
                e.err
            );
        }
    }
}

pub fn instructions_sysvar() -> Pubkey {
    solana_sdk_ids::sysvar::instructions::ID
}
