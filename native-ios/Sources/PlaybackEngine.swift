import AVFoundation
import Combine
import MediaPlayer
import OdifyStreaming
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
    @Published private(set) var autoplayEnabled: Bool
    @Published private(set) var tint = UIColor(red: 0.35, green: 0.56, blue: 0.48, alpha: 1)
    let clock = PlaybackClock()
    let player = AVQueuePlayer()
    var current: Track? { queue.current?.track }
    var onCheckpoint: (() -> Void)?
    var onNeedMore: ((Track) -> Void)?
    var onTrackMetadata: ((Track) -> Void)?
    var diagnostics: [String] = []
    private let documents: URL
    private let driveClient: DriveClient
    private var itemIDs: [ObjectIdentifier: String] = [:]
    private var observations: [NSKeyValueObservation] = []
    private var itemObservations: [ObjectIdentifier: NSKeyValueObservation] = [:]
    private var driveLoaders: [ObjectIdentifier: DriveResourceLoader] = [:]
    private var notifications: [NSObjectProtocol] = []
    private var timeObserver: Any?
    private var startTask: Task<Void, Never>?
    private var prefetchTask: Task<Void, Never>?
    private var artworkTask: Task<Void, Never>?
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

    init(documents: URL, queue: PlaybackQueue = PlaybackQueue(), position: Double = 0, autoplay: Bool = true, driveClient: DriveClient = .shared) {
        self.documents = documents; self.queue = queue; savedPosition = position
        self.driveClient = driveClient
        autoplayEnabled = autoplay
        clock.position = position
        clock.duration = queue.current?.track.duration ?? 0
        player.automaticallyWaitsToMinimizeStalling = true
        player.actionAtItemEnd = .advance
        observations.append(player.observe(\.currentItem, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in self?.currentItemChanged() }
        })
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in
                guard let self else { return }
                self.isPlaying = self.player.timeControlStatus == .playing
                self.isBuffering = self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate
                if self.isBuffering { self.monitorStall() }
                else { self.stallTask?.cancel(); self.stallTask = nil }
                self.publishNowPlaying()
            }
        })
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
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
            Task { @MainActor in self?.onCheckpoint?() }
        })
        configureRemoteCommands()
    }

    func play(_ tracks: [Track], at index: Int = 0) {
        guard !tracks.isEmpty else { return }
        queue.replace(tracks, at: index); failedIDs.removeAll(); retries.removeAll()
        waitingForRecommendations = false; savedPosition = 0; startCurrent()
    }
    func resume() {
        guard queue.current != nil else { return }
        wantsPlayback = true
        if player.currentItem == nil || player.currentItem?.status == .failed { startCurrent(resume: savedPosition, refresh: true); return }
        do { try activateAudio(); player.play(); publishNowPlaying() }
        catch { showError(error) }
    }
    func pause() {
        wantsPlayback = false; interrupted = false; waitingForRecommendations = false
        stallTask?.cancel(); stallTask = nil
        player.pause(); isPlaying = false; isBuffering = false
        savedPosition = clock.position; onCheckpoint?(); publishNowPlaying()
    }
    func toggle() { if wantsPlayback { pause() } else { resume() } }
    func seek(_ seconds: Double) {
        guard seconds.isFinite else { return }
        let position = max(0, min(clock.duration > 0 ? clock.duration : seconds, seconds))
        savedPosition = position; clock.position = position
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
    func jump(_ id: String) {
        guard queue.entries.contains(where: { $0.id == id }) else { return }
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
        queue.entries.append(QueueEntry(track: track))
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
    func toggleAutoplay() {
        autoplayEnabled.toggle(); onCheckpoint?()
        if autoplayEnabled, let track = current, queue.upcoming.count < 3 { onNeedMore?(track) }
    }
    func appendRecommendations(_ tracks: [Track]) {
        guard !tracks.isEmpty else { return }
        queue.entries.append(contentsOf: tracks.map { QueueEntry(track: $0) })
        if waitingForRecommendations, wantsPlayback, let id = queue.currentID,
           let next = queue.successor(after: id, automatic: false) {
            jump(next.id); return
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
    }

    private func startCurrent(resume: Double = 0, refresh: Bool = false) {
        startTask?.cancel(); prefetchTask?.cancel(); stallTask?.cancel(); stallTask = nil
        generation = UUID(); let request = generation
        player.pause(); player.removeAllItems(); itemIDs.removeAll()
        itemObservations.values.forEach { $0.invalidate() }; itemObservations.removeAll()
        driveLoaders.values.forEach { $0.cancel() }; driveLoaders.removeAll()
        guard let entry = queue.current else { wantsPlayback = false; isPlaying = false; return }
        wantsPlayback = true; isPlaying = false; isBuffering = true; error = nil
        clock.position = resume; clock.duration = entry.track.duration
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
                let url = try await StreamResolver.shared.url(for: entry.track, documents: self.documents, refresh: refresh)
                try Task.checkCancellation()
                guard self.generation == request else { return }
                let item = try await self.makeItem(url, entry: entry)
                guard !Task.isCancelled, self.generation == request else { self.discard(item); return }
                self.player.insert(item, after: nil)
                if resume > 0 { await self.player.seek(to: CMTime(seconds: resume, preferredTimescale: 600)) }
                guard self.generation == request else { return }
                if self.wantsPlayback { try self.activateAudio(); self.player.play() }
                self.rebuildUpcoming(); self.onCheckpoint?()
            } catch is CancellationError { }
            catch {
                guard self.generation == request else { return }
                self.recover(entry.id, failure: error)
            }
        }
    }
    private func makeItem(_ url: URL, entry: QueueEntry) async throws -> AVPlayerItem {
        let options: [String: Any] = url.host?.hasSuffix(".googlevideo.com") == true
            ? [AVURLAssetHTTPUserAgentKey: YouTubeAudio.userAgent] : [:]
        let asset = AVURLAsset(url: url, options: options)
        let loader: DriveResourceLoader?
        if entry.track.driveFileID != nil {
            let delegate = DriveResourceLoader(track: entry.track, client: driveClient)
            asset.resourceLoader.setDelegate(delegate, queue: delegate.queue)
            loader = delegate
        } else { loader = nil }
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
        if let loader { driveLoaders[ObjectIdentifier(item)] = loader }
        itemObservations[ObjectIdentifier(item)] = item.observe(\.status, options: [.new]) { [weak self, weak item] _, _ in
            Task { @MainActor in
                if let item, item.status == .failed { self?.failed(item) }
                else if let item, item.status == .readyToPlay { self?.readDriveMetadata(item) }
            }
        }
        return item
    }
    private func rebuildUpcoming() {
        prefetchTask?.cancel(); prefetchGeneration = UUID(); let request = prefetchGeneration
        guard let currentItem = player.currentItem, let currentID = itemIDs[ObjectIdentifier(currentItem)] else { return }
        var prepared: [QueueEntry] = [], cursor = currentID
        // Look beyond broken entries; stop after two usable successors have
        // actually been inserted, rather than examining only two queue slots.
        for _ in 0..<max(2, queue.entries.count) {
            guard let entry = queue.successor(after: cursor, automatic: true) else { break }
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
            defer { background.end() }
            guard let self else { return }
            var lastItem = lastPrepared
            var count = retained
            for entry in missing {
                do {
                    let url = try await StreamResolver.shared.url(for: entry.track, documents: self.documents)
                    try Task.checkCancellation()
                    guard self.prefetchGeneration == request, self.player.items().contains(where: { $0 === lastItem }) else { return }
                    let item = try await self.makeItem(url, entry: entry)
                    guard !Task.isCancelled, self.prefetchGeneration == request,
                          self.player.items().contains(where: { $0 === lastItem }) else { self.discard(item); return }
                    if self.player.canInsert(item, after: lastItem) {
                        self.player.insert(item, after: lastItem); lastItem = item; count += 1
                        if count == 2 { return }
                    } else { self.discard(item) }
                } catch is CancellationError { return }
                catch { self.failedIDs.insert(entry.id); self.error = "다음 곡 준비 실패: \(entry.track.title). \(error.localizedDescription)" }
            }
        }
    }
    private func currentItemChanged() {
        guard let item = player.currentItem, let id = itemIDs[ObjectIdentifier(item)] else { return }
        let changed = queue.currentID != id
        queue.currentID = id
        // A buffered successor can fail before it becomes current, in which
        // case its status KVO has already fired. Handle that state on arrival.
        if item.status == .failed { failed(item); return }
        let currentItems = Set(player.items().map(ObjectIdentifier.init))
        itemIDs = itemIDs.filter { currentItems.contains($0.key) }
        for key in itemObservations.keys.filter({ !currentItems.contains($0) }) { itemObservations.removeValue(forKey: key)?.invalidate() }
        for key in driveLoaders.keys.filter({ !currentItems.contains($0) }) { driveLoaders.removeValue(forKey: key)?.cancel() }
        if changed { clock.position = 0; clock.duration = current?.duration ?? 0; savedPosition = 0 }
        if changed { diagnostics.append("advanced:\(id)"); if diagnostics.count > 40 { diagnostics.removeFirst() } }
        publishNowPlaying(resetArtwork: true); rebuildUpcoming(); onCheckpoint?()
        if item.status == .readyToPlay { readDriveMetadata(item) }
        if autoplayEnabled, queue.repeatMode == .off, queue.upcoming.count < 3, let track = current { onNeedMore?(track) }
    }
    private func ended(_ item: AVPlayerItem) {
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
        let failure = item.error ?? OdifyError.message("재생 연결이 중단되었습니다.")
        guard player.currentItem === item, queue.currentID == id else {
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
        // A restriction is a final service response, never a reason to try
        // another client or silently start a different selected recording.
        if case YouTubeAudioFailure.restricted = failure {
            wantsPlayback = false; showError(failure); onCheckpoint?(); return
        }
        if retries.insert(id).inserted { startCurrent(resume: clock.position, refresh: true); return }
        failedIDs.insert(id)
        error = Self.playbackMessage(failure, source: current?.source)
        var cursor = id
        for _ in 0..<queue.entries.count {
            guard let next = queue.successor(after: cursor, automatic: false), next.id != id else { break }
            if !failedIDs.contains(next.id) { jump(next.id); return }
            cursor = next.id
        }
        pause()
    }
    private func discard(_ item: AVPlayerItem) {
        let key = ObjectIdentifier(item)
        itemIDs.removeValue(forKey: key)
        itemObservations.removeValue(forKey: key)?.invalidate()
        driveLoaders.removeValue(forKey: key)?.cancel()
    }
    private func monitorStall() {
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
    private func activateAudio() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default, options: [.allowAirPlay])
        try session.setActive(true)
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
                self.update(track); self.onTrackMetadata?(track)
                completed = true
            } catch { /* Tag lookup is optional; a playable Drive stream keeps playing. */ }
        }
    }
    private func showError(_ failure: Error) {
        error = Self.playbackMessage(failure, source: current?.source); isBuffering = false
        publishNowPlaying()
    }
    private static func playbackMessage(_ failure: Error, source: MusicSource?) -> String {
        let value = failure as NSError
        if value.domain == NSURLErrorDomain, value.code == URLError.timedOut.rawValue {
            return "\(source?.rawValue ?? "음원") 연결 시간이 초과되었습니다 (-1001). 다시 시도해 주세요."
        }
        guard value.domain == AVFoundationErrorDomain || value.domain == NSOSStatusErrorDomain else { return failure.localizedDescription }
        var codes = ["\(value.domain) \(value.code)"]
        var child = value.userInfo[NSUnderlyingErrorKey] as? NSError
        for _ in 0..<3 {
            guard let error = child else { break }
            codes.append("\(error.domain) \(error.code)"); child = error.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        // Codes identify the failing system layer without exposing signed
        // URLs, file identifiers, HTTP headers or access tokens in a report.
        return "\(source?.rawValue ?? "음원") 재생 준비 실패 · " + codes.joined(separator: " / ")
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
