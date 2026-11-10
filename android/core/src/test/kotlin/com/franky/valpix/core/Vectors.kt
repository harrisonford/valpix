// Converted by android/scripts/gen-vectors.py from the Swift core's Vectors.swift,
// which program/scripts/gen-vectors.ts writes. Do not edit by hand.
package com.franky.valpix.core

object Vectors {
    val programId = "5AVhdmuAbx8HWJdR6h7QAPojfJHt7Z6TAa9pwj12wqcx"
    val seedHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    val publicKey = "9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj"
    val publicKeyHex = "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"
    val recipient = "3eSdHG44thnPEP7QETrxKDLpMbecvTA2HVeXFJJiAd2Q"
    val newcomer = "8u8LCMQvMKrFxHbn326Ltcqv72HDPEC5FPMgPC3mXvxV"
    val signedMessage = "valpix test message"
    val signatureHex = "65d8a9a4cd4f0519634902a9c10e601b524b13a742aa9d8ce6e8cadded9d688dadb4efcfd4b2d1414530b7c9cee813da6d0d77346ca96a02e4c7c3bbe61da006"

    val base58Pairs: List<Pair<String, String>> = listOf(
        "" to "",
        "00" to "1",
        "0000" to "11",
        "61" to "2g",
        "000102030405" to "17bWpTW",
        "ffffffffffffffff" to "jpXCZedGfVQ",
        "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664" to "9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj",
        "0000000000000000000000000000000000000000000000000000000000000000" to "11111111111111111111111111111111",
    )
    val onCurve = listOf("9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj", "3eSdHG44thnPEP7QETrxKDLpMbecvTA2HVeXFJJiAd2Q", "8u8LCMQvMKrFxHbn326Ltcqv72HDPEC5FPMgPC3mXvxV")
    val offCurve = listOf("2YaReRAV33hBCp5ud4iNE1bnXg9hfXFBTS3vX842YCmq", "E4HL3ynd1KbrYE5wCFS7sda4JPhCrQbXxGJVQDNWgyYh", "BsgHvmgmHy52JJ4a7Hf2VeBDyPQzmXdcyfvza7Hd3R6V", "6wUn7Lk5UUd1qVXLf84hwrcVhaRAxuKpF7oRovcitdW7")

    val config = Pair("2YaReRAV33hBCp5ud4iNE1bnXg9hfXFBTS3vX842YCmq", 253)
    val pool = Pair("E4HL3ynd1KbrYE5wCFS7sda4JPhCrQbXxGJVQDNWgyYh", 254)
    val vault = Pair("BsgHvmgmHy52JJ4a7Hf2VeBDyPQzmXdcyfvza7Hd3R6V", 254)
    val member = Pair("6wUn7Lk5UUd1qVXLf84hwrcVhaRAxuKpF7oRovcitdW7", 255)
    val recipientMember = "3KCXNgJiD8ke4Z7A49ExzpCdxnfKN61rwH8b9fDyA9Zu"
    val newcomerMember = "3iso3aDkeohzKcMcKYyc3t763fqyGgiu3jdZpCNXYXxK"

    val blockhash = "JEJUoGfGEPTZ1XTwN39dYdFxYxDiDaSKVNy5qYWJmZt3"
    /// pay(300 CLPC, "Camila") from publicKey to recipient.
    val payDataHex = "7712d841c0757adc00a3e111000000000600000043616d696c61"
    val payMessageHex = "0100040979b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad0496642260dbb8ce7cca7a2ad77d39cbdf42a70cae62a1a46b5dc973a7be4f4d0fd47c583f34eb9f3572f20df047d19eb35124ac78ae4bee16bcee1b2bf3f3f2320c50a190564f6b80e50370ef8999433a4f57c28e85b655b811c8f3dea0735d4a242ec1ff2297fde84f96b121b4c995bfbb9da4a334a59641ea08a0a065ddfa016a96000000000000000000000000000000000000000000000000000000000000000016f27c558d3b7ffbf1f1683eb3f68e0c231c7db3e371099d560b49cbba9b773c3ddd6d45a75e4f4f1f46e0fdcdbcf03f1db3589c6be7c6af22557d60fb0bd8e506a7d517187bd16635dad40455fdc2c0c124c68f215675a5dbbacb5f08000000fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0efeeedecebeae9e8e7e6e5e4e3e2e1e001070800020106040305081a7712d841c0757adc00a3e111000000000600000043616d696c61"
    val joinDataHex = "ce37026a71dc11a3"
    val joinMessageHex = "0100040a79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad04966428719ead7c9dba86ae1aac63eae89267390042b7d47e3134e0a8697a0b7c21d8583f34eb9f3572f20df047d19eb35124ac78ae4bee16bcee1b2bf3f3f2320c50755c4cb9256ca7cdc4acfdc6cfeeda849017e5b9f9514e99191bd67e0b0d4276a190564f6b80e50370ef8999433a4f57c28e85b655b811c8f3dea0735d4a242ec1ff2297fde84f96b121b4c995bfbb9da4a334a59641ea08a0a065ddfa016a96000000000000000000000000000000000000000000000000000000000000000016f27c558d3b7ffbf1f1683eb3f68e0c231c7db3e371099d560b49cbba9b773c3ddd6d45a75e4f4f1f46e0fdcdbcf03f1db3589c6be7c6af22557d60fb0bd8e506a7d517187bd16635dad40455fdc2c0c124c68f215675a5dbbacb5f08000000fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0efeeedecebeae9e8e7e6e5e4e3e2e1e001080900020301070504060908ce37026a71dc11a3"
    val reviveSelfDataHex = "fd0e87e7bc75a66e"
    val reviveSelfMessageHex = "0100040879b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664583f34eb9f3572f20df047d19eb35124ac78ae4bee16bcee1b2bf3f3f2320c50a190564f6b80e50370ef8999433a4f57c28e85b655b811c8f3dea0735d4a242ec1ff2297fde84f96b121b4c995bfbb9da4a334a59641ea08a0a065ddfa016a96000000000000000000000000000000000000000000000000000000000000000016f27c558d3b7ffbf1f1683eb3f68e0c231c7db3e371099d560b49cbba9b773c3ddd6d45a75e4f4f1f46e0fdcdbcf03f1db3589c6be7c6af22557d60fb0bd8e506a7d517187bd16635dad40455fdc2c0c124c68f215675a5dbbacb5f08000000fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0efeeedecebeae9e8e7e6e5e4e3e2e1e00106070001050302040708fd0e87e7bc75a66e"

    val configData = "mwyq4B76zILgSttdfizOCh/xJaKacWVeB65bjzuJ1z9N4Ui8TiPyEgDKmjsAAAAAiBMAAAAAAAAgORUAAAAAAAAAAAAAAAAAoB4PAAAAAAAAjScAAAAAAEBCDwAAAAAAAC0xAQAAAAD9/g=="
    val configAdmin = "G6YXdapFrMSQWkHAgSKivdULpYHmUATvhfMvc6hwLtyX"
    val configDailyRate: ULong = 1000000000uL
    val configFeeRefund: ULong = 5000uL
    val configFloat: ULong = 1390880uL
    val configJoinSurcharge: ULong = 0uL
    val configReviveThreshold: ULong = 990880uL
    val configRevivePeriod: Long = 2592000L
    val configPoolFloor: ULong = 1000000uL
    val configMaxFee: ULong = 20000000uL
    val poolData = "8ZptBBGxbbzXgytW0QEAAP4="
    val poolClpcReserve: ULong = 1998605485015uL
    val memberData = "NhOiFR2mEcZZI+4nepF2amcEAaFeEoq4QneHXpKb4Ud7HbuhiatJ0QAAAAAAAAAAdB/zagAAAAB0H/NqAAAAAJa//t/bIeJb2Gak6hOl3UbkM/eM//suGXTdI0f4qWiUAAAAAAAAAAD/"
    val memberWallet = "6zy4bg3kTp7giwD2uJ82WRQgrV6JKzMuUQYR7RNHeB3E"
    val memberSettled: ULong = 0uL
    val memberLastSettled: Long = 1794318196L
    val memberCreated: Long = 1794318196L
    val memberInviter = "B9TxUqC9YFVMCtnc6y3KeY8inKtQDxX9Wp3XKNnpQPMD"
    val memberLastRevive: Long = 0L
    val paidLogLine = "Program data: 8MER7u7SgeuWv/7f2yHiW9hmpOoTpd1G5DP3jP/7Lhl03SNH+KlolDWabUKbXYeOu+yuJ+ghOG8Og+SN/8adD+wWGuum47KzAKPhEQAAAAB0Og8AAAAAAAYAAABDYW1pbGFzH/NqAAAAAA=="
    val paidPayer = "B9TxUqC9YFVMCtnc6y3KeY8inKtQDxX9Wp3XKNnpQPMD"
    val paidRecipient = "4cFBfPGNbmUUZt3vuT2qYBFRBKhVLJigyL4GWHNrYsEr"
    val paidAmount: ULong = 300000000uL
    val paidFee: ULong = 998004uL
    val paidName = "Camila"
    val paidTs: Long = 1794318195L
}
