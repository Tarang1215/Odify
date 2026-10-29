// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "OdifyStreamProbe", platforms: [.macOS(.v14)],
    dependencies: [.package(url: "https://github.com/alexeichhorn/YouTubeKit.git", revision: "89854c93207ad0d2b3d78d6f1aea24595ca66552")],
    targets: [.executableTarget(name: "OdifyStreamProbe", dependencies: [.product(name: "YouTubeKit", package: "YouTubeKit")])])
