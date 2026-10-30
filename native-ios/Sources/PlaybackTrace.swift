import Foundation

/// Small, device-local diagnostics: fixed stages, source enum and numeric
/// errors only. Never receives a Track, account, URL, token or error userInfo.
@MainActor final class PlaybackTrace {
    struct Entry: Codable {
        var stage: String
        var source: String?
        var uptime: TimeInterval
        var codes: [String]
    }
    private let file: URL
    private var entries: [Entry]
    init(documents: URL) {
        file = documents.appendingPathComponent("odify-playback-diagnostics.json")
        entries = (try? JSONDecoder().decode([Entry].self, from: Data(contentsOf: file))) ?? []
        entries = Array(entries.suffix(80))
    }
    func record(_ stage: String, source: MusicSource?, error: Error? = nil, statusCodes: [Int] = []) {
        var codes = statusCodes.prefix(4).map { "media-status \($0)" }
        var value = error.map { $0 as NSError }
        for _ in 0..<4 {
            guard let failure = value else { break }
            let domain = failure.domain.range(of: #"^[A-Za-z0-9._-]{1,100}$"#, options: .regularExpression) != nil ? failure.domain : "Error"
            codes.append("\(domain) \(failure.code)")
            value = failure.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        entries.append(Entry(stage: stage, source: source?.rawValue, uptime: ProcessInfo.processInfo.systemUptime, codes: codes))
        entries = Array(entries.suffix(80))
        if let data = try? JSONEncoder().encode(entries) {
            try? data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }
}
