import AVFoundation
import Combine
import MediaPlayer
import UIKit

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
    var diagnostics: [String] = []
    private let documents: URL
    private var itemIDs: [ObjectIdentifier: String] = [:]
    private var observations: [NSKeyValueObservation] = []
    private var itemObservations: [ObjectIdentifier: NSKeyValueObservation] = [:]
    private var notifications: [NSObjectProtocol] = []
    private var timeObserver: Any?
    private var startTask: Task<Void, Never>?
    private var prefetchTask: Task<Void, Never>?
    private var artworkTask: Task<Void, Never>?
    private var generation = UUID()
    private var prefetchGeneration = UUID()
    private var wantsPlayback = false
    private var interrupted = false
    private var savedPosition: Double
    private var lastCheckpoint = Date.distantPast
    private var failedIDs = Set<String>()
    private var retries = Set<String>()
    private var remoteTargets: [(MPRemoteCommand, Any)] = []

    init(documents: URL, queue: PlaybackQueue = PlaybackQueue(), position: Double = 0, autoplay: Bool = true) {
        self.documents = documents; self.queue = queue; savedPosition = position
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
                self.publishNowPlaying()
            }
        })
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
                let position = time.seconds, duration = self.player.currentItem?.duration.seconds ?? 0
                if position.isFinite { self.clock.position = max(0, position) }
                if duration.isFinite && duration > 0 { self.clock.duration = duration }
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
        notifications.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.uintValue
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.uintValue ?? 0
            Task { @MainActor in
                guard let self else { return }
                if type == AVAudioSession.InterruptionType.began.rawValue {
                    self.interrupted = self.wantsPlayback; self.wantsPlayback = false; self.player.pause()
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
        savedPosition = 0; startCurrent()
    }
    func resume() {
        guard queue.current != nil else { return }
        wantsPlayback = true
        if player.currentItem == nil || player.currentItem?.status == .failed { startCurrent(resume: savedPosition, refresh: true); return }
        do { try activateAudio(); player.play(); publishNowPlaying() }
        catch { showError(error) }
    }
    func pause() {
        wantsPlayback = false; player.pause(); isPlaying = false
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
        queue.currentID = id; savedPosition = 0; startCurrent()
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
        publishNowPlaying(); onCheckpoint?()
    }
    func clearError() { error = nil }
    func toggleAutoplay() {
        autoplayEnabled.toggle(); onCheckpoint?()
        if autoplayEnabled, let track = current, queue.upcoming.count < 3 { onNeedMore?(track) }
    }
    func appendRecommendations(_ tracks: [Track]) {
        queue.entries.append(contentsOf: tracks.map { QueueEntry(track: $0) })
        rebuildUpcoming(); onCheckpoint?()
    }
    var preparedEntryIDs: [String] { player.items().compactMap { itemIDs[ObjectIdentifier($0)] } }
    func shutdown() {
        pause(); startTask?.cancel(); prefetchTask?.cancel(); artworkTask?.cancel()
        observations.forEach { $0.invalidate() }; observations.removeAll()
        itemObservations.values.forEach { $0.invalidate() }; itemObservations.removeAll()
        notifications.forEach { NotificationCenter.default.removeObserver($0) }; notifications.removeAll()
        if let timeObserver { player.removeTimeObserver(timeObserver); self.timeObserver = nil }
        remoteTargets.forEach { $0.0.removeTarget($0.1) }; remoteTargets.removeAll()
        player.removeAllItems()
    }

    private func startCurrent(resume: Double = 0, refresh: Bool = false) {
        startTask?.cancel(); prefetchTask?.cancel()
        generation = UUID(); let request = generation
        player.pause(); player.removeAllItems(); itemIDs.removeAll()
        itemObservations.values.forEach { $0.invalidate() }; itemObservations.removeAll()
        guard let entry = queue.current else { wantsPlayback = false; isPlaying = false; return }
        wantsPlayback = true; isBuffering = true; error = nil
        clock.position = resume; clock.duration = entry.track.duration
        // Resolve while the app still has its current background-audio lifetime.
        var background = UIApplication.shared.beginBackgroundTask(withName: "Odify prepare audio")
        startTask = Task { [weak self] in
            defer { if background != .invalid { UIApplication.shared.endBackgroundTask(background); background = .invalid } }
            guard let self else { return }
            do {
                let url = try await StreamResolver.shared.url(for: entry.track, documents: self.documents, refresh: refresh)
                try Task.checkCancellation()
                guard self.generation == request else { return }
                let item = self.makeItem(url, entry: entry)
                self.player.insert(item, after: nil)
                if resume > 0 { await self.player.seek(to: CMTime(seconds: resume, preferredTimescale: 600)) }
                guard self.generation == request else { return }
                try self.activateAudio()
                if self.wantsPlayback { self.player.play() }
                self.rebuildUpcoming(); self.onCheckpoint?()
            } catch is CancellationError { }
            catch {
                guard self.generation == request else { return }
                self.isBuffering = false; self.wantsPlayback = false; self.showError(error)
            }
        }
    }
    private func makeItem(_ url: URL, entry: QueueEntry) -> AVPlayerItem {
        let item = AVPlayerItem(url: url)
        item.preferredForwardBufferDuration = 30
        item.canUseNetworkResourcesForLiveStreamingWhilePaused = true
        itemIDs[ObjectIdentifier(item)] = entry.id
        itemObservations[ObjectIdentifier(item)] = item.observe(\.status, options: [.new]) { [weak self, weak item] _, _ in
            Task { @MainActor in if let item, item.status == .failed { self?.failed(item) } }
        }
        return item
    }
    private func rebuildUpcoming() {
        prefetchTask?.cancel(); prefetchGeneration = UUID(); let request = prefetchGeneration
        guard let currentItem = player.currentItem, let currentID = itemIDs[ObjectIdentifier(currentItem)] else { return }
        var prepared: [QueueEntry] = [], cursor = currentID
        for _ in 0..<2 {
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
        }
        let missing = Array(prepared.dropFirst(retained))
        guard !missing.isEmpty else { return }
        prefetchTask = Task { [weak self] in
            guard let self else { return }
            var lastItem = lastPrepared
            for entry in missing {
                do {
                    let url = try await StreamResolver.shared.url(for: entry.track, documents: self.documents)
                    try Task.checkCancellation()
                    guard self.prefetchGeneration == request, self.player.items().contains(where: { $0 === lastItem }) else { return }
                    let item = self.makeItem(url, entry: entry)
                    if self.player.canInsert(item, after: lastItem) { self.player.insert(item, after: lastItem); lastItem = item }
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
        clock.position = 0; clock.duration = current?.duration ?? 0; savedPosition = 0
        if changed { diagnostics.append("advanced:\(id)"); if diagnostics.count > 40 { diagnostics.removeFirst() } }
        publishNowPlaying(resetArtwork: true); rebuildUpcoming(); onCheckpoint?()
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
            wantsPlayback = false; isPlaying = false; isBuffering = false; onCheckpoint?(); publishNowPlaying(); return
        }
        queue.currentID = next.id; savedPosition = 0; startCurrent()
    }
    private func failed(_ item: AVPlayerItem) {
        guard let id = itemIDs[ObjectIdentifier(item)], queue.currentID == id else { return }
        if retries.insert(id).inserted { startCurrent(resume: clock.position, refresh: true); return }
        failedIDs.insert(id)
        error = "\(current?.title ?? "이 곡") 재생 연결이 끊겼습니다."
        var cursor = id
        for _ in 0..<queue.entries.count {
            guard let next = queue.successor(after: cursor, automatic: false), next.id != id else { break }
            if !failedIDs.contains(next.id) { jump(next.id); return }
            cursor = next.id
        }
        pause()
    }
    private func activateAudio() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default, options: [.allowAirPlay])
        try session.setActive(true)
    }
    private func showError(_ failure: Error) {
        error = failure.localizedDescription; isBuffering = false
        publishNowPlaying()
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
