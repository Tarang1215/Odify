import SwiftUI

// The Android app's shared neutral palette and original launcher mark.
enum OdifyPalette {
    static let background = Color(red: 12/255, green: 14/255, blue: 18/255)
    static let surface = Color(red: 27/255, green: 30/255, blue: 37/255)
    static let raised = Color(red: 41/255, green: 45/255, blue: 54/255)
    static let text = Color(red: 242/255, green: 243/255, blue: 246/255)
    static let muted = Color(red: 162/255, green: 166/255, blue: 176/255)
}

struct OdifyMark: Shape {
    private static let points = [
        CGPoint(x: 78.4, y: 24.3),
        CGPoint(x: 75.6, y: 23.8),
        CGPoint(x: 47.3, y: 30.8),
        CGPoint(x: 45.1, y: 32.5),
        CGPoint(x: 44.2, y: 34.7),
        CGPoint(x: 44.2, y: 60.3),
        CGPoint(x: 43.9, y: 61.9),
        CGPoint(x: 42.6, y: 64.4),
        CGPoint(x: 40.8, y: 66.0),
        CGPoint(x: 38.5, y: 67.0),
        CGPoint(x: 32.4, y: 68.3),
        CGPoint(x: 30.7, y: 69.5),
        CGPoint(x: 29.0, y: 71.5),
        CGPoint(x: 27.9, y: 74.3),
        CGPoint(x: 27.9, y: 77.3),
        CGPoint(x: 28.9, y: 79.8),
        CGPoint(x: 30.8, y: 82.1),
        CGPoint(x: 33.3, y: 83.7),
        CGPoint(x: 35.2, y: 84.3),
        CGPoint(x: 40.1, y: 84.1),
        CGPoint(x: 43.9, y: 82.3),
        CGPoint(x: 46.7, y: 78.9),
        CGPoint(x: 47.7, y: 74.6),
        CGPoint(x: 47.6, y: 39.3),
        CGPoint(x: 48.1, y: 38.8),
        CGPoint(x: 49.0, y: 39.2),
        CGPoint(x: 53.7, y: 45.1),
        CGPoint(x: 54.7, y: 45.6),
        CGPoint(x: 61.7, y: 44.3),
        CGPoint(x: 64.2, y: 45.9),
        CGPoint(x: 65.4, y: 46.1),
        CGPoint(x: 75.6, y: 43.4),
        CGPoint(x: 76.5, y: 43.9),
        CGPoint(x: 76.6, y: 50.7),
        CGPoint(x: 75.2, y: 55.1),
        CGPoint(x: 72.1, y: 58.4),
        CGPoint(x: 66.0, y: 60.5),
        CGPoint(x: 63.7, y: 61.8),
        CGPoint(x: 61.2, y: 65.1),
        CGPoint(x: 60.6, y: 69.0),
        CGPoint(x: 61.2, y: 71.4),
        CGPoint(x: 62.8, y: 73.9),
        CGPoint(x: 64.9, y: 75.7),
        CGPoint(x: 67.8, y: 76.9),
        CGPoint(x: 70.9, y: 77.1),
        CGPoint(x: 73.3, y: 76.6),
        CGPoint(x: 75.9, y: 75.4),
        CGPoint(x: 78.1, y: 73.4),
        CGPoint(x: 79.6, y: 70.9),
        CGPoint(x: 80.0, y: 68.3),
        CGPoint(x: 79.9, y: 26.5)
    ]
    func path(in rect: CGRect) -> Path {
        var source = Path()
        source.addLines(Self.points); source.closeSubpath()
        source.addEllipse(in: CGRect(x: 57.3, y: 48.7, width: 3, height: 3))
        let scale = min(rect.width / 52.1, rect.height / 60.5)
        let transform = CGAffineTransform(translationX: -27.9, y: -23.8)
            .concatenating(CGAffineTransform(scaleX: scale, y: scale))
            .concatenating(CGAffineTransform(translationX: rect.midX - 52.1 * scale / 2, y: rect.midY - 60.5 * scale / 2))
        return source.applying(transform)
    }
}
