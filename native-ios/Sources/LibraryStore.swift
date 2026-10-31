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
    @Published private(set) var homeError: String?
    @Published private(set) var listening: [ListenEvent] = []
    private var listeningMeter = ListeningMeter()
    typealias HomeProvider = @Sendable () async throws -> [Track]
    private let homeProvider: HomeProvider
    private var homeFetchedAt: Date?
    private struct HomeCache: Codable { var fetchedAt: Date; var tracks: [Track] }
    private var persistTask: Task<Void, Never>?
    private var recommendationTask: Task<Void, Never>?
    private var recommendationGeneration = UUID()
    private var subscriptions = Set<AnyCancellable>()
    private let preview: Bool

    init(documents: URL? = nil, preview: Bool = false,
         homeProvider: @escaping HomeProvider = { try await MusicCatalog.shared.chart(.korea) }) {
        let root = documents ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        self.documents = root; self.preview = preview; self.homeProvider = homeProvider
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
        if preview {
            loaded = PreviewLibrary.snapshot
            if let fixture = Bundle.main.url(forResource: "preview-silence", withExtension: "wav") {
                let folder = root.appendingPathComponent("OdifyPreview", isDirectory: true)
                try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let target = folder.appendingPathComponent("silence.wav")
                if !FileManager.default.fileExists(atPath: target.path) { try? FileManager.default.copyItem(at: fixture, to: target) }
            }
        }
        loaded.tracks = loaded.tracks.map(\.routedForPlayback)
        for index in loaded.queue.entries.indices { loaded.queue.entries[index].track = loaded.queue.entries[index].track.routedForPlayback }
        snapshot = loaded
        engine = PlaybackEngine(documents: root, queue: loaded.queue, position: loaded.position, autoplay: loaded.autoplay,
                                youtubeWebPlayer: preview ? nil : YouTubeWebPlayback())
        if !preview, let data = try? Data(contentsOf: root.appendingPathComponent("odify-listening-v1.json")), data.count < 500_000 {
            listening = (try? JSONDecoder().decode([ListenEvent].self, from: data)) ?? []
        }
        notice = loadError
        engine.onCheckpoint = { [weak self] in self?.save() }
        engine.onTrackMetadata = { [weak self] track in self?.remember(track) }
        engine.preferSource = { [weak self] track in self?.preferredTrack(track) ?? track }
        engine.onNeedMore = { [weak self] track in self?.prepareRecommendations(after: track) }
        engine.$queue.dropFirst().sink { [weak self] _ in self?.scheduleSave() }.store(in: &subscriptions)
        engine.clock.$position.dropFirst().sink { [weak self] _ in self?.observeListening() }.store(in: &subscriptions)
        if preview { chart = loaded.tracks }
        else if let data = try? Data(contentsOf: root.appendingPathComponent("odify-home-chart-v1.json")),
                data.count <= 500_000, let cached = try? JSONDecoder().decode(HomeCache.self, from: data) {
            chart = Array(cached.tracks.prefix(60)); homeFetchedAt = cached.fetchedAt
        }
    }
    var tracks: [Track] { snapshot.tracks }
    var isPreview: Bool { preview }
    var personalTracks: [Track] {
        tracks.filter { $0.driveFileID == nil || $0.driveAccountHash == DriveAccount.shared.accountHash }
    }
    var mixes: [PersonalMix] { PersonalMixes.build(tracks: personalTracks, liked: snapshot.likedIDs, events: listening) }
    var recentTracks: [Track] {
        let byKey = Dictionary(personalTracks.map { ($0.recordingKey, $0) }, uniquingKeysWith: { first, _ in first })
        return Track.unique(listening.sorted { $0.at > $1.at }.compactMap { byKey[$0.recordingKey] })
    }
    private func observeListening() {
        guard !preview, let track = engine.current else { return }
        if listeningMeter.sample(occurrence: engine.listeningOccurrence.uuidString, playing: engine.isPlaying, uptime: ProcessInfo.processInfo.systemUptime) {
            listening = Array((listening.filter { Date().timeIntervalSince($0.at) <= 60 * 86400 } + [ListenEvent(occurrence: engine.listeningOccurrence.uuidString, recordingKey: track.recordingKey, at: Date())]).suffix(1000))
            if let data = try? JSONEncoder().encode(listening) {
                try? data.write(to: documents.appendingPathComponent("odify-listening-v1.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
        }
    }
    var playlists: [MusicPlaylist] { snapshot.playlists }
    var liked: [Track] { tracks.filter { snapshot.likedIDs.contains($0.id) } }
    var artists: [Track] {
        var seen = Set<String>()
        return (personalTracks.isEmpty ? chart : personalTracks).filter { seen.insert(Track.normalized($0.artist)).inserted }
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
        value.metadataOrigin = stored.metadataOrigin
        value.artwork = stored.artwork; value.lyrics = stored.lyrics; value.duration = stored.duration
        return value
    }
    func mergeDriveLibrary(_ incoming: [Track]) {
        var values = snapshot.tracks
        var indices = Dictionary(values.enumerated().map { ($0.element.id, $0.offset) }, uniquingKeysWith: { first, _ in first })
        for fresh in incoming {
            if let index = indices[fresh.id] {
                let stored = values[index]
                var updated = fresh
                if stored.driveModifiedTime == fresh.driveModifiedTime {
                    updated.title = stored.title; updated.artist = stored.artist; updated.album = stored.album
                    updated.metadataOrigin = stored.metadataOrigin
                    updated.artwork = stored.artwork; updated.lyrics = stored.lyrics; updated.duration = stored.duration
                }
                values[index] = updated
            } else { indices[fresh.id] = values.count; values.append(fresh) }
        }
        snapshot.tracks = values; save()
    }
    func play(_ tracks: [Track], at index: Int = 0) {
        recommendationTask?.cancel(); recommendationTask = nil
        recommendationGeneration = UUID()
        let resolved = tracks.map(preferredTrack)
        resolved.forEach(remember); engine.play(resolved, at: index)
    }
    func preferredTrack(_ track: Track) -> Track {
        PlaybackSourcePreference.resolve(track, library: tracks, account: DriveAccount.shared.accountHash, documents: documents)
    }
    func syncMasterCatalog(account: String, refresh: Bool = false) async -> String? {
        guard !preview, tracks.contains(where: { $0.playbackSource != .youtube }) else { return nil }
        do {
            let rows = try await DriveClient.shared.masterRecordings(account: account, refresh: refresh)
            try Task.checkCancellation()
            guard DriveAccount.shared.accountHash == account, !rows.isEmpty else { return nil }
            let corrected = MasterCatalog.apply(rows, to: snapshot.tracks, account: account)
            let changed = zip(snapshot.tracks, corrected).filter { $0.0 != $0.1 }.map { $0.1 }
            snapshot.tracks = corrected
            engine.updateMetadata(changed)
            if !changed.isEmpty { save() }
            return "MASTER 곡 정보 \(changed.count)곡을 연결했습니다."
        } catch is CancellationError { return nil }
        catch { return "음원 연결은 유지했습니다. MASTER 곡 정보: " + error.localizedDescription }
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

    func loadHome(refresh: Bool = false) async {
        guard !preview, !homeLoading else { return }
        if !refresh, !chart.isEmpty, let date = homeFetchedAt, Date().timeIntervalSince(date) < 3600 { return }
        homeLoading = true; homeError = nil
        defer { homeLoading = false }
        do {
            let value = try await homeProvider()
            try Task.checkCancellation()
            guard !value.isEmpty else { throw OdifyError.message("추천 정보를 받지 못했습니다.") }
            let fetchedAt = Date()
            chart = Array(value.prefix(60)); homeFetchedAt = fetchedAt
            if let data = try? JSONEncoder().encode(HomeCache(fetchedAt: fetchedAt, tracks: chart)) {
                try? data.write(to: documents.appendingPathComponent("odify-home-chart-v1.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
        }
        catch is CancellationError { }
        catch {
            guard !Task.isCancelled else { return }
            if PlaybackEngine.isCancelledRequest(error) { return }
            engine.recordHomeFailure(error)
            homeError = chart.isEmpty ? "새 추천을 불러오지 못했습니다. 보관함의 음악은 계속 이용하실 수 있습니다." : "추천 새로고침이 지연되고 있습니다. 저장된 추천을 표시합니다."
        }
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
        var track = Track(id: "file:" + id, title: url.deletingPathExtension().lastPathComponent, artist: "알 수 없는 아티스트", source: source, localFile: relative, originalFilename: url.lastPathComponent)
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
        guard !preview, recommendationTask == nil, engine.autoplayEnabled, engine.canPrepareAutomatically else { return }
        let queueIdentity = engine.queue.entries.first?.id
        let request = UUID(); recommendationGeneration = request
        let background = AudioPreparationLease(name: "Odify prepare continuous queue") { [weak self] in
            guard let self, self.recommendationGeneration == request else { return }
            self.recommendationTask?.cancel(); self.engine.recommendationsFinished()
        }
        recommendationTask = Task { [weak self] in
            defer { background.end() }
            guard let self else { return }
            defer {
                if self.recommendationGeneration == request {
                    self.recommendationTask = nil; self.engine.recommendationsFinished()
                }
            }
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
                guard self.engine.autoplayEnabled, self.engine.canPrepareAutomatically, self.engine.queue.entries.first?.id == queueIdentity else { return }
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
        Track(id: "preview:1", title: "Moonlit Drive", artist: "Odify Sessions", album: "After Hours", source: .local, duration: 244),
        Track(id: "preview:2", title: "Soft Focus", artist: "Studio Glass", album: "Still / Moving", source: .drive, duration: 198),
        Track(id: "preview:3", title: "Blue Hour", artist: "Evening Collective", album: "Between Days", duration: 213),
        Track(id: "preview:4", title: "Slow Motion", artist: "Odify Sessions", album: "After Hours", duration: 227),
        Track(id: "preview:5", title: "A Little Closer", artist: "Studio Glass", album: "Still / Moving", duration: 206)
    ]
    static var snapshot: LibrarySnapshot {
        let fixtureTracks = tracks.map { track -> Track in var value = track; value.localFile = "OdifyPreview/silence.wav"; return value }
        var value = LibrarySnapshot(tracks: fixtureTracks,
                                    playlists: [MusicPlaylist(name: "After hours", trackIDs: tracks.map(\.id)), MusicPlaylist(name: "조용히 집중할 때", trackIDs: tracks.prefix(3).map(\.id))],
                                    likedIDs: Set(tracks.prefix(2).map(\.id)))
        value.queue.replace(fixtureTracks); value.position = 42
        return value
    }
}
