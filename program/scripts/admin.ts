// valpix admin script: set the program up and poke at it from a terminal.
//
//   pnpm admin init --sol 10 --clpc 2000000
//   pnpm admin member <wallet>
//   pnpm admin show [wallet]
//   pnpm admin pay --to <wallet> --amount 300 --name Camila --keypair payer.json
//   pnpm admin join <wallet> --keypair inviter.json
//   pnpm admin fund --sol 0.5 --keypair member.json     (SOL in, CLPC out)
//   pnpm admin gift --sol 1                             (SOL in, nothing out)
//
// Every command takes --url (default http://127.0.0.1:8899) and --keypair
// (default ~/.config/solana/id.json).

import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { parseArgs } from "node:util";
import anchorPkg from "@anchor-lang/core";
import {
  Connection,
  Keypair,
  LAMPORTS_PER_SOL,
  PublicKey,
  SystemProgram,
  SYSVAR_INSTRUCTIONS_PUBKEY,
  Transaction,
  sendAndConfirmTransaction,
} from "@solana/web3.js";

const { AnchorProvider, BN, Program, Wallet } = anchorPkg;

const UNIT = 1_000_000n; // micro-CLPC per CLPC
const BASE_FEE = 5_000; // lamports per signature

const { values: opt, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    url: { type: "string", default: "http://127.0.0.1:8899" },
    keypair: { type: "string", default: `${homedir()}/.config/solana/id.json` },
    sol: { type: "string" },
    clpc: { type: "string" },
    to: { type: "string" },
    amount: { type: "string" },
    name: { type: "string", default: "" },
    "float-fees": { type: "string", default: "100" },
    "revive-fees": { type: "string", default: "20" },
    "floor-sol": { type: "string", default: "0.001" },
  },
});
const [command, arg] = positionals;

function loadKeypair(path: string): Keypair {
  return Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(path, "utf8"))));
}

const idl = JSON.parse(readFileSync(new URL("../target/idl/valpix.json", import.meta.url), "utf8"));
const signer = loadKeypair(opt.keypair!);
const connection = new Connection(opt.url!, "confirmed");
const provider = new AnchorProvider(connection, new Wallet(signer), { commitment: "confirmed" });
const program = new Program(idl, provider) as any;
const programId: PublicKey = program.programId;

const pda = (...seeds: (string | PublicKey)[]) =>
  PublicKey.findProgramAddressSync(
    seeds.map((s) => (typeof s === "string" ? Buffer.from(s) : s.toBuffer())),
    programId,
  )[0];
const config = pda("config");
const pool = pda("pool");
const vault = pda("vault");
const memberOf = (wallet: PublicKey) => pda("member", wallet);
const shared = { config, pool, vault, systemProgram: SystemProgram.programId };
const refunding = { ...shared, instructions: SYSVAR_INSTRUCTIONS_PUBKEY };

const toMicro = (clpc: string) => {
  const [whole, frac = ""] = clpc.split(".");
  return BigInt(whole) * UNIT + BigInt((frac + "000000").slice(0, 6));
};
const fmtClpc = (micro: bigint) =>
  `${micro / UNIT}.${(micro % UNIT).toString().padStart(6, "0")} CLPC`;
const fmtSol = (lamports: bigint | number) => `${Number(lamports) / LAMPORTS_PER_SOL} SOL`;
const requireOpt = (name: "sol" | "clpc" | "to" | "amount"): string => {
  const value = opt[name];
  if (!value) throw new Error(`--${name} is required`);
  return value;
};
const lamportsArg = (sol: string | undefined) => {
  if (!sol) throw new Error("--sol is required");
  return Math.round(Number(sol) * LAMPORTS_PER_SOL);
};

/// ceil(clpcReserve * lamportsOut / (solReserve - lamportsOut)), as the program does.
function quoteIn(solReserve: bigint, clpcReserve: bigint, lamportsOut: bigint): bigint | null {
  if (lamportsOut >= solReserve) return null;
  const d = solReserve - lamportsOut;
  return (clpcReserve * lamportsOut + d - 1n) / d;
}

async function chainNow(): Promise<number> {
  const slot = await connection.getSlot("confirmed");
  return (await connection.getBlockTime(slot)) ?? Math.floor(Date.now() / 1000);
}

async function show(wallet?: string) {
  const cfg = await program.account.config.fetch(config);
  const p = await program.account.pool.fetch(pool);
  const rentMin = BigInt(await connection.getMinimumBalanceForRentExemption(0));
  const solReserve = BigInt(await connection.getBalance(vault)) - rentMin;
  const clpcReserve = BigInt(p.clpcReserve.toString());
  // Mirrors the program: no fee below the floor or above the cap.
  const refund = BigInt(cfg.feeRefundLamports.toString());
  const price = quoteIn(solReserve, clpcReserve, refund);
  const maxFee = BigInt(cfg.maxFee.toString());
  const aboveFloor = solReserve >= refund && solReserve - refund >= BigInt(cfg.poolFloorLamports.toString());
  const fee = price !== null && aboveFloor && price <= maxFee ? price : null;

  console.log(`program   ${programId.toBase58()}`);
  console.log(`cluster   ${opt.url}`);
  console.log(`admin     ${cfg.admin.toBase58()}`);
  console.log(`vault     ${vault.toBase58()}`);
  console.log(`pool      ${fmtSol(solReserve)}  x  ${fmtClpc(clpcReserve)}`);
  console.log(`fee now   ${fee === null ? "none: the pool is too low or too dear, payments go through unrefunded" : fmtClpc(fee)}`);
  console.log(`fee cap   ${fmtClpc(maxFee)}`);
  console.log(`income    ${fmtClpc(BigInt(cfg.dailyRate.toString()))} per day`);
  console.log(`float     ${cfg.floatLamports} lamports (revive below ${cfg.reviveThresholdLamports})`);
  console.log(`surcharge ${fmtClpc(BigInt(cfg.joinSurcharge.toString()))} per join`);

  if (wallet) {
    const key = new PublicKey(wallet);
    const m = await program.account.member.fetchNullable(memberOf(key));
    if (!m) return console.log(`\n${wallet} is not a member`);
    const now = await chainNow();
    const elapsed = BigInt(Math.max(0, now - m.lastSettledTs.toNumber()));
    const live = BigInt(m.settled.toString()) + (elapsed * BigInt(cfg.dailyRate.toString())) / 86_400n;
    console.log(`\nmember    ${wallet}`);
    console.log(`balance   ${fmtClpc(live)}`);
    console.log(`wallet    ${await connection.getBalance(key)} lamports`);
    console.log(`invited by ${m.inviter.toBase58()}`);
  }
}

async function main() {
  switch (command) {
    case "init": {
      const rentMin = await connection.getMinimumBalanceForRentExemption(0);
      const params = {
        floatLamports: new BN(rentMin + Number(opt["float-fees"]) * BASE_FEE),
        reviveThresholdLamports: new BN(rentMin + Number(opt["revive-fees"]) * BASE_FEE),
        poolFloorLamports: new BN(Math.round(Number(opt["floor-sol"]) * LAMPORTS_PER_SOL)),
        initialSolLamports: new BN(lamportsArg(opt.sol)),
        initialClpcReserve: new BN(toMicro(requireOpt("clpc")).toString()),
      };
      const sig = await program.methods
        .initialize(params)
        .accounts({ admin: signer.publicKey, ...shared })
        .rpc();
      console.log(`initialised: ${sig}`);
      return show();
    }
    case "member": {
      const wallet = new PublicKey(arg);
      const sig = await program.methods
        .adminCreateMember()
        .accounts({
          admin: signer.publicKey,
          config,
          wallet,
          member: memberOf(wallet),
          systemProgram: SystemProgram.programId,
        })
        .rpc();
      console.log(`member created: ${sig}`);
      return show(arg);
    }
    case "pay": {
      const to = new PublicKey(opt.to!);
      const sig = await program.methods
        .pay(new BN(toMicro(opt.amount!).toString()), opt.name)
        .accounts({
          payer: signer.publicKey,
          payerMember: memberOf(signer.publicKey),
          recipientMember: memberOf(to),
          ...refunding,
        })
        .rpc();
      console.log(`paid: ${sig}`);
      return show(signer.publicKey.toBase58());
    }
    case "join": {
      const newcomer = new PublicKey(arg);
      const sig = await program.methods
        .join()
        .accounts({
          inviter: signer.publicKey,
          inviterMember: memberOf(signer.publicKey),
          newcomer,
          newcomerMember: memberOf(newcomer),
          ...refunding,
        })
        .rpc();
      console.log(`joined: ${sig}`);
      return show(arg);
    }
    case "fund": {
      const sig = await program.methods
        .fundPool(new BN(lamportsArg(opt.sol)), new BN(0))
        .accounts({ funder: signer.publicKey, funderMember: memberOf(signer.publicKey), ...shared })
        .rpc();
      console.log(`funded: ${sig}`);
      return show(signer.publicKey.toBase58());
    }
    case "gift": {
      const tx = new Transaction().add(
        SystemProgram.transfer({ fromPubkey: signer.publicKey, toPubkey: vault, lamports: lamportsArg(opt.sol) }),
      );
      console.log(`gift sent: ${await sendAndConfirmTransaction(connection, tx, [signer])}`);
      return show();
    }
    case "show":
      return show(arg);
    default:
      console.log("commands: init | member <wallet> | show [wallet] | pay | join <wallet> | fund | gift");
      process.exitCode = 1;
  }
}

main().catch((e) => {
  console.error(e?.logs?.join("\n") ?? e);
  process.exitCode = 1;
});
