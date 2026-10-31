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
    func coverJPEG(_ data: Data) -> Data? {
        guard data.count < 40_000_000, let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 1200,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { return nil }
        return UIImage(cgImage: image).jpegData(compressionQuality: 0.85)
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
        let neutral = UIColor(red: 140.0 / 255, green: 150.0 / 255, blue: 163.0 / 255, alpha: 1)
        guard let image = cgImage else { return neutral }
        let side = 20
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let drawn = pixels.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        guard drawn else { return neutral }
        var bins = [(weight: CGFloat, r: CGFloat, g: CGFloat, b: CGFloat)](repeating: (0, 0, 0, 0), count: 12)
        for index in stride(from: 0, to: pixels.count, by: 4) {
            guard pixels[index + 3] > 200 else { continue }
            let r = CGFloat(pixels[index]) / 255, g = CGFloat(pixels[index + 1]) / 255, b = CGFloat(pixels[index + 2]) / 255
            var hue: CGFloat = 0, saturation: CGFloat = 0, brightness: CGFloat = 0
            UIColor(red: r, green: g, blue: b, alpha: 1).getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: nil)
            guard saturation > 0.18, brightness > 0.16 else { continue }
            let bin = min(11, Int(hue * 12)), weight = saturation * brightness
            bins[bin].weight += weight; bins[bin].r += r * weight; bins[bin].g += g * weight; bins[bin].b += b * weight
        }
        guard let dominant = bins.max(by: { $0.weight < $1.weight }), dominant.weight > 0 else { return neutral }
        let color = UIColor(red: dominant.r / dominant.weight, green: dominant.g / dominant.weight, blue: dominant.b / dominant.weight, alpha: 1)
        var hue: CGFloat = 0, saturation: CGFloat = 0, brightness: CGFloat = 0
        color.getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: nil)
        return UIColor(hue: hue, saturation: min(0.68, max(0.3, saturation)), brightness: min(0.82, max(0.55, brightness)), alpha: 1)
    }
}

struct Artwork: View {
    var address: String?
    var title: String
    var size: CGFloat
    var radius: CGFloat = 16
    var portrait = false
    @EnvironmentObject private var store: LibraryStore
    @Environment(\.displayScale) private var displayScale
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().interpolation(.high).scaledToFill()
                    .frame(width: size, height: size).clipped()
            }
            else {
                let seed = title.unicodeScalars.reduce(0) { ($0 + Int($1.value)) % 360 }
                LinearGradient(colors: [Color(hue: Double(seed) / 360, saturation: 0.28, brightness: 0.48), Color(hue: Double((seed + 50) % 360) / 360, saturation: 0.45, brightness: 0.14)], startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: portrait ? "person.fill" : "waveform").font(.system(size: size * 0.27, weight: .light)).foregroundStyle(.white.opacity(0.65))
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: portrait ? size / 2 : radius, style: .continuous))
        .accessibilityHidden(true)
        .task(id: "\(address ?? "")|\(size)|\(displayScale)") {
            image = nil
            if let address {
                let loaded = await ArtworkLoader.shared.image(address, documents: store.documents, size: Int(size * displayScale))
                if !Task.isCancelled { image = loaded }
            }
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
        LinearGradient(colors: [OdifyPalette.surface, OdifyPalette.background], startPoint: .topLeading, endPoint: .bottomTrailing)
            .ignoresSafeArea()
    }
}

struct PlayerGlassBackdrop: View {
    var tint: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Rectangle().fill(.ultraThinMaterial)
                RadialGradient(colors: [tint.opacity(0.55), tint.opacity(0.13), .clear],
                               center: .topLeading, startRadius: 0, endRadius: max(geometry.size.width, geometry.size.height) * 0.9)
                RadialGradient(colors: [tint.opacity(0.3), .clear], center: .bottomTrailing,
                               startRadius: 0, endRadius: geometry.size.width * 0.8)
            }
        }.ignoresSafeArea().allowsHitTesting(false)
            .animation(reduceMotion ? nil : .easeInOut(duration: 1.2), value: tint)
    }
}
