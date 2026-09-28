// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "deckd",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "deckd",
            path: "Sources/deckd"
        )
    ]
)
