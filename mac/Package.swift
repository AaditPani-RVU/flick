// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "FlickReceiver",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "FlickReceiver",
            path: "Sources/FlickReceiver",
            swiftSettings: [.unsafeFlags(["-swift-version", "5"])]
        )
    ]
)
