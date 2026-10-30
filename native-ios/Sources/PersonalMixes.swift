import Foundation

struct ListenEvent: Codable, Equatable {
    var occurrence: String
    var recordingKey: String
    var at: Date
}

/// Count observed playing time, never a seek position or time spent paused.
struct ListeningMeter {
    private var occurrence = ""
    private var previous: TimeInterval?
    private var previousPlaying = false
    private var seconds: TimeInterval = 0
    private var reported = false
    mutating func sample(occurrence: String, playing: Bool, uptime: TimeInterval) -> Bool {
        if self.occurrence != occurrence { self.occurrence = occurrence; previous = nil; seconds = 0; reported = false; previousPlaying = false }
        if let previous, previousPlaying, playing, uptime >= previous, uptime - previous <= 3 { seconds += uptime - previous }
        previous = uptime; previousPlaying = playing
        guard seconds >= 30, !reported else { return false }
        reported = true; return true
    }
}

struct PersonalMix: Identifiable {
    var id: String
    var name: String
    var reason: String
    var tracks: [Track]
}

enum PersonalMixes {
    static func build(tracks: [Track], liked: Set<String>, events: [ListenEvent], now: Date = Date()) -> [PersonalMix] {
        let catalog = Track.unique(tracks).sorted { $0.id < $1.id }
        let likedKeys = Set(tracks.filter { liked.contains($0.id) }.map(\.recordingKey))
        let recent = events.filter { now.timeIntervalSince($0.at) <= 60 * 86400 }
        let grouped = Dictionary(grouping: recent, by: \.recordingKey)
        var artists: [String: Double] = [:]
        for track in catalog {
            let plays = grouped[track.recordingKey] ?? []
            let age = plays.map { now.timeIntervalSince($0.at) / 86400 }.min() ?? 0
            let weight = (likedKeys.contains(track.recordingKey) ? 3.0 : 0) + log(1 + Double(plays.count)) * exp(-max(0, age) / 21)
            artists[Track.normalized(track.artist), default: 0] += weight
        }
        let scored = catalog.map { track -> (Track, Double) in
            let score = artists[Track.normalized(track.artist), default: 0] * 2 + (likedKeys.contains(track.recordingKey) ? 1.0 : 0)
            return (track, score / (1 + Double(grouped[track.recordingKey]?.count ?? 0) * 0.15))
        }.filter { $0.1 > 0 }.sorted { $0.1 == $1.1 ? $0.0.id < $1.0.id : $0.1 > $1.1 }
        var counts: [String: Int] = [:]
        let daily = scored.compactMap { pair -> Track? in
            let artist = Track.normalized(pair.0.artist)
            guard counts[artist, default: 0] < 3 else { return nil }
            counts[artist, default: 0] += 1; return pair.0
        }
        let repeats = catalog.filter { grouped[$0.recordingKey] != nil }.sorted { a, b in
            let x = grouped[a.recordingKey] ?? [], y = grouped[b.recordingKey] ?? []
            return x.count == y.count ? (x.map(\.at).max() ?? .distantPast) > (y.map(\.at).max() ?? .distantPast) : x.count > y.count
        }
        let short = catalog.filter { $0.duration <= 0 || (45...900).contains($0.duration) }
        let day = Int(now.timeIntervalSince1970 / 86400)
        func shuffled(_ list: [Track], salt: Int) -> [Track] {
            func score(_ id: String) -> UInt64 { id.utf8.reduce(UInt64(bitPattern: Int64(day + salt))) { ($0 &* 1099511628211) ^ UInt64($1) } }
            return list.sorted { score($0.id) < score($1.id) }
        }
        var mixes: [PersonalMix] = []
        func add(_ id: String, _ name: String, _ reason: String, _ list: [Track]) {
            if !list.isEmpty { mixes.append(PersonalMix(id: id, name: name, reason: reason, tracks: Array(list.prefix(30)))) }
        }
        add("daily", "Your daily mix", "자주 듣는 아티스트와 좋아요를 바탕으로", daily)
        add("repeat", "On repeat", "최근 60일, 충분히 들은 음악부터", repeats)
        add("liked", "Close to your heart", "좋아요한 음악을 한자리에", catalog.filter { likedKeys.contains($0.recordingKey) })
        add("unheard", "A little discovery", "아직 듣지 않은 내 음악 · 매일 다른 선택", shuffled(short.filter { grouped[$0.recordingKey] == nil && !likedKeys.contains($0.recordingKey) }, salt: 0))
        add("rediscover", "Worth another listen", "2주 넘게 만나지 못한, 익숙한 취향", short.filter { (grouped[$0.recordingKey]?.map(\.at).max()).map { now.timeIntervalSince($0) > 14 * 86400 } == true })
        add("offline", "Offline essentials", "연결 없이도 함께할 내 음악", shuffled(short.filter { $0.localFile != nil }, salt: 17))
        if mixes.isEmpty { add("start", "A little discovery", "내 음악에서 시작해요 · 들을수록 취향이 쌓여요", catalog) }
        return mixes
    }
}
