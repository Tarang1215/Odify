import Foundation

struct LyricCandidate: Identifiable, Hashable {
    var id: Int
    var title: String
    var artist: String
    var album: String
    var duration: Double
    var synced: String
    var plain: String
    var text: String { synced.isEmpty ? plain : synced }
}

actor LyricsCatalog {
    static let shared = LyricsCatalog()
    private let session: URLSession
    private var cache: [String: (Date, [LyricCandidate])] = [:]
    private var retryAfter = Date.distantPast
    init(session: URLSession? = nil) {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.httpShouldSetCookies = false
        config.timeoutIntervalForRequest = 12; config.timeoutIntervalForResource = 18
        self.session = session ?? URLSession(configuration: config)
    }
    func search(title: String, artist: String) async throws -> [LyricCandidate] {
        let title = String(title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(180))
        let artist = String(artist.trimmingCharacters(in: .whitespacesAndNewlines).prefix(140))
        guard !title.isEmpty else { throw OdifyError.message("곡명을 입력해 주세요.") }
        let url = MusicHTTP.url("https://lrclib.net/api/search", artist.isEmpty ? ["track_name": title] : ["track_name": title, "artist_name": artist])
        if let (time, value) = cache[url.absoluteString], Date().timeIntervalSince(time) < 900 { return value }
        guard Date() >= retryAfter else { throw OdifyError.message("가사 검색 요청이 많습니다. 잠시 후 다시 시도해 주세요.") }
        var request = URLRequest(url: url)
        request.httpShouldHandleCookies = false
        request.setValue("Odify/1.1 (https://github.com/Tarang1215/Odify)", forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else { throw OdifyError.message("가사 응답을 확인하지 못했습니다.") }
        if [429, 503].contains(http.statusCode) {
            let delay = Double(http.value(forHTTPHeaderField: "Retry-After") ?? "") ?? 60
            let date = http.value(forHTTPHeaderField: "Retry-After").flatMap(Self.httpDate)
            retryAfter = max(Date().addingTimeInterval(max(1, delay)), date ?? .distantPast)
            throw OdifyError.message("가사 검색이 잠시 지연되고 있습니다. 잠시 후 다시 시도해 주세요.")
        }
        guard http.statusCode == 200, data.count < 2_000_000 else { throw OdifyError.message("가사 검색에 실패했습니다 (\(http.statusCode)). 다시 시도해 주세요.") }
        let value = try Self.parse(data)
        if cache.count >= 30 { cache.removeAll() }
        cache[url.absoluteString] = (Date(), value)
        return value
    }
    private static func httpDate(_ value: String) -> Date? {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0); formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss z"
        return formatter.date(from: value)
    }
    static func parse(_ data: Data) throws -> [LyricCandidate] {
        let rows = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] ?? []
        var ids = Set<Int>()
        return rows.prefix(30).compactMap { row in
            guard let id = row["id"] as? Int, ids.insert(id).inserted, row["instrumental"] as? Bool != true,
                  let title = row["trackName"] as? String, let artist = row["artistName"] as? String else { return nil }
            let synced = row["syncedLyrics"] as? String ?? "", plain = row["plainLyrics"] as? String ?? ""
            guard !synced.isEmpty || !plain.isEmpty, synced.count <= 200_000, plain.count <= 200_000 else { return nil }
            return LyricCandidate(id: id, title: title, artist: artist, album: row["albumName"] as? String ?? "",
                                  duration: row["duration"] as? Double ?? 0, synced: synced, plain: plain)
        }
    }
}

struct TimedLyric: Identifiable, Equatable {
    var id: Int
    var seconds: Double
    var text: String
}
enum LRCParser {
    static func lines(_ text: String) -> [TimedLyric] {
        let pattern = try! NSRegularExpression(pattern: #"\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]"#)
        let offsetRegex = try! NSRegularExpression(pattern: #"(?i)\[offset:([+-]?\d+)\]"#)
        var offset = 0.0
        if let match = offsetRegex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
           let range = Range(match.range(at: 1), in: text) { offset = (Double(text[range]) ?? 0) / 1000 }
        var result: [TimedLyric] = []
        for line in text.components(separatedBy: .newlines) {
            let matches = pattern.matches(in: line, range: NSRange(line.startIndex..., in: line))
            let body = pattern.stringByReplacingMatches(in: line, range: NSRange(line.startIndex..., in: line), withTemplate: "").trimmingCharacters(in: .whitespacesAndNewlines)
            for match in matches {
                func part(_ index: Int) -> String { Range(match.range(at: index), in: line).map { String(line[$0]) } ?? "" }
                let seconds = Double(part(2)) ?? 0
                guard seconds < 60 else { continue }
                let fraction = part(3)
                let time = (Double(part(1)) ?? 0) * 60 + seconds + (Double("0." + fraction) ?? 0) + offset
                result.append(TimedLyric(id: result.count, seconds: max(0, time), text: body))
            }
        }
        return result.sorted { $0.seconds == $1.seconds ? $0.id < $1.id : $0.seconds < $1.seconds }
    }
}
