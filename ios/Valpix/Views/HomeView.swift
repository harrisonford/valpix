import SwiftUI
import ValpixCore

struct HomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                NameField()
                SignalDot(hasRoute: model.hasRoute)
            }
            .padding(.top, 6)

            Spacer()

            BalanceText(size: 60)
                .contentShape(Rectangle())
                .onTapGesture { model.flow = .history }
                .onLongPressGesture { model.flow = .wallet }
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Movimientos")

            Spacer()

            HStack(spacing: 44) {
                GlassButton(icon: "qrcode.viewfinder", label: "Pagar") { model.flow = .scan }
                GlassButton(icon: "qrcode", label: "Cobrar") { model.flow = .charge }
            }
            .opacity(model.hasRoute ? 1 : 0.3)
            .disabled(!model.hasRoute)
            .padding(.bottom, 36)
        }
    }
}

/// The balance: whole pesos in white, four decimals in gray, never still.
struct BalanceText: View {
    @Environment(AppModel.self) private var model
    var size: CGFloat

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { context in
            let parts = PesoFormat.parts(micro: model.balance(at: context.date))
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(parts.whole)
                    .font(Theme.number(size))
                    .foregroundStyle(Theme.foreground)
                Text("," + parts.decimals)
                    .font(Theme.number(size * 0.46))
                    .foregroundStyle(Theme.dim)
            }
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(parts.whole) pesos")
        }
        .padding(.horizontal, 24)
    }
}

struct HistoryView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            BalanceText(size: 34).padding(.top, 64).padding(.bottom, 28)
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(model.history) { entry in
                        HStack(alignment: .firstTextBaseline) {
                            Text(title(entry)).foregroundStyle(Theme.soft).lineLimit(1)
                            Text(time(entry.ts)).foregroundStyle(Theme.dim)
                            Spacer()
                            Text(amount(entry)).foregroundStyle(Theme.foreground).monospacedDigit()
                        }
                        .font(Theme.caption)
                        .padding(.vertical, 13)
                        .overlay(alignment: .top) { Theme.hairline.frame(height: 1) }
                    }
                }
                .padding(.horizontal, 24)
            }
        }
        .task { await model.loadHistory() }
    }

    private func title(_ entry: HistoryEntry) -> String {
        model.displayName(for: entry.counterparty, fallback: entry.name)
    }

    private func amount(_ entry: HistoryEntry) -> String {
        let pesos = Money.wholePesos(micro: entry.amountMicro)
        switch entry.kind {
        case .received: return PesoFormat.signed(pesos, incoming: true)
        case .sent, .invited: return PesoFormat.signed(pesos, incoming: false)
        case .joined: return ""
        }
    }

    private func time(_ ts: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(ts))
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "es_CL")
        formatter.dateFormat = Calendar.current.isDateInToday(date) ? "HH:mm" : "d MMM"
        return formatter.string(from: date)
    }
}
