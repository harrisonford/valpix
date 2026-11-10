// Writes test vectors for the Swift core from Solana's own libraries and
// from whatever valpix state is on the local validator.
//   pnpm exec tsx gen-vectors.ts > ../../ios/ValpixCore/Tests/ValpixCoreTests/Vectors.swift
import { readFileSync } from "node:fs";
import anchorPkg from "@anchor-lang/core";
import { Connection, Keypair, PublicKey, SystemProgram, SYSVAR_INSTRUCTIONS_PUBKEY, Transaction } from "@solana/web3.js";
import { createPrivateKey, sign as edSign } from "node:crypto";
import bs58 from "bs58";

const { AnchorProvider, BN, Program, Wallet } = anchorPkg;
const idl = JSON.parse(readFileSync(new URL("../target/idl/valpix.json", import.meta.url), "utf8"));
const connection = new Connection("http://127.0.0.1:8899", "confirmed");
const seed = Uint8Array.from({ length: 32 }, (_, i) => i + 1);
const payer = Keypair.fromSeed(seed);
const program = new Program(idl, new AnchorProvider(connection, new Wallet(payer), {})) as any;
const pid: PublicKey = program.programId;
const pda = (...s: (string | PublicKey)[]) =>
  PublicKey.findProgramAddressSync(s.map((x) => (typeof x === "string" ? Buffer.from(x) : x.toBuffer())), pid);
const hex = (b: Uint8Array | Buffer) => Buffer.from(b).toString("hex");

const recipient = Keypair.fromSeed(Uint8Array.from({ length: 32 }, (_, i) => 200 - i)).publicKey;
const newcomer = Keypair.fromSeed(Uint8Array.from({ length: 32 }, (_, i) => 7 * i + 3)).publicKey;
const [config, configBump] = pda("config");
const [pool, poolBump] = pda("pool");
const [vault, vaultBump] = pda("vault");
const [member, memberBump] = pda("member", payer.publicKey);
const [recipientMember] = pda("member", recipient);
const [newcomerMember] = pda("member", newcomer);
const shared = { config, pool, vault, systemProgram: SystemProgram.programId, instructions: SYSVAR_INSTRUCTIONS_PUBKEY };
const blockhash = bs58.encode(Uint8Array.from({ length: 32 }, (_, i) => 255 - i));

const payIx = await program.methods.pay(new BN(300_000_000), "Camila")
  .accounts({ payer: payer.publicKey, payerMember: member, recipientMember, ...shared }).instruction();
const joinIx = await program.methods.join()
  .accounts({ inviter: payer.publicKey, inviterMember: member, newcomer, newcomerMember, ...shared }).instruction();
const reviveSelfIx = await program.methods.reviveSelf()
  .accounts({ payer: payer.publicKey, payerMember: member, ...shared }).instruction();
const message = (ix: any) => {
  const tx = new Transaction({ feePayer: payer.publicKey, recentBlockhash: blockhash }).add(ix);
  return hex(tx.compileMessage().serialize());
};
const signMsg = Buffer.from("valpix test message");
const edKey = createPrivateKey({
  key: Buffer.concat([Buffer.from("302e020100300506032b657004220420", "hex"), Buffer.from(seed)]),
  format: "der",
  type: "pkcs8",
});
const signature = edSign(null, signMsg, edKey);

// Live records from the local validator, if it is running with valpix set up.
let live = "";
try {
  const cfg = await connection.getAccountInfo(config);
  const pl = await connection.getAccountInfo(pool);
  const members = await program.account.member.all();
  const m = members[0];
  const mi = await connection.getAccountInfo(m.publicKey);
  const c = await program.account.config.fetch(config);
  const p = await program.account.pool.fetch(pool);
  let eventLine = "", ev: any = null;
  for (const mm of members) {
    const sigs = await connection.getSignaturesForAddress(mm.publicKey, { limit: 20 });
    for (const s of sigs) {
      const tx = await connection.getTransaction(s.signature, { maxSupportedTransactionVersion: 0, commitment: "confirmed" });
      const line = tx?.meta?.logMessages?.find((l) => l.startsWith("Program data: "));
      if (line && tx?.meta?.logMessages?.some((l) => l.includes("Instruction: Pay"))) {
        eventLine = line;
        ev = program.coder.events.decode(line.slice("Program data: ".length));
        break;
      }
    }
    if (eventLine) break;
  }
  live = `
    static let configData = "${cfg!.data.toString("base64")}"
    static let configAdmin = "${c.admin.toBase58()}"
    static let configDailyRate: UInt64 = ${c.dailyRate}
    static let configFeeRefund: UInt64 = ${c.feeRefundLamports}
    static let configFloat: UInt64 = ${c.floatLamports}
    static let configJoinSurcharge: UInt64 = ${c.joinSurcharge}
    static let configReviveThreshold: UInt64 = ${c.reviveThresholdLamports}
    static let configRevivePeriod: Int64 = ${c.revivePeriodSecs}
    static let configPoolFloor: UInt64 = ${c.poolFloorLamports}
    static let configMaxFee: UInt64 = ${c.maxFee}
    static let poolData = "${pl!.data.toString("base64")}"
    static let poolClpcReserve: UInt64 = ${p.clpcReserve}
    static let memberData = "${mi!.data.toString("base64")}"
    static let memberWallet = "${m.account.wallet.toBase58()}"
    static let memberSettled: UInt64 = ${m.account.settled}
    static let memberLastSettled: Int64 = ${m.account.lastSettledTs}
    static let memberCreated: Int64 = ${m.account.createdTs}
    static let memberInviter = "${m.account.inviter.toBase58()}"
    static let memberLastRevive: Int64 = ${m.account.lastReviveTs}
    static let paidLogLine = "${eventLine}"
    static let paidPayer = "${ev?.data.payer.toBase58() ?? ""}"
    static let paidRecipient = "${ev?.data.recipient.toBase58() ?? ""}"
    static let paidAmount: UInt64 = ${ev?.data.amount ?? 0}
    static let paidFee: UInt64 = ${ev?.data.fee ?? 0}
    static let paidName = "${ev?.data.name ?? ""}"
    static let paidTs: Int64 = ${ev?.data.ts ?? 0}`;
} catch (e) {
  live = `\n    // local validator not reachable when vectors were generated: ${String(e).slice(0, 80)}`;
}

console.log(`// Generated by program/scripts/gen-vectors.ts from @solana/web3.js, Anchor and the
// local validator. Do not edit by hand.
enum Vectors {
    static let programId = "${pid.toBase58()}"
    static let seedHex = "${hex(seed)}"
    static let publicKey = "${payer.publicKey.toBase58()}"
    static let publicKeyHex = "${hex(payer.publicKey.toBytes())}"
    static let recipient = "${recipient.toBase58()}"
    static let newcomer = "${newcomer.toBase58()}"
    static let signedMessage = "${signMsg.toString("utf8")}"
    static let signatureHex = "${hex(signature)}"

    static let base58Pairs: [(hex: String, base58: String)] = [
        ("", ""),
        ("00", "1"),
        ("0000", "11"),
        ("61", "${bs58.encode(Buffer.from("61", "hex"))}"),
        ("000102030405", "${bs58.encode(Buffer.from("000102030405", "hex"))}"),
        ("ffffffffffffffff", "${bs58.encode(Buffer.from("ffffffffffffffff", "hex"))}"),
        ("${hex(payer.publicKey.toBytes())}", "${payer.publicKey.toBase58()}"),
        ("${"00".repeat(32)}", "${bs58.encode(new Uint8Array(32))}"),
    ]
    static let onCurve = ["${payer.publicKey.toBase58()}", "${recipient.toBase58()}", "${newcomer.toBase58()}"]
    static let offCurve = ["${config.toBase58()}", "${pool.toBase58()}", "${vault.toBase58()}", "${member.toBase58()}"]

    static let config = (address: "${config.toBase58()}", bump: UInt8(${configBump}))
    static let pool = (address: "${pool.toBase58()}", bump: UInt8(${poolBump}))
    static let vault = (address: "${vault.toBase58()}", bump: UInt8(${vaultBump}))
    static let member = (address: "${member.toBase58()}", bump: UInt8(${memberBump}))
    static let recipientMember = "${recipientMember.toBase58()}"
    static let newcomerMember = "${newcomerMember.toBase58()}"

    static let blockhash = "${blockhash}"
    /// pay(300 CLPC, "Camila") from publicKey to recipient.
    static let payDataHex = "${hex(payIx.data)}"
    static let payMessageHex = "${message(payIx)}"
    static let joinDataHex = "${hex(joinIx.data)}"
    static let joinMessageHex = "${message(joinIx)}"
    static let reviveSelfDataHex = "${hex(reviveSelfIx.data)}"
    static let reviveSelfMessageHex = "${message(reviveSelfIx)}"
${live}
}`);
