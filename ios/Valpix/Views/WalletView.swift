import SwiftUI
import ValpixCore

/// The wallet itself: move it to another phone, or make a backup.
/// Both show a QR that IS the wallet, so both ask for approval first.
struct WalletView: View {
    @Environment(AppModel.self) private var model
    @State private var shown: String?
    @State private var password = ""
    @State private var askingPassword = false

    var body: some View {
        VStack(spacing: 28) {
            Spacer()
            if let shown {
                QRImage(text: shown)
                    .frame(width: 264, height: 264)
                    .accessibilityLabel("Código de tu billetera. No lo compartas.")
            } else if askingPassword {
                SecureField("", text: $password, prompt: Text("••••••••").foregroundStyle(Theme.dim))
                    .font(.system(size: 24, weight: .light))
                    .foregroundStyle(Theme.foreground)
                    .multilineTextAlignment(.center)
                    .textContentType(.newPassword)
                    .submitLabel(.done)
                    .onSubmit(makeBackup)
                    .accessibilityLabel("Contraseña del respaldo")
                GlassButton(icon: "checkmark", label: "Crear respaldo", action: makeBackup)
                    .opacity(password.isEmpty ? 0.3 : 1)
                    .disabled(password.isEmpty)
            } else {
                Text(model.publicKey.base58)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Theme.dim)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
                    .textSelection(.enabled)
                // The person's own access point to Solana, tried first.
                TextField("", text: Binding(get: { model.accessPoint }, set: { model.accessPoint = $0 }),
                          prompt: Text("https://").foregroundStyle(Theme.dim))
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(Theme.soft)
                    .multilineTextAlignment(.center)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
                    .accessibilityLabel("Tu propio punto de acceso")
            }
            Spacer()
            if shown == nil && !askingPassword {
                HStack(spacing: 44) {
                    GlassButton(icon: "iphone.and.arrow.forward", label: "Pasar a otro teléfono") {
                        Task {
                            if await Authenticator.confirm(reason: "Mostrar tu billetera") { shown = model.walletLink }
                        }
                    }
                    GlassButton(icon: "lock.doc", label: "Respaldo con contraseña") { askingPassword = true }
                }
                .padding(.bottom, 36)
            }
        }
        .padding(.horizontal, 24)
    }

    private func makeBackup() {
        guard !password.isEmpty else { return }
        Task {
            guard await Authenticator.confirm(reason: "Crear respaldo") else { return }
            shown = model.backupLink(password: password)
            password = ""
            askingPassword = false
        }
    }
}

/// A scanned backup: type its password to bring the wallet onto this phone.
struct UnlockView: View {
    @Environment(AppModel.self) private var model
    let blob: Data
    @State private var password = ""
    @State private var wrong = false

    var body: some View {
        VStack(spacing: 28) {
            Spacer()
            Image(systemName: wrong ? "lock.slash" : "lock")
                .font(.system(size: 44, weight: .thin))
                .foregroundStyle(wrong ? Theme.dim : Theme.foreground)
            SecureField("", text: $password, prompt: Text("••••••••").foregroundStyle(Theme.dim))
                .font(.system(size: 24, weight: .light))
                .foregroundStyle(Theme.foreground)
                .multilineTextAlignment(.center)
                .textContentType(.password)
                .submitLabel(.done)
                .onSubmit(open)
                .accessibilityLabel("Contraseña del respaldo")
            Spacer()
            GlassButton(icon: "checkmark", label: "Abrir respaldo", action: open)
                .opacity(password.isEmpty ? 0.3 : 1)
                .disabled(password.isEmpty)
                .padding(.bottom, 36)
        }
        .padding(.horizontal, 24)
    }

    private func open() {
        let attempt = password
        Task {
            wrong = !(await model.unlock(blob: blob, password: attempt))
            if wrong { password = "" }
        }
    }
}

/// A scanned wallet: one button to make it this phone's wallet.
struct AdoptView: View {
    @Environment(AppModel.self) private var model
    let seed: Data

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Image(systemName: "iphone.and.arrow.forward.inward")
                .font(.system(size: 54, weight: .thin))
                .foregroundStyle(Theme.foreground)
            if let key = Keypair(seed: seed)?.publicKey {
                Text(model.displayName(for: key))
                    .font(Theme.caption)
                    .foregroundStyle(Theme.soft)
                    .padding(.top, 18)
            }
            Spacer()
            GlassButton(icon: "faceid", label: "Usar esta billetera") {
                Task { _ = await model.adopt(seed: seed) }
            }
            .padding(.bottom, 36)
        }
    }
}
