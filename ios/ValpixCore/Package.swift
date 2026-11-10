// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ValpixCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "ValpixCore", targets: ["ValpixCore"])],
    targets: [
        .target(name: "ValpixCore"),
        .testTarget(name: "ValpixCoreTests", dependencies: ["ValpixCore"]),
    ]
)
