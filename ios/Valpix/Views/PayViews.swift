import SwiftUI
import ValpixCore

/// After scanning: the amount, who it is for, and one button.
struct ConfirmView: View {
    @Environment(AppModel.self) private var model
    let recipient: PublicKey
    /// `nil` for an open code: the payer types the amount.
    let fixedAmount: UInt64?
    let name: String
    @State private var typed: UInt64 = 0
    @State private var working = false

    private var amount: UInt64 { fixedAmount ?? typed }

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Text(PesoFormat.whole(amount))
                .font(Theme.number(60))
                .foregroundStyle(model.canPay(amount) ? Theme.foreground : Theme.dim)
                .accessibilityLabel("\(amount) pesos")
            Text(model.displayName(for: recipient, fallback: name))
                .font(Theme.caption)
                .foregroundStyle(Theme.soft)
                .padding(.top, 12)
            Spacer()
            if fixedAmount == nil {
                Keypad(value: $typed).padding(.bottom, 26)
            }
            GlassButton(icon: "faceid", label: "Aprobar pago") { approve() }
                .opacity(model.canPay(amount) && !working ? 1 : 0.3)
                .disabled(!model.canPay(amount) || working)
                .padding(.bottom, 36)
        }
        .task {
            // Debug builds only: lets a script on the Mac drive a payment.
            if AppConfig.flag("VALPIX_AUTOCONFIRM"), fixedAmount != nil { approve() }
        }
    }

    private func approve() {
        guard !working else { return }
        working = true
        Task {
            await model.pay(to: recipient, amountPesos: amount, name: name)
            working = false
        }
    }
}

/// After scanning a newcomer's code: who they are, what it costs, one button.
struct InviteView: View {
    @Environment(AppModel.self) private var model
    let newcomer: PublicKey
    let name: String
    @State private var working = false

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Image(systemName: "person.badge.plus")
                .font(.system(size: 54, weight: .thin))
                .foregroundStyle(Theme.foreground)
            Text(model.displayName(for: newcomer, fallback: name))
                .font(Theme.caption)
                .foregroundStyle(Theme.soft)
                .padding(.top, 18)
            if let cost = model.inviteCostPesos {
                Text(PesoFormat.signed(cost, incoming: false))
                    .font(Theme.number(30))
                    .foregroundStyle(Theme.dim)
                    .padding(.top, 14)
            }
            Spacer()
            GlassButton(icon: "faceid", label: "Aprobar invitación") {
                guard !working else { return }
                working = true
                Task {
                    await model.invite(newcomer, name: name)
                    working = false
                }
            }
            .opacity(working ? 0.3 : 1)
            .disabled(working)
            .padding(.bottom, 36)
        }
    }
}

/// Gray while it travels.
struct SendingView: View {
    let amountPesos: UInt64
    let name: String
    @State private var pulsing = false

    var body: some View {
        VStack(spacing: 26) {
            Circle()
                .strokeBorder(Theme.foreground.opacity(0.7), lineWidth: 1)
                .frame(width: 84, height: 84)
                .scaleEffect(pulsing ? 1.5 : 0.7)
                .opacity(pulsing ? 0 : 1)
                .animation(.easeOut(duration: 1.5).repeatForever(autoreverses: false), value: pulsing)
                .background(Circle().strokeBorder(Theme.dim, lineWidth: 1).frame(width: 84, height: 84))
            if amountPesos > 0 {
                Text(PesoFormat.whole(amountPesos)).font(Theme.number(44)).foregroundStyle(Theme.dim)
            }
        }
        .onAppear { pulsing = true }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Enviando")
    }
}

/// White once the chain has confirmed.
struct DoneView: View {
    @Environment(AppModel.self) private var model
    let amountPesos: UInt64?
    let name: String
    let incoming: Bool

    var body: some View {
        VStack(spacing: 22) {
            Image(systemName: "checkmark")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(Theme.background)
                .frame(width: 84, height: 84)
                .background(Theme.foreground, in: Circle())
            if let amountPesos {
                Text(PesoFormat.signed(amountPesos, incoming: incoming))
                    .font(Theme.number(44))
                    .foregroundStyle(Theme.foreground)
            }
            if !name.isEmpty {
                Text(name).font(Theme.caption).foregroundStyle(Theme.soft)
            }
        }
        .sensoryFeedback(.success, trigger: true)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(incoming ? "Pago recibido" : "Listo")
        .task {
            try? await Task.sleep(for: .seconds(3))
            if case .done = model.flow { model.flow = nil }
        }
    }
}

/// It did not go through. One icon says why; nothing was paid.
struct FailedView: View {
    let amountPesos: UInt64?
    let reason: FailureReason

    private var icon: String {
        switch reason {
        case .noRoute: "wifi.slash"
        case .expired: "clock.badge.xmark"
        case .rejected: "xmark"
        case .unconfirmed: "questionmark"
        }
    }

    /// Only a failure that certainly did not pay strikes the amount out.
    private var certainlyNotPaid: Bool { reason != .unconfirmed }

    private var spoken: String {
        switch reason {
        case .noRoute: "Sin conexión. No se pagó."
        case .expired: "No se confirmó a tiempo. No se pagó."
        case .rejected: "Pago rechazado. No se pagó."
        case .unconfirmed: "No se pudo confirmar. Revisa tus movimientos antes de pagar de nuevo."
        }
    }

    var body: some View {
        VStack(spacing: 22) {
            Image(systemName: icon)
                .font(.system(size: 32, weight: .regular))
                .foregroundStyle(Theme.foreground)
                .frame(width: 84, height: 84)
                .overlay(Circle().strokeBorder(Theme.dim, lineWidth: 1))
            if let amountPesos, amountPesos > 0 {
                Text(PesoFormat.whole(amountPesos))
                    .font(Theme.number(44))
                    .foregroundStyle(Theme.dim)
                    .strikethrough(certainlyNotPaid, color: Theme.dim)
            }
        }
        .sensoryFeedback(.error, trigger: true)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }
}
