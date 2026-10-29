import ImageIO
import SwiftUI

actor ArtworkLoader {
    static let shared = ArtworkLoader()
    private let cache = NSCache<NSString, UIImage>()
    private var keys = Set<String>()
    init() { cache.totalCostLimit = 24 * 1024 * 1024; cache.countLimit = 120 }
    func evict(_ address: String) {
        for key in keys.filter({ $0.hasPrefix(address + "|") }) { cache.removeObject(forKey: key as NSString); keys.remove(key) }
    }
    func image(_ address: String, documents: URL, size: Int) async -> UIImage? {
        let pixels = min(1000, max(120, size)), key = address + "|\(pixels)"
        if let image = cache.object(forKey: key as NSString) { return image }
        do {
            let data: Data
            if let url = URL(string: address), url.scheme == "https" { data = try await MusicHTTP.data(url) }
            else {
                let url = documents.appendingPathComponent(address).standardizedFileURL
                guard url.path.hasPrefix(documents.standardizedFileURL.path + "/") else { return nil }
                data = try Data(contentsOf: url)
            }
            guard !Task.isCancelled, let source = CGImageSourceCreateWithData(data as CFData, nil),
                  let bitmap = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: pixels,
                    kCGImageSourceShouldCacheImmediately: true
                  ] as CFDictionary) else { return nil }
            let image = UIImage(cgImage: bitmap)
            cache.setObject(image, forKey: key as NSString, cost: bitmap.bytesPerRow * bitmap.height); keys.insert(key)
            return image
        } catch { return nil }
    }
}

extension UIImage {
    var odifyTint: UIColor {
        guard let image = cgImage else { return .systemTeal }
        var pixel = [UInt8](repeating: 0, count: 4)
        let color: UIColor = pixel.withUnsafeMutableBytes { bytes in
            guard let context = CGContext(data: bytes.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return .systemTeal }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: 1, height: 1))
            let values = bytes.bindMemory(to: UInt8.self)
            return UIColor(red: max(0.18, min(0.72, Double(values[0]) / 255)),
                           green: max(0.18, min(0.72, Double(values[1]) / 255)),
                           blue: max(0.18, min(0.72, Double(values[2]) / 255)), alpha: 1)
        }
        return color
    }
}

struct Artwork: View {
    var address: String?
    var title: String
    var size: CGFloat
    var radius: CGFloat = 16
    var portrait = false
    @EnvironmentObject private var store: LibraryStore
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            else {
                let seed = title.unicodeScalars.reduce(0) { ($0 + Int($1.value)) % 360 }
                LinearGradient(colors: [Color(hue: Double(seed) / 360, saturation: 0.28, brightness: 0.48), Color(hue: Double((seed + 50) % 360) / 360, saturation: 0.45, brightness: 0.14)], startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: portrait ? "person.fill" : "waveform").font(.system(size: size * 0.27, weight: .light)).foregroundStyle(.white.opacity(0.65))
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: portrait ? size / 2 : radius, style: .continuous))
        .accessibilityHidden(true)
        .task(id: address) {
            image = nil
            if let address { image = await ArtworkLoader.shared.image(address, documents: store.documents, size: Int(size * 2)) }
        }
    }
}

struct ArtistPortrait: View {
    var track: Track
    var size: CGFloat = 92
    @State private var address: String?
    var body: some View {
        Artwork(address: address, title: track.artist, size: size, portrait: true)
            .task(id: track.artistID ?? track.artist) { address = await MusicCatalog.shared.portrait(track) }
    }
}

struct GlassCircle: View {
    let symbol: String
    let label: String
    var size: CGFloat = 46
    var action: () -> Void
    var body: some View {
        Button(action: action) { Image(systemName: symbol).font(.system(size: 18, weight: .medium)).frame(width: size, height: size) }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .accessibilityLabel(label)
    }
}

struct MusicBackdrop: View {
    var tint: Color = Color(red: 0.2, green: 0.38, blue: 0.34)
    var body: some View {
        LinearGradient(colors: [tint.opacity(0.45), Color(red: 0.055, green: 0.065, blue: 0.075), .black], startPoint: .topLeading, endPoint: .bottomTrailing)
            .ignoresSafeArea()
    }
}
