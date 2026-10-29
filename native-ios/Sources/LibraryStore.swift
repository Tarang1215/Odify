import AVFoundation
import Combine
import SwiftUI
import UniformTypeIdentifiers

@MainActor final class LibraryStore: ObservableObject {
    let documents: URL
    let engine: PlaybackEngine
    @Published private(set) var snapshot: LibrarySnapshot
    @Published var notice: String?
    @Published var importing = false
    @Published var chart: [Track] = []
    @Published var homeLoading = false
    private var persistTask: Task<Void, Never>?
    private var recommendationTask: Task<Void, Never>?
    private var recommendationGeneration = UUID()
    private var subscriptions = Set<AnyCancellable>()
    private let preview: Bool

    init(documents: URL? = nil, preview: Bool = false) {
        let root = documents ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        self.documents = root; self.preview = preview
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var loaded = LibrarySnapshot()
        let file = root.appendingPathComponent("odify-library-v1.json")
        var loadError: String?
        if FileManager.default.fileExists(atPath: file.path) {
            do { loaded = try JSONDecoder().decode(LibrarySnapshot.self, from: Data(contentsOf: file)) }
            catch {
                let backup = root.appendingPathComponent("odify-library-recovery-\(Int(Date().timeIntervalSince1970)).json")
                try? FileManager.default.copyItem(at: file, to: backup)
                loadError = "보관함을 읽지 못해 복구 사본을 남겼습니다. 파일 앱의 Odify 폴더를 확인해 주세요."
            }
        }
        if preview { loaded = PreviewLibrary.snapshot }
        snapshot = loaded
        engine = PlaybackEngine(documents: root, queue: loaded.queue, position: loaded.position, autoplay: loaded.autoplay)
        notice = loadError
        engine.onCheckpoint = { [weak self] in self?.save() }
        engine.onTrackMetadata = { [weak self] track in self?.remember(track) }
        engine.onNeedMore = { [weak self] track in self?.prepareRecommendations(after: track) }
        engine.$queue.dropFirst().sink { [weak self] _ in self?.scheduleSave() }.store(in: &subscriptions)
        if preview { chart = loaded.tracks }
    }
    var tracks: [Track] { snapshot.tracks }
    var playlists: [MusicPlaylist] { snapshot.playlists }
    var liked: [Track] { tracks.filter { snapshot.likedIDs.contains($0.id) } }
    var artists: [Track] {
        var seen = Set<String>()
        return (tracks + chart).filter { seen.insert(Track.normalized($0.artist)).inserted }
    }
    func tracks(in playlist: MusicPlaylist) -> [Track] {
        let byID = Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return playlist.trackIDs.compactMap { byID[$0] }
    }
    func remember(_ track: Track) {
        if let index = snapshot.tracks.firstIndex(where: { $0.id == track.id }) { snapshot.tracks[index] = track }
        else { snapshot.tracks.append(track) }
        scheduleSave()
    }
    func driveTrack(_ fresh: Track) -> Track {
        guard let stored = tracks.first(where: { $0.id == fresh.id }), stored.driveModifiedTime == fresh.driveModifiedTime else { return fresh }
        var value = fresh
        value.title = stored.title; value.artist = stored.artist; value.album = stored.album
        value.artwork = stored.artwork; value.lyrics = stored.lyrics; value.duration = stored.duration
        return value
    }
    func play(_ tracks: [Track], at index: Int = 0) {
        recommendationTask?.cancel(); recommendationTask = nil
        recommendationGeneration = UUID()
        tracks.forEach(remember); engine.play(tracks, at: index)
    }
    func toggleLike(_ track: Track) {
        remember(track)
        if !snapshot.likedIDs.insert(track.id).inserted { snapshot.likedIDs.remove(track.id) }
        scheduleSave()
    }
    @discardableResult func createPlaylist(_ name: String) -> MusicPlaylist {
        let playlist = MusicPlaylist(name: name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "새 플레이리스트" : String(name.prefix(100)))
        snapshot.playlists.append(playlist); scheduleSave(); return playlist
    }
    func rename(_ playlist: MusicPlaylist, to name: String) {
        guard let index = snapshot.playlists.firstIndex(where: { $0.id == playlist.id }), !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        snapshot.playlists[index].name = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(100)); scheduleSave()
    }
    func delete(_ playlist: MusicPlaylist) {
        snapshot.playlists.removeAll { $0.id == playlist.id }; scheduleSave()
    }
    func add(_ track: Track, to playlist: MusicPlaylist) {
        remember(track)
        guard let index = snapshot.playlists.firstIndex(where: { $0.id == playlist.id }) else { return }
        if !snapshot.playlists[index].trackIDs.contains(track.id) { snapshot.playlists[index].trackIDs.append(track.id) }
        scheduleSave()
    }
    func remove(_ track: Track, from playlist: MusicPlaylist) {
        guard let index = snapshot.playlists.firstIndex(where: { $0.id == playlist.id }) else { return }
        snapshot.playlists[index].trackIDs.removeAll { $0 == track.id }; scheduleSave()
    }
    func saveLyrics(_ text: String, for track: Track) {
        var updated = track; updated.lyrics = text.trimmingCharacters(in: .whitespacesAndNewlines)
        remember(updated); engine.update(updated); save()
    }
    func setCover(_ data: Data, for playlist: MusicPlaylist) async throws {
        guard let jpeg = await ArtworkLoader.shared.coverJPEG(data) else { throw OdifyError.message("지원하는 크기의 이미지를 선택해 주세요.") }
        guard let index = snapshot.playlists.firstIndex(where: { $0.id == playlist.id }) else { return }
        let folder = documents.appendingPathComponent("Artwork", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let relative = "Artwork/\(playlist.id)-\(UUID().uuidString).jpg"
        try jpeg.write(to: documents.appendingPathComponent(relative), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        awaitlessEvictArtwork(relative)
        snapshot.playlists[index].coverFile = relative; scheduleSave()
    }
    private func awaitlessEvictArtwork(_ relative: String) { Task { await ArtworkLoader.shared.evict(relative) } }

    func loadHome() async {
        guard !preview, chart.isEmpty, !homeLoading else { return }
        homeLoading = true
        defer { homeLoading = false }
        do { chart = try await MusicCatalog.shared.chart(.korea) }
        catch is CancellationError { }
        catch { notice = error.localizedDescription }
    }
    func importFiles(_ urls: [URL], source: MusicSource) async {
        guard !importing else { return }
        importing = true
        defer { importing = false }
        var failures = 0
        for url in urls {
            do {
                let track = try await Self.importAudio(url, source: source, documents: documents)
                remember(track)
            } catch { failures += 1; notice = error.localizedDescription }
        }
        save()
        if failures == 0 { notice = "\(urls.count)곡을 보관함에 가져왔습니다." }
    }
    private static func importAudio(_ url: URL, source: MusicSource, documents: URL) async throws -> Track {
        let allowed = Set(["mp3", "m4a", "aac", "wav", "aiff", "aif", "flac", "mp4", "caf", "alac"])
        guard allowed.contains(url.pathExtension.lowercased()) else { throw OdifyError.message("지원하는 오디오 파일을 선택해 주세요.") }
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        let id = UUID().uuidString
        let relative = "Music/\(id).\(url.pathExtension.lowercased())"
        let destination = documents.appendingPathComponent(relative)
        let fileURL = url
        try await Task.detached(priority: .userInitiated) {
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            var coordinationError: NSError?
            var readError: Error?
            NSFileCoordinator().coordinate(readingItemAt: fileURL, options: [], error: &coordinationError) { coordinated in
                do { try FileManager.default.copyItem(at: coordinated, to: destination) } catch { readError = error }
            }
            if let error = coordinationError { throw error }
            if let error = readError { throw error }
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: destination.path)
        }.value
        let asset = AVURLAsset(url: destination)
        var track = Track(id: "file:" + id, title: url.deletingPathExtension().lastPathComponent, artist: "알 수 없는 아티스트", source: source, localFile: relative)
        if let duration = try? await asset.load(.duration), duration.seconds.isFinite { track.duration = duration.seconds }
        if let metadata = try? await asset.load(.commonMetadata) {
            for item in metadata {
                if item.commonKey == .commonKeyTitle, let value = try? await item.load(.stringValue), !value.isEmpty { track.title = value }
                if item.commonKey == .commonKeyArtist, let value = try? await item.load(.stringValue), !value.isEmpty { track.artist = value }
                if item.commonKey == .commonKeyAlbumName, let value = try? await item.load(.stringValue) { track.album = value }
                if item.commonKey == .commonKeyArtwork, let data = try? await item.load(.dataValue), let image = UIImage(data: data),
                   let jpeg = image.jpegData(compressionQuality: 0.85) {
                    let artwork = documents.appendingPathComponent("Artwork", isDirectory: true)
                    try? FileManager.default.createDirectory(at: artwork, withIntermediateDirectories: true)
                    let name = "Artwork/\(id).jpg"
                    if (try? jpeg.write(to: documents.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil { track.artwork = name }
                }
            }
        }
        return track
    }
    func scheduleSave() {
        persistTask?.cancel()
        persistTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(350))
            if !Task.isCancelled { self?.save() }
        }
    }
    private func prepareRecommendations(after seed: Track) {
        guard !preview, recommendationTask == nil, engine.autoplayEnabled else { return }
        let queueIdentity = engine.queue.entries.first?.id
        let request = UUID(); recommendationGeneration = request
        recommendationTask = Task { [weak self] in
            guard let self else { return }
            defer { if self.recommendationGeneration == request { self.recommendationTask = nil } }
            do {
                let candidates: [Track]
                if seed.source != .youtube {
                    // Private Drive/local tags never become automatic external
                    // discovery queries. Continue from the on-device library.
                    let available = self.tracks.filter { $0.driveFileID == nil || $0.driveAccountHash == DriveAccount.shared.accountHash }
                    candidates = available.filter { Track.normalized($0.artist) == Track.normalized(seed.artist) }
                        + available.filter { Track.normalized($0.artist) != Track.normalized(seed.artist) }
                } else {
                    let official = try await MusicCatalog.shared.artist(seed)
                    if official.isEmpty {
                        candidates = try await YouTubeCatalog.shared.search(seed.artist + " audio").filter { !$0.isVariant }.map { $0.track() }
                    } else { candidates = official }
                }
                try Task.checkCancellation()
                guard self.engine.autoplayEnabled, self.engine.queue.entries.first?.id == queueIdentity else { return }
                let existing = Set(self.engine.queue.entries.map { $0.track.recordingKey })
                let additions = Array(Track.unique(candidates).filter { !existing.contains($0.recordingKey) }.prefix(5))
                self.engine.appendRecommendations(additions)
                additions.forEach(self.remember)
            } catch is CancellationError { }
            catch {
                // Keep the already prepared queue playing. Discovery failure
                // must never stop the current song or replace a user's order.
            }
        }
    }
    func save() {
        guard !preview else { return }
        snapshot.queue = engine.queue; snapshot.position = engine.clock.position
        snapshot.autoplay = engine.autoplayEnabled
        do {
            let data = try JSONEncoder().encode(snapshot)
            try data.write(to: documents.appendingPathComponent("odify-library-v1.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch { notice = "보관함 저장 실패: \(error.localizedDescription)" }
    }
}

/// Opt-in simulator presentation fixture. Never loaded during ordinary launch.
enum PreviewLibrary {
    static let tracks = [
        Track(id: "preview:1", title: "Moonlit Drive", artist: "Odify Sessions", album: "After Hours", source: .local),
        Track(id: "preview:2", title: "Soft Focus", artist: "Studio Glass", album: "Still / Moving", source: .drive),
        Track(id: "preview:3", title: "Blue Hour", artist: "Evening Collective", album: "Between Days"),
        Track(id: "preview:4", title: "Slow Motion", artist: "Odify Sessions", album: "After Hours"),
        Track(id: "preview:5", title: "A Little Closer", artist: "Studio Glass", album: "Still / Moving")
    ]
    static var snapshot: LibrarySnapshot {
        var value = LibrarySnapshot(tracks: tracks,
                                    playlists: [MusicPlaylist(name: "After hours", trackIDs: tracks.map(\.id)), MusicPlaylist(name: "조용히 집중할 때", trackIDs: tracks.prefix(3).map(\.id))],
                                    likedIDs: Set(tracks.prefix(2).map(\.id)))
        value.queue.replace(tracks); value.position = 42
        return value
    }
}
