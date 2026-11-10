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
        wrong = !model.unlock(blob: blob, password: password)
        if wrong { password = "" }
    }
}
