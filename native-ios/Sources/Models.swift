import Foundation

enum MusicSource: String, Codable, CaseIterable {
    case local = "Local", drive = "Drive", youtube = "YouTube"
}

struct Track: Identifiable, Codable, Hashable {
    var id: String
    var title: String
    var artist: String
    var album: String = ""
    var artwork: String? = nil
    var source: MusicSource = .youtube
    var localFile: String? = nil
    var videoID: String? = nil
    var artistID: String? = nil
    var artistURL: String? = nil
    var duration: Double = 0
    var lyrics: String? = nil
    var driveFileID: String? = nil
    var driveAccountHash: String? = nil
    var fileSize: Int64? = nil
    var mimeType: String? = nil
    var fileExtension: String? = nil
    var driveModifiedTime: String? = nil
    var originalFilename: String? = nil
    var metadataOrigin: String? = nil

    // Stored source labels can be stale after metadata/import updates. An
    // attached file is authoritative and must never enter YouTube lookup.
    var playbackSource: MusicSource {
        if localFile != nil { return .local }
        if driveFileID != nil { return .drive }
        return source
    }
    var routedForPlayback: Track { var value = self; value.source = playbackSource; return value }

    var recordingKey: String { Self.normalized(artist) + "|" + Self.normalized(title) }
    static func normalized(_ value: String) -> String {
        value.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(String.init).joined()
    }
    static func unique(_ tracks: [Track]) -> [Track] {
        var ids = Set<String>(), recordings = Set<String>()
        return tracks.filter {
            guard !ids.contains($0.id), !recordings.contains($0.recordingKey) else { return false }
            ids.insert($0.id); recordings.insert($0.recordingKey); return true
        }
    }
}

struct MusicPlaylist: Identifiable, Codable, Hashable {
    var id = UUID().uuidString
    var name: String
    var trackIDs: [String] = []
    var coverFile: String? = nil
}

struct QueueEntry: Identifiable, Codable, Hashable {
    var id = UUID().uuidString
    var track: Track
}

enum RepeatMode: String, Codable { case off, all, one }

/// Stable occurrence IDs allow repeats without a drag changing the playing song.
struct PlaybackQueue: Codable, Equatable {
    var entries: [QueueEntry] = []
    var currentID: String? = nil
    var repeatMode: RepeatMode = .off
    var currentIndex: Int? { entries.firstIndex { $0.id == currentID } }
    var current: QueueEntry? { currentIndex.map { entries[$0] } }
    var upcoming: [QueueEntry] {
        guard let index = currentIndex, index + 1 < entries.count else { return [] }
        return Array(entries[(index + 1)...])
    }
    mutating func replace(_ tracks: [Track], at index: Int = 0) {
        entries = tracks.map { QueueEntry(track: $0) }
        currentID = entries.indices.contains(index) ? entries[index].id : entries.first?.id
    }
    mutating func move(from offsets: IndexSet, to destination: Int) {
        let moving = offsets.sorted().compactMap { entries.indices.contains($0) ? entries[$0] : nil }
        let removedBefore = offsets.filter { $0 < destination }.count
        entries = entries.enumerated().filter { !offsets.contains($0.offset) }.map(\.element)
        entries.insert(contentsOf: moving, at: max(0, min(entries.count, destination - removedBefore)))
    }
    mutating func remove(_ id: String) {
        guard let index = entries.firstIndex(where: { $0.id == id }) else { return }
        entries.remove(at: index)
        if currentID == id { currentID = entries.isEmpty ? nil : entries[min(index, entries.count - 1)].id }
    }
    func successor(after id: String, automatic: Bool) -> QueueEntry? {
        guard let index = entries.firstIndex(where: { $0.id == id }) else { return nil }
        if automatic && repeatMode == .one { return entries[index] }
        if index + 1 < entries.count { return entries[index + 1] }
        return repeatMode == .all ? entries.first : nil
    }
    mutating func shuffleUpcoming() {
        guard let index = currentIndex, index + 1 < entries.count else { return }
        entries.replaceSubrange((index + 1)..., with: entries[(index + 1)...].shuffled())
    }
}

struct LibrarySnapshot: Codable {
    var tracks: [Track] = []
    var playlists: [MusicPlaylist] = []
    var likedIDs: Set<String> = []
    var queue = PlaybackQueue()
    var position: Double = 0
    var autoplay: Bool = true
}

extension LibrarySnapshot {
    init(from decoder: Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        tracks = try fields.decodeIfPresent([Track].self, forKey: .tracks) ?? []
        playlists = try fields.decodeIfPresent([MusicPlaylist].self, forKey: .playlists) ?? []
        likedIDs = try fields.decodeIfPresent(Set<String>.self, forKey: .likedIDs) ?? []
        queue = try fields.decodeIfPresent(PlaybackQueue.self, forKey: .queue) ?? PlaybackQueue()
        position = try fields.decodeIfPresent(Double.self, forKey: .position) ?? 0
        autoplay = try fields.decodeIfPresent(Bool.self, forKey: .autoplay) ?? true
    }
}

struct VideoResult: Identifiable, Hashable {
    var id: String
    var title: String
    var channel: String
    var artwork: String { "https://i.ytimg.com/vi/\(id)/hqdefault.jpg" }
    var isVariant: Bool {
        title.range(of: #"\b(cover|live|remix|sped up|slowed|karaoke|reaction)\b|커버|라이브|歌ってみた"#,
                    options: [.regularExpression, .caseInsensitive]) != nil
    }
    func track(catalog: Track? = nil) -> Track {
        if var catalog, !isVariant {
            catalog.source = .youtube; catalog.videoID = id
            return catalog
        }
        return Track(id: "youtube:" + id, title: title, artist: channel, artwork: artwork, videoID: id)
    }
}

enum OdifyError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}
