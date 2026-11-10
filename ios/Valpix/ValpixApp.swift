import SwiftUI

@main
struct ValpixApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .onOpenURL { model.open(text: $0.absoluteString) }
                .task { model.start() }
        }
    }
}
