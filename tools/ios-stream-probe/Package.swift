// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "OdifyStreamProbe", platforms: [.macOS(.v14)],
    dependencies: [.package(path: "../../native-ios/Streaming")],
    targets: [.executableTarget(name: "OdifyStreamProbe", dependencies: [.product(name: "OdifyStreaming", package: "Streaming")])])
