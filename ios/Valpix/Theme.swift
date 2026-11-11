import SwiftUI

/// The whole look in one place: pitch black, white, gray, glass. Restyle
/// the app by changing this file.
enum Theme {
    static let background = Color.black
    static let foreground = Color.white
    /// Decimals, amounts in transit, anything not yet real.
    static let dim = Color(white: 0.42)
    /// Names and times.
    static let soft = Color(white: 0.70)
    static let hairline = Color(white: 0.11)

    /// Gray that still has to be read: the balance's decimals.
    static let faint = Color(white: 0.56)

    /// Figures that do not jiggle as they change, heavy enough to read at
    /// arm's length.
    static func number(_ size: CGFloat) -> Font {
        .system(size: size, weight: .regular).monospacedDigit()
    }
    static let caption = Font.system(size: 15, weight: .regular)
    /// The person's own name, at the top of the main screens.
    static let name = Font.system(size: 20, weight: .medium)
}

/// A sum of money: the figure and, under it, the currency, so a bare number
/// is never mistaken for anything else (Kris, 2026-11-11).
struct Amount: View {
    let whole: String
    var size: CGFloat
    var color: Color = Theme.foreground
    /// The balance's four decimals, smaller and gray.
    var decimals: String? = nil
    var struck = false
    /// Read aloud in place of the figures.
    let spoken: String

    var body: some View {
        let unit = max(13, size * 0.26)
        VStack(spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(whole)
                    .font(Theme.number(size))
                    .foregroundStyle(color)
                    .strikethrough(struck, color: color)
                if let decimals {
                    Text("," + decimals)
                        .font(Theme.number(size * 0.46))
                        .foregroundStyle(Theme.faint)
                }
            }
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            Text("CLP")
                .font(.system(size: unit, weight: .medium))
                .kerning(unit * 0.14)
                .foregroundStyle(Theme.soft)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }
}

/// A round glass control carrying one icon and no label.
struct GlassButton: View {
    let icon: String
    /// Spoken by screen readers; never shown.
    let label: String
    var size: CGFloat = 68
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: size * 0.36, weight: .regular))
                .foregroundStyle(Theme.foreground)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .glassCircle()
        .accessibilityLabel(label)
    }
}

extension View {
    /// System glass where it exists, a translucent material below iOS 26.
    @ViewBuilder
    func glassCircle() -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(.regular.interactive(), in: .circle)
        } else {
            background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().strokeBorder(Color.white.opacity(0.22), lineWidth: 1))
        }
    }
}

/// A number pad for whole pesos.
struct Keypad: View {
    @Binding var value: UInt64
    var max: UInt64 = 1_000_000_000

    private let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫"]

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(76), spacing: 18), count: 3), spacing: 14) {
            ForEach(keys, id: \.self) { key in
                if key.isEmpty {
                    Color.clear.frame(width: 76, height: 76)
                } else {
                    Button { press(key) } label: {
                        Group {
                            if key == "⌫" {
                                Image(systemName: "delete.left").font(.system(size: 22, weight: .light))
                            } else {
                                Text(key).font(.system(size: 30, weight: .light))
                            }
                        }
                        .foregroundStyle(Theme.foreground)
                        .frame(width: 76, height: 76)
                        .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .glassCircle()
                    .accessibilityLabel(key == "⌫" ? "Borrar" : key)
                }
            }
        }
    }

    private func press(_ key: String) {
        if key == "⌫" {
            value /= 10
        } else if let digit = UInt64(key) {
            let next = value * 10 + digit
            if next <= max { value = next }
        }
    }
}
