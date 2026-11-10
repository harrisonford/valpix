import SwiftUI

@main
struct ValpixApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                // A link tapped in another app. It can ask to pay or invite,
                // never to replace the wallet.
                .onOpenURL { model.open(text: $0.absoluteString, from: .external) }
                .task {
                    await model.unlockApp()
                    model.start()
                }
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .background: model.lock()
                    case .active: Task { await model.unlockApp() }
                    default: break
                    }
                }
        }
    }
}
