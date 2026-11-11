import SwiftUI
import ValpixCore

/// A phone with no account yet. It shows who it is; a member scans this to
/// bring it in, and the number appears once the chain says so.
struct JoinView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                NameField()
                SignalDot(hasRoute: model.hasRoute)
            }
            .padding(.top, 12)
            Spacer()
            // Tap the code to copy it, to be brought in by a member who is somewhere else.
            QRImage(text: ValpixLink.join(key: model.publicKey, name: model.sharedName).string, copyable: true)
                .frame(width: 250, height: 250)
                .accessibilityLabel("Código para unirte")
                .accessibilityValue(model.publicKey.base58)
            Spacer()
            // A wallet from another phone, or a backup, comes in through the same scanner.
            GlassButton(icon: "qrcode.viewfinder", label: "Leer código") { model.flow = .scan }
                .padding(.bottom, 36)
        }
    }
}

/// A QR code: dark modules on a white card with a quiet margin, so it scans
/// reliably whatever surrounds it.
struct QRImage: View {
    let text: String
    /// Tap puts the code's text on the clipboard, so it can be sent to
    /// someone who is not here to scan it; they paste it into their own
    /// scanner. Only for codes that are safe to show anyone: never the one
    /// that carries the wallet.
    var copyable = false
    @State private var copies = 0
    @State private var justCopied = false

    var body: some View {
        if let image = Self.make(text) {
            let card = RoundedRectangle(cornerRadius: 22, style: .continuous)
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(14)
                .background(Color.white, in: card)
                .overlay {
                    // The phone says nothing when something is copied, so the card does.
                    if justCopied {
                        card.fill(Color.white.opacity(0.94))
                            .overlay(Image(systemName: "checkmark").font(.system(size: 46, weight: .medium)).foregroundStyle(.black))
                            .transition(.opacity)
                    }
                }
                .contentShape(card)
                .onTapGesture {
                    guard copyable else { return }
                    UIPasteboard.general.string = text
                    copies += 1
                    withAnimation(.easeOut(duration: 0.15)) { justCopied = true }
                    Task {
                        try? await Task.sleep(for: .seconds(1))
                        withAnimation(.easeIn(duration: 0.25)) { justCopied = false }
                    }
                }
                .sensoryFeedback(.success, trigger: copies)
                .accessibilityAddTraits(copyable ? .isButton : [])
                .accessibilityHint(copyable ? "Copiar" : "")
        }
    }

    static func make(_ text: String) -> UIImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage,
              let cgImage = CIContext().createCGImage(output, from: output.extent)
        else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
