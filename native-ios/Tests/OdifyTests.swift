import AVFoundation
import Combine
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
