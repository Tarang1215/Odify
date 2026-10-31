import AVFoundation
import Combine
import MediaPlayer
import OdifyStreaming
import OSLog
import UIKit

/// A finite preparation window, never an artificial silent-audio keepalive.
@MainActor final class AudioPreparationLease {
    private var identifier: UIBackgroundTaskIdentifier = .invalid
    init(name: String, expired: @escaping @MainActor () -> Void) {
        identifier = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            Task { @MainActor in expired(); self?.end() }
        }
    }
    func end() {
        guard identifier != .invalid else { return }
        let value = identifier; identifier = .invalid
        UIApplication.shared.endBackgroundTask(value)
    }
}

@MainActor final class PlaybackClock: ObservableObject {
    @Published var position: Double = 0
    @Published var duration: Double = 0
}

/// AVQueuePlayer owns prepared successors so a screen-off transition does not
/// depend on waking a hidden web view or completing a request after silence.
@MainActor final class PlaybackEngine: ObservableObject {
    @Published private(set) var queue: PlaybackQueue
    @Published private(set) var isPlaying = false
    @Published private(set) var isBuffering = false
    @Published private(set) var error: String?
    @Published private(set) var playbackRestricted = false
    @Published private(set) var usingYouTubeWebPlayer = false
    var youtubeWebHost: YouTubeWebPlayback? { webPlayback as? YouTubeWebPlayback }
    @Published private(set) var autoplayEnabled: Bool
    @Published private(set) var tint = UIColor(red: 0.35, green: 0.56, blue: 0.48, alpha: 1)
    let clock = PlaybackClock()
    private(set) var player = AVQueuePlayer()
    var current: Track? { queue.current?.track }
    var onCheckpoint: (() -> Void)?
    var onNeedMore: ((Track) -> Void)?
    var onTrackMetadata: ((Track) -> Void)?
    var preferSource: ((Track) -> Track)?
    var diagnostics: [String] = []
    private(set) var listeningOccurrence = UUID()
    private let documents: URL
    private let trace: PlaybackTrace
    typealias SourceResolver = @Sendable (Track, URL, Bool) async throws -> URL
    private let resolveSource: SourceResolver
    private let youtubeSession: URLSession?
    typealias VideoResolver = @Sendable (Track) async throws -> String
    private let webPlayback: YouTubeWebPlaying?
    private let resolveWebVideo: VideoResolver
    private let driveClient: DriveClient
    private var itemIDs: [ObjectIdentifier: String] = [:]
    private var itemSourceKeys: [ObjectIdentifier: UUID] = [:]
    private var activeItemKey: ObjectIdentifier?
    private var observations: [NSKeyValueObservation] = []
    private var itemObservations: [ObjectIdentifier: NSKeyValueObservation] = [:]
    private var driveLoaders: [ObjectIdentifier: DriveResourceLoader] = [:]
    private var notifications: [NSObjectProtocol] = []
    private var timeObserver: Any?
    private var startTask: Task<Void, Never>?
    private var prefetchTask: Task<Void, Never>?
    private var artworkTask: Task<Void, Never>?
    private var artworkIdentity: String?
    private var stallTask: Task<Void, Never>?
    private var generation = UUID()
    private var prefetchGeneration = UUID()
    private var wantsPlayback = false
    private var interrupted = false
    private var savedPosition: Double
    private var lastCheckpoint = Date.distantPast
    private var failedIDs = Set<String>()
    private var retries = Set<String>()
    private var remoteTargets: [(MPRemoteCommand, Any)] = []
    private var metadataTask: Task<Void, Never>?
    private var metadataRead = Set<String>()
    private var waitingForRecommendations = false
    private var prefetchDenied = false
    private var deniedEntries: [String: Error] = [:]
    var canPrepareAutomatically: Bool { !prefetchDenied }
    var externalYouTubeURL: URL? {
        current.flatMap(Self.youtubeURL)
    }
    var restrictedYouTubeURL: URL? {
        if let entry = queue.current, deniedEntries[entry.id] != nil { return Self.youtubeURL(entry.track) }
        return queue.entries.first(where: { deniedEntries[$0.id] != nil }).flatMap { Self.youtubeURL($0.track) }
    }
    private static func youtubeURL(_ track: Track) -> URL? {
        guard track.source == .youtube else { return nil }
        if let id = track.videoID, id.range(of: #"^[A-Za-z0-9_-]{11}$"#, options: .regularExpression) != nil {
            return MusicHTTP.url("https://www.youtube.com/watch", ["v": id])
        }
        return MusicHTTP.url("https://www.youtube.com/results", ["search_query": String((track.artist + " " + track.title).prefix(160))])
    }
    private static let log = Logger(subsystem: "com.odify.music.ios", category: "Playback")

    init(documents: URL, queue: PlaybackQueue = PlaybackQueue(), position: Double = 0, autoplay: Bool = true, driveClient: DriveClient = .shared,
         youtubeSession: URLSession? = nil, youtubeWebPlayer: YouTubeWebPlaying? = nil,
         webVideoResolver: @escaping VideoResolver = { track in
             if let id = track.videoID, YouTubeWebPlayback.validID(id) { return id }
             throw OdifyError.message("웹 플레이어에서 영상을 연결합니다.")
         },
         sourceResolver: @escaping SourceResolver = { try await StreamResolver.shared.url(for: $0, documents: $1, refresh: $2) }) {
        var restored = queue
        for index in restored.entries.indices { restored.entries[index].track = restored.entries[index].track.routedForPlayback }
        self.documents = documents; self.queue = restored; savedPosition = position
        trace = PlaybackTrace(documents: documents)
        resolveSource = sourceResolver; self.youtubeSession = youtubeSession
        webPlayback = youtubeWebPlayer; resolveWebVideo = webVideoResolver
        self.driveClient = driveClient
        autoplayEnabled = autoplay
        clock.position = position
        clock.duration = queue.current?.track.duration ?? 0
        configurePlayer()
        webPlayback?.onEvent = { [weak self] request, event in self?.webEvent(request, event: event) }
        let center = NotificationCenter.default
        notifications.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] notification in
            guard let item = notification.object as? AVPlayerItem else { return }
            Task { @MainActor in self?.ended(item) }
        })
        notifications.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: nil, queue: .main) { [weak self] notification in
            guard let item = notification.object as? AVPlayerItem else { return }
            Task { @MainActor in self?.failed(item) }
        })
        notifications.append(center.addObserver(forName: .AVPlayerItemPlaybackStalled, object: nil, queue: .main) { [weak self] notification in
            guard let item = notification.object as? AVPlayerItem else { return }
            Task { @MainActor in
                guard let self, self.player.currentItem === item else { return }
                self.monitorStall()
            }
        })
        notifications.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.uintValue
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.uintValue ?? 0
            Task { @MainActor in
                guard let self else { return }
                if type == AVAudioSession.InterruptionType.began.rawValue {
                    self.interrupted = self.wantsPlayback; self.wantsPlayback = false; self.player.pause()
                    self.stallTask?.cancel(); self.onCheckpoint?()
                    self.webPlayback?.pause()
                } else if self.interrupted {
                    self.interrupted = false
                    if AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume) { self.resume() }
                }
            }
        })
        notifications.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.uintValue
            if reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue { Task { @MainActor in self?.pause() } }
        })
        notifications.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.webPlayback?.setActive(false); self?.onCheckpoint?() }
        })
        notifications.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.webPlayback?.setActive(true)
            }
        })
        configureRemoteCommands()
    }

    private func configurePlayer() {
        player.audiovisualBackgroundPlaybackPolicy = .continuesIfPossible
        player.automaticallyWaitsToMinimizeStalling = true
        player.actionAtItemEnd = .advance
        observations.append(player.observe(\.currentItem, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in self?.currentItemChanged() }
        })
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in
                guard let self else { return }
                guard !self.usingYouTubeWebPlayer else { return }
                self.isPlaying = self.wantsPlayback && self.player.timeControlStatus == .playing
                self.isBuffering = self.wantsPlayback && self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate
                if self.isPlaying { self.trace.record("playing", source: self.current?.source) }
                if self.isBuffering { self.monitorStall() }
                else { self.stallTask?.cancel(); self.stallTask = nil }
                self.publishNowPlaying()
            }
        })
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
                guard !self.usingYouTubeWebPlayer else { return }
                let position = time.seconds, duration = self.player.currentItem?.duration.seconds ?? 0
                if position.isFinite { self.clock.position = max(0, position) }
                if duration.isFinite && duration > 0 && abs(self.clock.duration - duration) > 0.1 {
                    self.clock.duration = duration; self.publishNowPlaying()
                }
                if Date().timeIntervalSince(self.lastCheckpoint) > 10 {
                    self.lastCheckpoint = Date(); self.onCheckpoint?(); self.publishNowPlaying()
                }
            }
        }
    }
    private func replaceFailedPlayer() {
        let volume = player.volume
        observations.forEach { $0.invalidate() }; observations.removeAll()
        if let timeObserver { player.removeTimeObserver(timeObserver); self.timeObserver = nil }
        player.pause(); player.removeAllItems()
        player = AVQueuePlayer(); player.volume = volume
        configurePlayer()
    }

    func play(_ tracks: [Track], at index: Int = 0) {
        guard !tracks.isEmpty else { return }
        prefetchDenied = false; deniedEntries.removeAll(); playbackRestricted = false
        queue.replace(tracks.map(\.routedForPlayback), at: index); failedIDs.removeAll(); retries.removeAll()
        waitingForRecommendations = false; savedPosition = 0; startCurrent()
    }
    func resume() {
        guard queue.current != nil else { return }
        if prefetchDenied {
            failedIDs.subtract(deniedEntries.keys)
            prefetchDenied = false; deniedEntries.removeAll(); playbackRestricted = false
            error = nil
        }
        wantsPlayback = true
        if usingYouTubeWebPlayer {
            if error != nil { startCurrent(resume: savedPosition); return }
            webPlayback?.play(); return
        }
        if player.status == .failed || player.currentItem == nil || player.currentItem?.status == .failed { startCurrent(resume: savedPosition, refresh: true); return }
        do { try activateAudio(); player.play(); rebuildUpcoming(); publishNowPlaying() }
        catch { showError(error) }
    }
    func pause() {
        wantsPlayback = false; interrupted = false; waitingForRecommendations = false
        stallTask?.cancel(); stallTask = nil
        player.pause(); isPlaying = false; isBuffering = false
        if usingYouTubeWebPlayer { webPlayback?.pause() }
        savedPosition = clock.position; onCheckpoint?(); publishNowPlaying()
    }
    func toggle() { if wantsPlayback { pause() } else { resume() } }
    func adjustVolume(_ delta: Float) {
        player.volume = max(0, min(1, player.volume + delta))
        webPlayback?.setVolume(player.volume)
    }
    func seek(_ seconds: Double) {
        guard seconds.isFinite else { return }
        let position = max(0, min(clock.duration > 0 ? clock.duration : seconds, seconds))
        savedPosition = position; clock.position = position
        if usingYouTubeWebPlayer { webPlayback?.seek(position); onCheckpoint?(); publishNowPlaying(); return }
        player.seek(to: CMTime(seconds: position, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        onCheckpoint?(); publishNowPlaying()
    }
    func next() {
        guard let id = queue.currentID else { return }
        guard let next = queue.successor(after: id, automatic: false) else { pause(); return }
        jump(next.id)
    }
    func previous() {
        if clock.position > 3 { seek(0); return }
        guard let index = queue.currentIndex, index > 0 else { seek(0); return }
        jump(queue.entries[index - 1].id)
    }
    func jump(_ id: String, userInitiated: Bool = true) {
        guard queue.entries.contains(where: { $0.id == id }) else { return }
        if userInitiated { prefetchDenied = false; deniedEntries.removeAll(); playbackRestricted = false }
        waitingForRecommendations = false; queue.currentID = id; savedPosition = 0; startCurrent()
    }
    func move(_ offsets: IndexSet, to destination: Int) {
        queue.move(from: offsets, to: destination); rebuildUpcoming(); onCheckpoint?()
    }
    func remove(_ id: String) {
        let wasCurrent = queue.currentID == id
        queue.remove(id)
        if wasCurrent { startCurrent() } else { rebuildUpcoming() }
        onCheckpoint?()
    }
    func enqueue(_ track: Track) {
        queue.entries.append(QueueEntry(track: (preferSource?(track) ?? track).routedForPlayback))
        if queue.currentID == nil { queue.currentID = queue.entries.first?.id }
        rebuildUpcoming(); onCheckpoint?()
    }
    func shuffle() { queue.shuffleUpcoming(); rebuildUpcoming(); onCheckpoint?() }
    func cycleRepeat() {
        switch queue.repeatMode { case .off: queue.repeatMode = .all; case .all: queue.repeatMode = .one; case .one: queue.repeatMode = .off }
        rebuildUpcoming(); onCheckpoint?()
    }
    func update(_ track: Track) {
        for index in queue.entries.indices where queue.entries[index].track.id == track.id { queue.entries[index].track = track }
        publishNowPlaying(resetArtwork: current?.id == track.id); onCheckpoint?()
    }
    func clearError() { error = nil }
    func recordHomeFailure(_ failure: Error) { trace.record("home-catalog-failed", source: nil, error: failure) }
    func toggleAutoplay() {
        autoplayEnabled.toggle(); onCheckpoint?()
        if autoplayEnabled, let track = current, queue.upcoming.count < 3 { onNeedMore?(track) }
    }
    func appendRecommendations(_ tracks: [Track]) {
        guard !tracks.isEmpty else { return }
        queue.entries.append(contentsOf: tracks.map { QueueEntry(track: (preferSource?($0) ?? $0).routedForPlayback) })
        if waitingForRecommendations, wantsPlayback, let id = queue.currentID,
           let next = queue.successor(after: id, automatic: false) {
            jump(next.id, userInitiated: false); return
        }
        rebuildUpcoming(); onCheckpoint?()
    }
    func recommendationsFinished() {
        guard waitingForRecommendations else { return }
        waitingForRecommendations = false; wantsPlayback = false; isBuffering = false
        onCheckpoint?(); publishNowPlaying()
    }
    var preparedEntryIDs: [String] { player.items().compactMap { itemIDs[ObjectIdentifier($0)] } }
    func shutdown() {
        pause(); startTask?.cancel(); prefetchTask?.cancel(); artworkTask?.cancel()
        metadataTask?.cancel(); stallTask?.cancel()
        observations.forEach { $0.invalidate() }; observations.removeAll()
        itemObservations.values.forEach { $0.invalidate() }; itemObservations.removeAll()
        driveLoaders.values.forEach { $0.cancel() }; driveLoaders.removeAll()
        notifications.forEach { NotificationCenter.default.removeObserver($0) }; notifications.removeAll()
        if let timeObserver { player.removeTimeObserver(timeObserver); self.timeObserver = nil }
        remoteTargets.forEach { $0.0.removeTarget($0.1) }; remoteTargets.removeAll()
        player.removeAllItems()
        webPlayback?.stop(); webPlayback?.onEvent = nil
    }

    private func startCurrent(resume: Double = 0, refresh: Bool = false) {
        listeningOccurrence = UUID()
        if let index = queue.currentIndex, let preferSource {
            let track = queue.entries[index].track
            let resolved = preferSource(track)
            if resolved != track { queue.entries[index].track = resolved }
        }
        if let index = queue.currentIndex { queue.entries[index].track = queue.entries[index].track.routedForPlayback }
        startTask?.cancel(); prefetchTask?.cancel(); stallTask?.cancel(); stallTask = nil
        generation = UUID(); let request = generation
        // AVPlayer.Status.failed is terminal for that player instance.
        if player.status == .failed { replaceFailedPlayer() }
        player.pause(); player.removeAllItems(); itemIDs.removeAll(); itemSourceKeys.removeAll()
        itemObservations.values.forEach { $0.invalidate() }; itemObservations.removeAll()
        driveLoaders.values.forEach { $0.cancel() }; driveLoaders.removeAll()
        if usingYouTubeWebPlayer, queue.current?.track.source == .youtube { webPlayback?.pause() }
        else { webPlayback?.stop() }
        usingYouTubeWebPlayer = false
        guard let entry = queue.current else { wantsPlayback = false; isPlaying = false; return }
        if let failure = deniedEntries[entry.id] { stopForTerminalFailure(entry.id, failure: failure); return }
        trace.record("prepare", source: entry.track.source)
        wantsPlayback = true; isPlaying = false; isBuffering = true; error = nil
        clock.position = resume; clock.duration = entry.track.duration
        if entry.track.source == .youtube, webPlayback != nil {
            usingYouTubeWebPlayer = true
            publishNowPlaying(resetArtwork: true)
            let background = AudioPreparationLease(name: "Odify prepare web playback") { [weak self] in
                guard let self, self.generation == request else { return }
                self.startTask?.cancel(); self.wantsPlayback = false
                self.showError(YouTubeWebFailure(code: -1001))
            }
            startTask = Task { [weak self] in
                defer { background.end() }
                guard let self else { return }
                do {
                    let video = try await self.resolveWebVideo(entry.track)
                    try Task.checkCancellation()
                    guard self.generation == request else { return }
                    guard YouTubeWebPlayback.validID(video) else { throw YouTubeWebFailure(code: 2) }
                    try self.activateAudio()
                    self.trace.record("web-player-load", source: .youtube)
                    self.webPlayback?.load(videoID: video, request: request, position: resume, autoplay: self.wantsPlayback)
                    if !self.wantsPlayback { self.webPlayback?.pause() }
                } catch is CancellationError { }
                catch {
                    guard !Task.isCancelled, self.generation == request else { return }
                    self.trace.record("web-video-lookup", source: .youtube)
                    do { try self.activateAudio() } catch { self.wantsPlayback = false; self.showError(error); return }
                    self.webPlayback?.search(track: entry.track, request: request)
                }
            }
            return
        }
        // Resolve while the app still has its current background-audio lifetime.
        let background = AudioPreparationLease(name: "Odify prepare audio") { [weak self] in
            guard let self, self.generation == request else { return }
            self.startTask?.cancel(); self.wantsPlayback = false
            self.showError(OdifyError.message("음원 준비 시간이 초과되었습니다. 앱을 열고 다시 시도해 주세요."))
        }
        startTask = Task { [weak self] in
            defer { background.end() }
            guard let self else { return }
            do {
                let url = try await self.resolveSource(entry.track, self.documents, refresh)
                try Task.checkCancellation()
                guard self.generation == request else { return }
                self.trace.record("source-resolved", source: entry.track.source)
                if self.wantsPlayback { try self.activateAudio() }
                let item = try await self.makeItem(url, entry: entry)
                guard !Task.isCancelled, self.generation == request else { self.discard(item); return }
                self.activeItemKey = ObjectIdentifier(item)
                self.player.insert(item, after: nil)
                if resume > 0 { await self.player.seek(to: CMTime(seconds: resume, preferredTimescale: 600)) }
                guard self.generation == request else { return }
                if self.wantsPlayback { self.player.play() }
                self.rebuildUpcoming(); self.onCheckpoint?()
            } catch is CancellationError { }
            catch {
                guard !Task.isCancelled, self.generation == request else { return }
                if entry.track.source == .youtube {
                    // Resolution already performs its bounded transport retry.
                    // Do not multiply that timeout by retrying the entire
                    // selection or silently replacing it with another song.
                    if Self.isTerminalNetworkFailure(error) { self.stopForTerminalFailure(entry.id, failure: error) }
                    else { self.wantsPlayback = false; self.showError(error); self.onCheckpoint?() }
                } else { self.recover(entry.id, failure: error) }
            }
        }
    }
    private func makeItem(_ url: URL, entry: QueueEntry, prepareAhead: Bool = false) async throws -> AVPlayerItem {
        let asset: AVURLAsset
        let loader: DriveResourceLoader?
        var sourceKey: UUID?
        if !url.isFileURL, entry.track.driveFileID != nil {
            asset = AVURLAsset(url: url)
            let delegate = DriveResourceLoader(track: entry.track, client: driveClient)
            asset.resourceLoader.setDelegate(delegate, queue: delegate.queue)
            loader = delegate
        } else if YouTubeByteSource.trusted(url) {
            let source = try await YouTubeByteSource.open(url, session: youtubeSession)
            do {
                if prepareAhead {
                    try await source.warmStart()
                    trace.record("next-audio-warmed", source: .youtube)
                }
                try Task.checkCancellation()
            }
            catch { await source.close(); throw error }
            asset = AVURLAsset(url: URL(string: "odify-audio://media/\(UUID().uuidString).m4a")!)
            let sourceToken = UUID(); sourceKey = sourceToken
            let delegate = DriveResourceLoader(length: source.length, contentType: "public.mpeg-4-audio", chunkSize: 131_072, onClose: { Task { await source.close() } }) { [weak self] start, count in
                do { return try await source.range(start: start, length: count) }
                catch {
                    if !Self.isCancelledRequest(error) {
                        await self?.rangeFailed(entry.id, sourceKey: sourceToken, failure: error)
                    }
                    throw error
                }
            }
            asset.resourceLoader.setDelegate(delegate, queue: delegate.queue); loader = delegate
            Self.log.notice("[OdifyPlayback] youtube-audio-range-verified")
            trace.record("audio-range-verified", source: .youtube)
        } else { asset = AVURLAsset(url: url); loader = nil }
        var retained = false
        defer { if !retained { loader?.cancel() } }
        // A remote/custom-scheme asset can report isPlayable=false before its
        // actual media request has been established (including guest runtimes).
        // Only preflight local files. Streaming readiness belongs to AVPlayerItem
        // status, whose concrete network/decoder failure can then be surfaced.
        if url.isFileURL {
            guard try await asset.load(.isPlayable) else { throw OdifyError.message("손상되었거나 지원하지 않는 파일입니다: \(entry.track.title)") }
        }
        try Task.checkCancellation()
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 30
        item.canUseNetworkResourcesForLiveStreamingWhilePaused = true
        itemIDs[ObjectIdentifier(item)] = entry.id
        if let sourceKey { itemSourceKeys[ObjectIdentifier(item)] = sourceKey }
        if let loader { driveLoaders[ObjectIdentifier(item)] = loader }
        retained = true
        itemObservations[ObjectIdentifier(item)] = item.observe(\.status, options: [.new]) { [weak self, weak item] _, _ in
            Task { @MainActor in
                if let item, item.status == .failed { self?.failed(item) }
                else if let item, item.status == .readyToPlay {
                    self?.trace.record("item-ready", source: entry.track.source)
                    self?.readDriveMetadata(item)
                }
            }
        }
        return item
    }
    private func rebuildUpcoming() {
        prefetchTask?.cancel(); prefetchGeneration = UUID(); let request = prefetchGeneration
        guard !prefetchDenied else { return }
        guard !usingYouTubeWebPlayer else { return }
        guard let currentItem = player.currentItem, let currentID = itemIDs[ObjectIdentifier(currentItem)] else { return }
        var prepared: [QueueEntry] = [], cursor = currentID
        // Look beyond broken entries; stop after two usable successors have
        // actually been inserted, rather than examining only two queue slots.
        for _ in 0..<max(2, queue.entries.count) {
            guard let entry = queue.successor(after: cursor, automatic: true) else { break }
            if webPlayback != nil, entry.track.playbackSource == .youtube { break }
            if !failedIDs.contains(entry.id) { prepared.append(entry) }
            cursor = entry.id
        }
        var retained = 0
        var lastPrepared = currentItem
        let existing = Array(player.items().dropFirst())
        for item in existing {
            if retained < prepared.count, itemIDs[ObjectIdentifier(item)] == prepared[retained].id {
                lastPrepared = item; retained += 1
            } else { break }
        }
        for item in existing.dropFirst(retained) {
            player.remove(item); itemIDs.removeValue(forKey: ObjectIdentifier(item))
            itemSourceKeys.removeValue(forKey: ObjectIdentifier(item))
            itemObservations.removeValue(forKey: ObjectIdentifier(item))?.invalidate()
            driveLoaders.removeValue(forKey: ObjectIdentifier(item))?.cancel()
        }
        let missing = Array(prepared.dropFirst(retained))
        guard retained < 2, !missing.isEmpty else { return }
        let background = AudioPreparationLease(name: "Odify buffer next songs") { [weak self] in
            guard let self, self.prefetchGeneration == request else { return }
            self.prefetchTask?.cancel()
        }
        prefetchTask = Task { [weak self] in
            defer {
                background.end()
                if self?.prefetchGeneration == request { self?.prefetchTask = nil }
            }
            guard let self else { return }
            var lastItem = lastPrepared
            var count = retained
            for entry in missing {
                do {
                    let url = try await self.resolveSource(entry.track, self.documents, false)
                    try Task.checkCancellation()
                    guard self.prefetchGeneration == request, self.player.items().contains(where: { $0 === lastItem }) else { return }
                    let item = try await self.makeItem(url, entry: entry, prepareAhead: true)
                    guard !Task.isCancelled, self.prefetchGeneration == request,
                          self.player.items().contains(where: { $0 === lastItem }) else { self.discard(item); return }
                    if self.player.canInsert(item, after: lastItem) {
                        self.player.insert(item, after: lastItem); lastItem = item; count += 1
                        if count == 2 { return }
                    } else { self.discard(item) }
                } catch is CancellationError { return }
                catch {
                    // URLSession may report NSURLErrorCancelled rather than
                    // CancellationError when a queue rebuild cancels this task.
                    // An obsolete request must never blacklist a still-valid
                    // successor or overwrite the new queue's playback state.
                    guard !Task.isCancelled, self.prefetchGeneration == request,
                          !Self.isCancelledRequest(error) else { return }
                    self.failedIDs.insert(entry.id)
                    self.trace.record("next-prepare-failed", source: entry.track.source, error: error)
                    Self.log.error("[OdifyPlayback] next-prepare-failed source=\(entry.track.source.rawValue, privacy: .public) codes=\(Self.errorCodes(error), privacy: .public)")
                    self.error = "다음 곡 준비 실패: \(entry.track.title). \(Self.playbackMessage(error, source: entry.track.source))"
                    if Self.isTerminalNetworkFailure(error) {
                        self.prefetchDenied = true
                        self.deniedEntries[entry.id] = error
                        return
                    }
                }
            }
        }
    }
    private func currentItemChanged() {
        guard !usingYouTubeWebPlayer else { return }
        guard let item = player.currentItem, let id = itemIDs[ObjectIdentifier(item)] else { return }
        let key = ObjectIdentifier(item)
        let changed = queue.currentID != id || activeItemKey != key
        activeItemKey = key
        queue.currentID = id
        // A buffered successor can fail before it becomes current, in which
        // case its status KVO has already fired. Handle that state on arrival.
        if item.status == .failed { failed(item); return }
        let currentItems = Set(player.items().map(ObjectIdentifier.init))
        itemIDs = itemIDs.filter { currentItems.contains($0.key) }
        itemSourceKeys = itemSourceKeys.filter { currentItems.contains($0.key) }
        for key in itemObservations.keys.filter({ !currentItems.contains($0) }) { itemObservations.removeValue(forKey: key)?.invalidate() }
        for key in driveLoaders.keys.filter({ !currentItems.contains($0) }) { driveLoaders.removeValue(forKey: key)?.cancel() }
        if changed { listeningOccurrence = UUID(); clock.position = 0; clock.duration = current?.duration ?? 0; savedPosition = 0 }
        if changed {
            diagnostics.append("advanced:\(id)"); if diagnostics.count > 40 { diagnostics.removeFirst() }
            trace.record("queue-advanced", source: current?.source)
        }
        publishNowPlaying(resetArtwork: true)
        // startCurrent already builds the successors after inserting/seeking
        // the selected item. Its deferred KVO notification must not cancel
        // and resolve those same network requests again. A real automatic
        // transition does need to refill the prepared successors.
        if changed { rebuildUpcoming() }
        onCheckpoint?()
        if item.status == .readyToPlay { readDriveMetadata(item) }
        if !prefetchDenied, autoplayEnabled, queue.repeatMode == .off, queue.upcoming.count < 3, let track = current { onNeedMore?(track) }
    }
    private func ended(_ item: AVPlayerItem) {
        guard !usingYouTubeWebPlayer else { return }
        guard let id = itemIDs[ObjectIdentifier(item)] else { return }
        if let current = player.currentItem, current !== item { return }
        guard wantsPlayback else { return }
        // Some queue edits leave AVQueuePlayer stopped at its ended current
        // item even with actionAtItemEnd=.advance. Advance the existing queue
        // without resolving/replacing its already buffered successor.
        if player.items().count > 1 {
            player.advanceToNextItem()
            if wantsPlayback { player.play() }
            return
        }
        // Already buffered audio may finish naturally. A terminal server
        // response must not start a new round of cold automatic requests.
        if prefetchDenied { pause(); return }
        guard let next = queue.successor(after: id, automatic: true) else {
            isPlaying = false
            if autoplayEnabled, onNeedMore != nil, let track = current {
                waitingForRecommendations = true; isBuffering = true; onNeedMore?(track)
            } else { wantsPlayback = false; isBuffering = false }
            onCheckpoint?(); publishNowPlaying(); return
        }
        queue.currentID = next.id; savedPosition = 0; startCurrent()
    }
    private func failed(_ item: AVPlayerItem) {
        guard let id = itemIDs[ObjectIdentifier(item)] else { return }
        if deniedEntries[id] != nil { return }
        let statuses = item.errorLog()?.events.map(\.errorStatusCode) ?? []
        let failure: Error
        if current?.source == .youtube, let denied = statuses.first(where: { [401, 403, 429].contains($0) }) {
            failure = AudioHTTPFailure(statusCode: denied)
        } else { failure = item.error ?? OdifyError.message("재생 연결이 중단되었습니다.") }
        trace.record("item-failed", source: current?.source, error: failure, statusCodes: statuses)
        guard queue.currentID == id, player.currentItem == nil || player.currentItem === item else {
            // Do not leave a failed successor sitting in AVQueuePlayer waiting
            // to poison a later automatic transition.
            if player.items().contains(where: { $0 === item }) {
                failedIDs.insert(id); player.remove(item); discard(item); rebuildUpcoming()
            }
            return
        }
        recover(id, failure: failure)
    }
    private func recover(_ id: String, failure: Error) {
        guard queue.currentID == id else { return }
        guard wantsPlayback else { showError(failure); return }
        if Self.isTerminalNetworkFailure(failure) { stopForTerminalFailure(id, failure: failure); return }
        // A restriction is a final service response, never a reason to try
        // another client or silently start a different selected recording.
        if case YouTubeAudioFailure.restricted = failure {
            stopForTerminalFailure(id, failure: failure); return
        }
        if retries.insert(id).inserted { startCurrent(resume: clock.position, refresh: true); return }
        failedIDs.insert(id)
        showError(failure)
        var cursor = id
        for _ in 0..<queue.entries.count {
            guard let next = queue.successor(after: cursor, automatic: false), next.id != id else { break }
            if !failedIDs.contains(next.id) { jump(next.id, userInitiated: false); return }
            cursor = next.id
        }
        pause()
    }
    private func discard(_ item: AVPlayerItem) {
        let key = ObjectIdentifier(item)
        itemIDs.removeValue(forKey: key)
        itemSourceKeys.removeValue(forKey: key)
        itemObservations.removeValue(forKey: key)?.invalidate()
        driveLoaders.removeValue(forKey: key)?.cancel()
    }
    private func rangeFailed(_ id: String, sourceKey: UUID, failure: Error) {
        let items = player.items().filter { itemSourceKeys[ObjectIdentifier($0)] == sourceKey }
        guard queue.entries.contains(where: { $0.id == id }), !items.isEmpty else { return }
        trace.record("audio-range-failed", source: .youtube, error: failure)
        guard Self.isTerminalNetworkFailure(failure) else { return }
        deniedEntries[id] = failure
        prefetchDenied = true; prefetchGeneration = UUID()
        prefetchTask?.cancel(); prefetchTask = nil
        if let currentItem = player.currentItem, itemSourceKeys[ObjectIdentifier(currentItem)] == sourceKey {
            stopForTerminalFailure(id, failure: failure)
        } else {
            for item in items { player.remove(item); discard(item) }
            error = "다음 곡 연결이 거절되었습니다. " + Self.playbackMessage(failure, source: .youtube)
            onCheckpoint?()
        }
    }
    private func stopForTerminalFailure(_ id: String, failure: Error) {
        deniedEntries[id] = failure; prefetchDenied = true; playbackRestricted = true
        generation = UUID(); startTask?.cancel(); startTask = nil
        wantsPlayback = false; interrupted = false; waitingForRecommendations = false
        prefetchGeneration = UUID(); prefetchTask?.cancel(); prefetchTask = nil
        stallTask?.cancel(); stallTask = nil
        savedPosition = clock.position
        player.pause()
        for item in player.items() { discard(item) }
        player.removeAllItems()
        isPlaying = false; isBuffering = false
        showError(failure); onCheckpoint?()
    }
    private func monitorStall() {
        guard !usingYouTubeWebPlayer else { return }
        guard wantsPlayback, stallTask == nil, let item = player.currentItem else { return }
        let position = player.currentTime().seconds
        let background = AudioPreparationLease(name: "Odify recover stalled audio") { [weak self] in self?.stallTask?.cancel() }
        stallTask = Task { [weak self, weak item] in
            defer { background.end() }
            do { try await Task.sleep(for: .seconds(20)) } catch { return }
            guard let self, let item, self.wantsPlayback, self.player.currentItem === item else { return }
            self.stallTask = nil
            let now = self.player.currentTime().seconds
            guard self.player.timeControlStatus != .playing, !now.isFinite || !position.isFinite || now - position < 0.2 else { return }
            self.failed(item)
        }
    }
    private func webEvent(_ request: UUID, event: YouTubeWebEvent) {
        guard usingYouTubeWebPlayer, generation == request, let entry = queue.current else { return }
        switch event {
        case .selected(let id):
            guard YouTubeWebPlayback.validID(id) else { return }
            var track = entry.track; track.videoID = id
            update(track); onTrackMetadata?(track)
            trace.record("web-video-matched", source: .youtube)
            webPlayback?.load(videoID: id, request: request, position: savedPosition, autoplay: wantsPlayback)
            if !wantsPlayback { webPlayback?.pause() }
        case .state(let state, let position, let duration, let intent):
            guard position.isFinite, duration.isFinite, position >= 0, duration >= 0 else { return }
            let wasPlaying = isPlaying
            clock.position = position; if duration > 0 { clock.duration = duration }
            savedPosition = position
            if state == 0 {
                isPlaying = false; isBuffering = false
                if wantsPlayback && intent { advanceWebQueue() }
                return
            }
            // Native controls own playback intent. Preparation or background
            // pause snapshots are observations, not a user pause command.
            let playing = wantsPlayback && state == 1, buffering = wantsPlayback && [-1, 2, 3, 5].contains(state)
            if isPlaying != playing { isPlaying = playing }
            if isBuffering != buffering { isBuffering = buffering }
            if state == 1 {
                error = nil
                if !wasPlaying {
                    trace.record("web-playing", source: .youtube)
                    if autoplayEnabled, queue.upcoming.count < 3 { onNeedMore?(entry.track) }
                }
            }
            if Date().timeIntervalSince(lastCheckpoint) > 10 { lastCheckpoint = Date(); onCheckpoint?() }
            publishNowPlaying()
        case .needsTap:
            wantsPlayback = false; isPlaying = false; isBuffering = false
            showError(OdifyError.message("YouTube가 자동 재생을 허용하지 않았습니다. 다시 재생하거나 YouTube에서 확인해 주세요."))
        case .failed(let code):
            wantsPlayback = false; isPlaying = false; isBuffering = false
            webPlayback?.pause(); playbackRestricted = true; prefetchDenied = true
            let failure = YouTubeWebFailure(code: code); deniedEntries[entry.id] = failure
            showError(failure); onCheckpoint?()
        }
    }
    private func advanceWebQueue() {
        guard let id = queue.currentID else { return }
        if let next = queue.successor(after: id, automatic: true) {
            queue.currentID = next.id; savedPosition = 0
            diagnostics.append("web-advanced"); if diagnostics.count > 40 { diagnostics.removeFirst() }
            trace.record("web-queue-advanced", source: next.track.source)
            startCurrent(); return
        }
        isPlaying = false
        if autoplayEnabled, onNeedMore != nil, let track = current {
            waitingForRecommendations = true; isBuffering = true; onNeedMore?(track)
        } else { wantsPlayback = false; isBuffering = false }
        onCheckpoint?(); publishNowPlaying()
    }
    private func activateAudio() throws {
        let session = AVAudioSession.sharedInstance()
        // playback already supports AirPlay. Explicit allowAirPlay is only
        // valid with playAndRecord; iPadOS 27 rejects that mix with OSStatus -50.
        try session.setCategory(.playback, mode: .default, policy: .longFormAudio, options: [])
        try session.setActive(true)
        trace.record("audio-session-active", source: current?.source)
        Self.log.notice("[OdifyPlayback] audio-session-active")
    }
    private func readDriveMetadata(_ item: AVPlayerItem) {
        guard let id = itemIDs[ObjectIdentifier(item)], id == queue.currentID, let original = current,
              original.driveFileID != nil, metadataRead.insert(original.id).inserted else { return }
        metadataTask?.cancel()
        metadataTask = Task { [weak self] in
            guard let self else { return }
            var completed = false
            defer { if !completed { self.metadataRead.remove(original.id) } }
            do {
                var track = original
                let metadata = try await item.asset.load(.commonMetadata)
                for field in metadata {
                    if field.commonKey == .commonKeyTitle, let text = try? await field.load(.stringValue), !text.isEmpty { track.title = text }
                    if field.commonKey == .commonKeyArtist, let text = try? await field.load(.stringValue), !text.isEmpty { track.artist = text }
                    if field.commonKey == .commonKeyAlbumName, let text = try? await field.load(.stringValue) { track.album = text }
                    if field.commonKey == .commonKeyArtwork, let data = try? await field.load(.dataValue),
                       let jpeg = await ArtworkLoader.shared.coverJPEG(data) {
                        let folder = self.documents.appendingPathComponent("Artwork", isDirectory: true)
                        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                        let relative = "Artwork/drive-\(UUID().uuidString).jpg"
                        try jpeg.write(to: self.documents.appendingPathComponent(relative), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                        track.artwork = relative
                    }
                }
                if let duration = try? await item.asset.load(.duration), duration.seconds.isFinite { track.duration = duration.seconds }
                try Task.checkCancellation()
                guard self.current?.id == track.id else { return }
                if let current = self.current, current.metadataOrigin == "master" {
                    track.title = current.title; track.artist = current.artist; track.album = current.album
                    track.metadataOrigin = "master"; track.originalFilename = current.originalFilename
                }
                self.update(track); self.onTrackMetadata?(track)
                completed = true
            } catch { /* Tag lookup is optional; a playable Drive stream keeps playing. */ }
        }
    }
    private func showError(_ failure: Error) {
        trace.record("failed", source: current?.source, error: failure)
        error = Self.playbackMessage(failure, source: current?.source); isBuffering = false
        Self.log.error("[OdifyPlayback] failure source=\(self.current?.source.rawValue ?? "none", privacy: .public) codes=\(Self.errorCodes(failure), privacy: .public)")
        publishNowPlaying()
    }
    static func playbackMessage(_ failure: Error, source: MusicSource?) -> String {
        let value = failure as NSError
        if value.domain == NSURLErrorDomain, value.code == URLError.timedOut.rawValue {
            return "\(source?.rawValue ?? "음원") 연결 시간이 초과되었습니다 (-1001). 다시 시도해 주세요."
        }
        if failure is OdifyError || failure is YouTubeAudioFailure || failure is AudioHTTPFailure || failure is MusicHTTPFailure || failure is YouTubeWebFailure { return failure.localizedDescription }
        return "\(source?.rawValue ?? "음원") 재생 오류 · " + errorCodes(failure)
    }
    nonisolated static func isCancelledRequest(_ failure: Error) -> Bool {
        if failure is CancellationError { return true }
        var value: NSError? = failure as NSError
        for _ in 0..<4 {
            guard let error = value else { return false }
            if error.domain == NSURLErrorDomain, error.code == URLError.cancelled.rawValue { return true }
            value = error.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return false
    }
    private static func errorCodes(_ failure: Error) -> String {
        let value = failure as NSError
        var codes = ["\(value.domain) \(value.code)"]
        var child = value.userInfo[NSUnderlyingErrorKey] as? NSError
        for _ in 0..<3 {
            guard let error = child else { break }
            codes.append("\(error.domain) \(error.code)"); child = error.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        // Codes identify the failing system layer without exposing signed
        // URLs, file identifiers, HTTP headers or access tokens in a report.
        return codes.joined(separator: " / ")
    }
    nonisolated static func isTerminalNetworkFailure(_ failure: Error) -> Bool {
        if let value = failure as? YouTubeAudioFailure, case .restricted = value { return true }
        var value: NSError? = failure as NSError
        for _ in 0..<4 {
            guard let error = value else { break }
            if error.domain == AudioHTTPFailure.errorDomain, [401, 403, 429].contains(error.code) { return true }
            if [NSURLErrorDomain, MusicHTTPFailure.errorDomain].contains(error.domain), error.code == URLError.httpTooManyRedirects.rawValue { return true }
            value = error.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return false
    }
    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        func add(_ command: MPRemoteCommand, action: @escaping @MainActor () -> Void) {
            let token = command.addTarget { _ in Task { @MainActor in action() }; return .success }
            remoteTargets.append((command, token)); command.isEnabled = true
        }
        add(center.playCommand) { [weak self] in self?.resume() }
        add(center.pauseCommand) { [weak self] in self?.pause() }
        add(center.togglePlayPauseCommand) { [weak self] in self?.toggle() }
        add(center.nextTrackCommand) { [weak self] in self?.next() }
        add(center.previousTrackCommand) { [weak self] in self?.previous() }
        let seek = center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor in self?.seek(event.positionTime) }; return .success
        }
        remoteTargets.append((center.changePlaybackPositionCommand, seek))
        center.changePlaybackPositionCommand.isEnabled = true
    }
    private func publishNowPlaying(resetArtwork: Bool = false) {
        guard let track = current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        let identity = track.id + "|" + (track.artwork ?? "")
        let resetArtwork = resetArtwork && artworkIdentity != identity
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPMediaItemPropertyTitle] = track.title
        info[MPMediaItemPropertyArtist] = track.artist
        info[MPMediaItemPropertyAlbumTitle] = track.album
        info[MPMediaItemPropertyPlaybackDuration] = clock.duration
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = clock.position
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        if resetArtwork {
            artworkIdentity = identity
            info.removeValue(forKey: MPMediaItemPropertyArtwork)
            let hue = Double(track.recordingKey.unicodeScalars.reduce(0) { ($0 + Int($1.value)) % 360 }) / 360
            tint = UIColor(hue: hue, saturation: 0.35, brightness: 0.42, alpha: 1)
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        guard resetArtwork, let address = track.artwork else { return }
        artworkTask?.cancel()
        artworkTask = Task { [weak self] in
            guard let self, let image = await ArtworkLoader.shared.image(address, documents: self.documents, size: 600), !Task.isCancelled,
                  self.current?.id == track.id else { return }
            var updated = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            updated[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            MPNowPlayingInfoCenter.default().nowPlayingInfo = updated
            self.tint = image.odifyTint
        }
    }
}
