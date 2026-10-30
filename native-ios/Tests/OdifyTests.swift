import AVFoundation
import Combine
import UIKit
import XCTest
@testable import Odify

final class QueueTests: XCTestCase {
    private let a = Track(id: "a", title: "A", artist: "Artist")
    private let b = Track(id: "b", title: "B", artist: "Artist")
    private let c = Track(id: "c", title: "C", artist: "Artist")
    func testReorderKeepsCurrentOccurrenceAndUsesNewSuccessor() {
        var queue = PlaybackQueue(); queue.replace([a, b, c])
        let current = queue.currentID
        queue.move(from: IndexSet(integer: 2), to: 1)
        XCTAssertEqual(queue.currentID, current)
        XCTAssertEqual(queue.successor(after: current!, automatic: true)?.track.id, "c")
        XCTAssertEqual(queue.entries.map(\.track.id), ["a", "c", "b"])
    }
    func testMovingCurrentAndDuplicateRecordingsDoesNotLoseIdentity() {
        var queue = PlaybackQueue(); queue.replace([a, b, a], at: 2)
        let current = queue.currentID
        XCTAssertNotEqual(queue.entries[0].id, queue.entries[2].id)
        queue.move(from: IndexSet(integer: 2), to: 0)
        XCTAssertEqual(queue.currentID, current)
        XCTAssertEqual(queue.currentIndex, 0)
        queue.remove(queue.entries[1].id)
        XCTAssertEqual(queue.currentID, current)
    }
    func testRepeatOneOnlyAppliesToAutomaticTransition() {
        var queue = PlaybackQueue(); queue.replace([a, b]); queue.repeatMode = .one
        XCTAssertEqual(queue.successor(after: queue.currentID!, automatic: true)?.track.id, "a")
        XCTAssertEqual(queue.successor(after: queue.currentID!, automatic: false)?.track.id, "b")
        queue.repeatMode = .all
        XCTAssertEqual(queue.successor(after: queue.entries.last!.id, automatic: true)?.track.id, "a")
        queue.repeatMode = .off
        XCTAssertNil(queue.successor(after: queue.entries.last!.id, automatic: true))
    }
    func testQueueRestoresOccurrenceAndRelativeFilesAcrossContainerPaths() throws {
        var queue = PlaybackQueue()
        queue.replace([Track(id: "file:1", title: "Local", artist: "Artist", source: .local, localFile: "Music/song.m4a"), b])
        let snapshot = LibrarySnapshot(tracks: queue.entries.map(\.track), queue: queue, position: 42)
        let data = try JSONEncoder().encode(snapshot)
        let restored = try JSONDecoder().decode(LibrarySnapshot.self, from: data)
        XCTAssertEqual(restored.queue, queue)
        XCTAssertEqual(restored.position, 42)
        XCTAssertEqual(restored.tracks.first?.localFile, "Music/song.m4a")
    }
    func testDeduplicatesArtistCatalogueWithCaseAndDiacritics() {
        let songs = [Track(id: "one", title: "Messy", artist: "ROSÉ"), Track(id: "two", title: "MESSY", artist: "Rosé"), b]
        XCTAssertEqual(Track.unique(songs).map(\.id), ["one", "b"])
    }
    func testEarlierSnapshotWithoutAutoplayKeepsLibrary() throws {
        let snapshot = LibrarySnapshot(tracks: [a, b], likedIDs: ["a"], position: 17)
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(snapshot)) as? [String: Any])
        json.removeValue(forKey: "autoplay")
        let restored = try JSONDecoder().decode(LibrarySnapshot.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(restored.tracks.map(\.id), ["a", "b"])
        XCTAssertEqual(restored.position, 17)
        XCTAssertEqual(restored.likedIDs, ["a"])
        XCTAssertTrue(restored.autoplay)
    }
    func testOfficialMetadataIsKeptButLiveAndCoverUseVideoArtwork() {
        let official = Track(id: "catalog:1", title: "Messy", artist: "ROSÉ", artwork: "https://example.org/album.jpg")
        let video = VideoResult(id: "abcdefghijk", title: "ROSÉ - Messy (Official Audio)", channel: "ROSÉ")
        XCTAssertEqual(video.track(catalog: official).artwork, official.artwork)
        XCTAssertEqual(video.track(catalog: official).artist, "ROSÉ")
        let live = VideoResult(id: "zyxwvutsrqp", title: "Messy (Live)", channel: "Stage")
        XCTAssertEqual(live.track(catalog: official).artwork, live.artwork)
        XCTAssertEqual(live.track(catalog: official).id, "youtube:zyxwvutsrqp")
    }
    func testYouTubeParserHandlesQuotedBracesAndDuplicateRenderers() throws {
        let renderer: [String: Any] = ["videoRenderer": ["videoId": "abcdefghijk", "title": ["runs": [["text": "Song { live } \"night\""]]], "ownerText": ["runs": [["text": "Artist"]]]]]
        let data = try JSONSerialization.data(withJSONObject: ["contents": [renderer, renderer]])
        let html = "<script>var ytInitialData = " + String(decoding: data, as: UTF8.self) + "; var next = {};</script>"
        let results = YouTubeCatalog.videoResults(try XCTUnwrap(YouTubeCatalog.initialData(html)))
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].title, "Song { live } \"night\"")
    }
    func testCatalogNeverUsesChannelOrPreviewURLAsFullRecording() throws {
        let track = try XCTUnwrap(MusicCatalog.parseSong(["trackId": 1, "artistId": 2, "trackName": "Song", "artistName": "Artist", "artworkUrl100": "https://example.org/100x100bb.jpg", "previewUrl": "https://example.org/30-second-preview.m4a"]))
        XCTAssertEqual(track.artwork, "https://example.org/600x600bb.jpg")
        XCTAssertNil(track.localFile); XCTAssertNil(track.videoID)
        XCTAssertEqual(track.source, .youtube)
    }
    func testArtistPortraitUsesSquareOriginalInsteadOfSocialCard() throws {
        let input = "https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages221/v4/example/portrait.png/1200x630cw.png"
        XCTAssertEqual(MusicCatalog.squarePortrait(input), "https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages221/v4/example/portrait.png/600x600bb.jpg")
        XCTAssertNil(MusicCatalog.squarePortrait("https://untrusted.example/image/thumb/AMCArtistImages/a/1200x630cw.png"))
    }
}

@MainActor final class NativePlaybackTests: XCTestCase {
    func testPreparedQueueAdvancesAndReorderPreservesCurrentAudio() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let wave = Self.silence(seconds: 2)
        try wave.write(to: directory.appendingPathComponent("a.wav"))
        let tracks = ["A", "B", "C"].map { Track(id: $0, title: $0, artist: "Test", source: .local, localFile: "a.wav", duration: 2) }
        let engine = PlaybackEngine(documents: directory)
        defer { engine.shutdown() }
        engine.player.volume = 0
        engine.play(tracks)
        for _ in 0..<100 {
            if engine.preparedEntryIDs.count == 3 { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(engine.preparedEntryIDs.count, 3, engine.error ?? "not prepared")
        XCTAssertEqual(AVAudioSession.sharedInstance().category, .playback)
        XCTAssertFalse(AVAudioSession.sharedInstance().categoryOptions.contains(.allowAirPlay), "playback already supports AirPlay; this explicit option is invalid on physical iPadOS 27")
        let original = try XCTUnwrap(engine.player.currentItem)
        engine.move(IndexSet(integer: 2), to: 1)
        for _ in 0..<60 {
            if engine.preparedEntryIDs.count == 3 { break }
            try await Task.sleep(for: .milliseconds(25))
        }
        XCTAssertTrue(engine.player.currentItem === original)
        XCTAssertEqual(engine.preparedEntryIDs, engine.queue.entries.map(\.id))
        let preparedNext = try XCTUnwrap(engine.player.items().dropFirst().first)
        for _ in 0..<160 {
            if engine.current?.id == "C" { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(engine.current?.id, "C", engine.error ?? "did not advance")
        XCTAssertTrue(engine.player.currentItem === preparedNext, "must reuse the buffered next AVPlayerItem")
        XCTAssertFalse(engine.diagnostics.isEmpty)
    }
    func testResolverRejectsRelativePathEscape() async throws {
        let track = Track(id: "x", title: "X", artist: "X", source: .local, localFile: "../outside.mp3")
        do { _ = try await StreamResolver.shared.url(for: track, documents: FileManager.default.temporaryDirectory); XCTFail("escaped library") }
        catch { XCTAssertTrue(error.localizedDescription.contains("파일")) }
    }
    func testUnknownNetworkErrorKeepsSourceAndUnderlyingCodesWithoutPrivateURLs() {
        let child = NSError(domain: NSOSStatusErrorDomain, code: -12935)
        let failure = NSError(domain: NSURLErrorDomain, code: -1, userInfo: [
            NSLocalizedDescriptionKey: "Unknown error https://private.example/file?access_token=private-fixture",
            NSUnderlyingErrorKey: child
        ])
        let message = PlaybackEngine.playbackMessage(failure, source: .youtube)
        XCTAssertTrue(message.contains("YouTube")); XCTAssertTrue(message.contains("NSURLErrorDomain -1"))
        XCTAssertTrue(message.contains("-12935"))
        XCTAssertFalse(message.contains("private")); XCTAssertFalse(message.contains("https://"))
    }
    func testBrokenPreparedFileDoesNotStopTheRemainingQueue() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try Self.silence(seconds: 2).write(to: directory.appendingPathComponent("ok.wav"))
        try Data("not an audio recording".utf8).write(to: directory.appendingPathComponent("bad.wav"))
        let engine = PlaybackEngine(documents: directory)
        defer { engine.shutdown() }
        engine.player.volume = 0
        engine.play([
            Track(id: "ok1", title: "First", artist: "Test", source: .local, localFile: "ok.wav", duration: 2),
            Track(id: "bad", title: "Broken", artist: "Test", source: .local, localFile: "bad.wav"),
            Track(id: "ok2", title: "Last", artist: "Test", source: .local, localFile: "ok.wav", duration: 2)
        ])
        for _ in 0..<240 {
            if engine.current?.id == "ok2", engine.player.currentItem?.status == .readyToPlay { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(engine.current?.id, "ok2", engine.error ?? "stopped at corrupt file")
        XCTAssertEqual(engine.player.currentItem?.status, .readyToPlay)
    }
    func testMissingSelectedFileSkipsMultipleFailuresAndStartsPlayableSuccessor() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try Self.silence(seconds: 2).write(to: directory.appendingPathComponent("ok.wav"))
        let engine = PlaybackEngine(documents: directory, autoplay: false)
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play((0..<4).map { Track(id: "bad\($0)", title: "Missing", artist: "Test", source: .local, localFile: "missing.wav") }
                    + [Track(id: "ok", title: "OK", artist: "Test", source: .local, localFile: "ok.wav")])
        for _ in 0..<100 {
            if engine.current?.id == "ok", engine.player.currentItem?.status == .readyToPlay { break }
            try await Task.sleep(for: .milliseconds(30))
        }
        XCTAssertEqual(engine.current?.id, "ok", engine.error ?? "did not skip missing files")
        XCTAssertEqual(engine.player.currentItem?.status, .readyToPlay)
    }
    func testPauseWhileFailedNotificationArrivesNeverRestartsPlayback() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try Self.silence(seconds: 3).write(to: directory.appendingPathComponent("ok.wav"))
        let engine = PlaybackEngine(documents: directory, autoplay: false)
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play([Track(id: "ok", title: "OK", artist: "Test", source: .local, localFile: "ok.wav")])
        for _ in 0..<100 {
            if engine.player.currentItem?.status == .readyToPlay { break }
            try await Task.sleep(for: .milliseconds(30))
        }
        let item = try XCTUnwrap(engine.player.currentItem)
        engine.pause()
        NotificationCenter.default.post(name: .AVPlayerItemFailedToPlayToEndTime, object: item)
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertTrue(engine.player.currentItem === item)
        XCTAssertEqual(engine.player.rate, 0); XCTAssertFalse(engine.isPlaying)
    }
    func testDelayedRecommendationContinuesAfterSingleSongEndsButPauseCancelsIntent() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try Self.silence(seconds: 1).write(to: directory.appendingPathComponent("ok.wav"))
        let a = Track(id: "a", title: "A", artist: "Test", source: .local, localFile: "ok.wav")
        let b = Track(id: "b", title: "B", artist: "Test", source: .local, localFile: "ok.wav")
        let engine = PlaybackEngine(documents: directory)
        defer { engine.shutdown() }; engine.player.volume = 0
        var requests = 0
        engine.onNeedMore = { _ in requests += 1 }
        engine.play([a])
        for _ in 0..<160 {
            if requests >= 2 { break }
            try await Task.sleep(for: .milliseconds(30))
        }
        XCTAssertGreaterThanOrEqual(requests, 2, "must retain natural-end intent while discovery is pending")
        engine.appendRecommendations([b])
        for _ in 0..<100 {
            if engine.current?.id == "b", engine.player.rate > 0 { break }
            try await Task.sleep(for: .milliseconds(30))
        }
        XCTAssertEqual(engine.current?.id, "b"); XCTAssertGreaterThan(engine.player.rate, 0)
        engine.pause()
        engine.appendRecommendations([a])
        try await Task.sleep(for: .milliseconds(150))
        XCTAssertEqual(engine.player.rate, 0)
    }
    func testTwelveSilentTransitionsKeepOnlyBoundedPreparedItems() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try Self.silence(seconds: 1).write(to: directory.appendingPathComponent("ok.wav"))
        let tracks = (0..<12).map { Track(id: "song\($0)", title: "Silent \($0)", artist: "Test", source: .local, localFile: "ok.wav") }
        let engine = PlaybackEngine(documents: directory, autoplay: false)
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play(tracks)
        // Lifecycle notification coverage only: this does not claim a physical
        // iPad/LiveContainer lock-screen or suspension test.
        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        for _ in 0..<650 {
            XCTAssertLessThanOrEqual(engine.player.items().count, 3)
            if engine.current?.id == "song11", engine.player.currentItem?.status == .readyToPlay { break }
            try await Task.sleep(for: .milliseconds(40))
        }
        XCTAssertEqual(engine.current?.id, "song11", engine.error ?? "continuous queue stopped")
        XCTAssertEqual(engine.diagnostics.filter { $0.hasPrefix("advanced:") }.count, 11)
    }
    private static func silence(seconds: UInt32) -> Data {
        let sampleRate: UInt32 = 8000, count = sampleRate * seconds * 2
        var data = Data()
        func string(_ value: String) { data.append(contentsOf: value.utf8) }
        func integer<T: FixedWidthInteger>(_ value: T) { var little = value.littleEndian; withUnsafeBytes(of: &little) { data.append(contentsOf: $0) } }
        string("RIFF"); integer(count + 36); string("WAVEfmt "); integer(UInt32(16)); integer(UInt16(1)); integer(UInt16(1))
        integer(sampleRate); integer(sampleRate * 2); integer(UInt16(2)); integer(UInt16(16)); string("data"); integer(count)
        data.append(Data(repeating: 0, count: Int(count)))
        return data
    }
}
