import CryptoKit
import Foundation
import Testing
@testable import ValpixCore

@Suite struct Base58Tests {
    @Test func encodesKnownPairs() {
        for pair in Vectors.base58Pairs {
            #expect(Base58.encode(Data(hex: pair.hex)) == pair.base58)
        }
    }

    @Test func decodesKnownPairs() {
        for pair in Vectors.base58Pairs {
            #expect(Base58.decode(pair.base58) == Data(hex: pair.hex))
        }
    }

    @Test func rejectsCharactersOutsideTheAlphabet() {
        #expect(Base58.decode("0OIl") == nil)
        #expect(Base58.decode("abc def") == nil)
    }
}

@Suite struct PublicKeyTests {
    @Test func roundTripsBase58() throws {
        let key = try #require(PublicKey(base58: Vectors.publicKey))
        #expect(key.data == Data(hex: Vectors.publicKeyHex))
        #expect(key.base58 == Vectors.publicKey)
    }

    @Test func rejectsWrongLengths() {
        #expect(PublicKey(base58: "abc") == nil)
        #expect(PublicKey(data: Data(repeating: 1, count: 31)) == nil)
        #expect(PublicKey(base58: "not base58 !!") == nil)
    }

    @Test func tellsWalletKeysFromProgramAddresses() throws {
        for text in Vectors.onCurve {
            #expect(try #require(PublicKey(base58: text)).isOnCurve, "\(text) is a wallet key")
        }
        for text in Vectors.offCurve {
            #expect(!(try #require(PublicKey(base58: text)).isOnCurve), "\(text) is a program address")
        }
    }

    @Test func derivesTheSameAddressesAsSolana() throws {
        let program = try #require(PublicKey(base58: Vectors.programId))
        let wallet = try #require(PublicKey(base58: Vectors.publicKey))
        let cases: [([Data], String, UInt8)] = [
            ([Data("config".utf8)], Vectors.config.address, Vectors.config.bump),
            ([Data("pool".utf8)], Vectors.pool.address, Vectors.pool.bump),
            ([Data("vault".utf8)], Vectors.vault.address, Vectors.vault.bump),
            ([Data("member".utf8), wallet.data], Vectors.member.address, Vectors.member.bump),
        ]
        for (seeds, address, bump) in cases {
            let found = try #require(PublicKey.findProgramAddress(seeds: seeds, programId: program))
            #expect(found.0.base58 == address)
            #expect(found.1 == bump)
        }
    }
}

@Suite struct KeypairTests {
    @Test func derivesThePublicKeyFromTheSeed() throws {
        let keypair = try #require(Keypair(seed: Data(hex: Vectors.seedHex)))
        #expect(keypair.publicKey.base58 == Vectors.publicKey)
        #expect(keypair.seed == Data(hex: Vectors.seedHex))
    }

    @Test func rejectsSeedsOfTheWrongLength() {
        #expect(Keypair(seed: Data(repeating: 0, count: 31)) == nil)
    }

    @Test func producesSignaturesSolanaAccepts() throws {
        let keypair = try #require(Keypair(seed: Data(hex: Vectors.seedHex)))
        let message = Data(Vectors.signedMessage.utf8)
        let signature = keypair.sign(message)
        #expect(signature.count == 64)
        let verifier = try Curve25519.Signing.PublicKey(rawRepresentation: keypair.publicKey.data)
        #expect(verifier.isValidSignature(signature, for: message))
        // A signature made by Solana's library verifies against our key.
        #expect(verifier.isValidSignature(Data(hex: Vectors.signatureHex), for: message))
    }

    @Test func newKeypairsDiffer() {
        #expect(Keypair().publicKey != Keypair().publicKey)
    }
}

@Suite struct BorshTests {
    @Test func writesLittleEndianIntegersAndLengthPrefixedStrings() {
        var w = BorshWriter()
        w.u8(7)
        w.u32(1)
        w.u64(300_000_000)
        w.i64(-2)
        w.string("Ñu")
        #expect(w.data.hex == "07" + "01000000" + "00a3e11100000000" + "feffffffffffffff" + "03000000" + "c39175")
    }

    @Test func readsBackWhatItWrote() throws {
        var w = BorshWriter()
        let key = try #require(PublicKey(base58: Vectors.publicKey))
        w.u8(255); w.u64(.max); w.i64(.min); w.string("Camila"); w.publicKey(key)
        var r = BorshReader(w.data)
        #expect(try r.u8() == 255)
        #expect(try r.u64() == .max)
        #expect(try r.i64() == .min)
        #expect(try r.string() == "Camila")
        #expect(try r.publicKey() == key)
        #expect(r.isAtEnd)
    }

    @Test func throwsInsteadOfReadingPastTheEnd() {
        var r = BorshReader(Data([1, 2, 3]))
        #expect(throws: BorshError.self) { try r.u64() }
        var s = BorshReader(Data([255, 255, 255, 255, 65]))
        #expect(throws: BorshError.self) { try s.string() }
    }
}
