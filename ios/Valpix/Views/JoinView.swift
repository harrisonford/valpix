import SwiftUI
import ValpixCore

/// A phone with no account yet. It shows who it is; a member scans this to
/// bring it in, and the number appears once the chain says so.
struct JoinView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                NameField()
                SignalDot(hasRoute: model.hasRoute)
            }
            .padding(.top, 6)
            Spacer()
            QRImage(text: ValpixLink.join(key: model.publicKey, name: model.sharedName).string)
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

    var body: some View {
        if let image = Self.make(text) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(14)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
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
