// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "OdifyStreaming", platforms: [.iOS(.v15), .macOS(.v14)],
    products: [.library(name: "OdifyStreaming", targets: ["OdifyStreaming"])],
    targets: [.target(name: "OdifyStreaming")])
