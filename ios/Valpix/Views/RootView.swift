import SwiftUI
import ValpixCore

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        ZStack {
            Theme.background.ignoresSafeArea()
            if model.locked, model.phase != .storageFailed {
                // Nothing is shown until the person unlocks. Tap to try again.
                GlassButton(icon: "faceid", label: "Abrir valpix") { Task { await model.unlockApp() } }
            } else {
            switch model.phase {
            case .loading: EmptyView()
            case .needsJoin: JoinView()
            case .ready: HomeView()
            case .storageFailed:
                Image(systemName: "lock.slash")
                    .font(.system(size: 44, weight: .thin))
                    .foregroundStyle(Theme.dim)
                    .accessibilityLabel("Este teléfono no puede guardar la billetera")
            }
            }
        }
        .fullScreenCover(isPresented: Binding(get: { model.flow != nil }, set: { if !$0 { model.flow = nil } })) {
            FlowView()
        }
    }
}

/// Whatever the person is in the middle of, on a black sheet with one way out.
struct FlowView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack(alignment: .topLeading) {
            Theme.background.ignoresSafeArea()
            Group {
                switch model.flow {
                case .charge: ChargeView()
                case .scan: ScanView()
                case .confirm(let to, let amount, let name): ConfirmView(recipient: to, fixedAmount: amount, name: name)
                case .invite(let key, let name): InviteView(newcomer: key, name: name)
                case .adopt(let seed): AdoptView(seed: seed)
                case .unlock(let blob): UnlockView(blob: blob)
                case .sending(let amount, let name): SendingView(amountPesos: amount, name: name)
                case .done(let amount, let name, let incoming): DoneView(amountPesos: amount, name: name, incoming: incoming)
                case .failed(let amount, let reason): FailedView(amountPesos: amount, reason: reason)
                case .history: HistoryView()
                case .wallet: WalletView()
                case nil: EmptyView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            if showsClose {
                GlassButton(icon: "xmark", label: "Cerrar", size: 44) { model.flow = nil }
                    .padding(.leading, 20)
                    .padding(.top, 8)
            }
        }
        .preferredColorScheme(.dark)
    }

    /// A payment in flight cannot be walked away from.
    private var showsClose: Bool {
        if case .sending = model.flow { return false }
        return true
    }
}

/// The signal dot: solid with a route to the chain, hollow without.
struct SignalDot: View {
    let hasRoute: Bool

    var body: some View {
        Circle()
            .strokeBorder(hasRoute ? Theme.foreground : Theme.dim, lineWidth: 1)
            .background(Circle().fill(hasRoute ? Theme.foreground : .clear))
            .frame(width: 7, height: 7)
            .accessibilityLabel(hasRoute ? "Con conexión" : "Sin conexión")
    }
}

/// The person's own name: tap to edit, kept only on this phone.
struct NameField: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TextField("", text: $model.name, prompt: Text("·").foregroundStyle(Theme.dim))
            .font(Theme.caption)
            .foregroundStyle(Theme.soft)
            .multilineTextAlignment(.center)
            .textInputAutocapitalization(.words)
            .autocorrectionDisabled()
            .submitLabel(.done)
            .fixedSize()
            .frame(minWidth: 44, minHeight: 44)
            .accessibilityLabel("Tu nombre")
    }
}
