import SwiftUI
import ValpixCore

/// Getting paid: type an amount, show the QR, wait for the chain.
struct ChargeView: View {
    @Environment(AppModel.self) private var model
    @State private var amount: UInt64 = 0
    @State private var showingCode = false
    @State private var startedAt: TimeInterval = 0

    var body: some View {
        if showingCode {
            VStack(spacing: 30) {
                // Tap the code to copy it, to be paid by someone who is not here to scan it.
                QRImage(text: ValpixLink.pay(to: model.publicKey, amountPesos: amount > 0 ? amount : nil, name: model.sharedName).string, copyable: true)
                    .frame(width: 264, height: 264)
                    .accessibilityLabel("Código para cobrar")
                if amount > 0 {
                    Amount(whole: PesoFormat.whole(amount), size: 44, spoken: "\(amount) pesos")
                }
            }
            .task {
                // Only a payment the chain has confirmed ends this screen.
                if let paid = await model.nextIncomingPayment(requestedPesos: amount > 0 ? amount : nil, after: startedAt) {
                    model.remember(name: paid.name, for: paid.counterparty)
                    model.flow = .done(
                        amountPesos: Money.wholePesos(micro: paid.amountMicro),
                        name: model.displayName(for: paid.counterparty, fallback: paid.name),
                        incoming: true
                    )
                    await model.refresh()
                }
            }
        } else {
            VStack(spacing: 0) {
                Spacer()
                Amount(whole: PesoFormat.whole(amount), size: 60, color: amount > 0 ? Theme.foreground : Theme.dim, spoken: "\(amount) pesos")
                Spacer()
                Keypad(value: $amount)
                // With no amount typed, this shows an open code a stall can print.
                GlassButton(icon: "qrcode", label: "Mostrar código") {
                    startedAt = model.chainNow
                    showingCode = true
                }
                .padding(.top, 26)
                .padding(.bottom, 30)
            }
            .task {
                // Debug builds only: `VALPIX_CHARGE=<pesos>` goes straight to the code.
                if AppConfig.flag("VALPIX_AUTOCONFIRM"),
                   let preset = ProcessInfo.processInfo.environment["VALPIX_CHARGE"].flatMap(UInt64.init) {
                    amount = preset
                    startedAt = model.chainNow
                    showingCode = true
                }
            }
        }
    }
}
