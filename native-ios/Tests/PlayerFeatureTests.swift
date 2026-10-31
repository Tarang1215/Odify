import Foundation
import UIKit
import XCTest
@testable import Odify

private final class LyricsFixture: URLProtocol {
    static var status = 200
    static var requests: [URLRequest] = []
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "lrclib.net" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let data = Data(#"[{"id":1,"trackName":"Song","artistName":"Artist","albumName":"Album","duration":180,"syncedLyrics":"[00:01.20]Fixture","plainLyrics":"Fixture"}]"#.utf8)
        let response = HTTPURLResponse(url: request.url!, statusCode: Self.status, httpVersion: nil,
                                       headerFields: ["Content-Type": "application/json", "Retry-After": "120"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

final class LyricsFeatureTests: XCTestCase {
    private func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [LyricsFixture.self]
        return URLSession(configuration: config)
    }
    func testSearchIncludesOnlyExplicitSongFieldsAndCachesResults() async throws {
        LyricsFixture.status = 200; LyricsFixture.requests = []
        let session = session(); defer { session.invalidateAndCancel() }
        let catalog = LyricsCatalog(session: session)
        let first = try await catalog.search(title: "Song", artist: "Artist")
        let cached = try await catalog.search(title: "Song", artist: "Artist")
        XCTAssertEqual(first, cached); XCTAssertEqual(first.first?.text, "[00:01.20]Fixture")
        XCTAssertEqual(LyricsFixture.requests.count, 1)
        let request = try XCTUnwrap(LyricsFixture.requests.first)
        let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(Set(query.map(\.name)), Set(["track_name", "artist_name"]))
        XCTAssertFalse(request.httpShouldHandleCookies)
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization")); XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        XCTAssertTrue(request.value(forHTTPHeaderField: "User-Agent")?.contains("Odify") == true)
    }
    func testRateLimitBlocksFurtherRequestsUntilRetryAfter() async throws {
        LyricsFixture.status = 429; LyricsFixture.requests = []
        let session = session(); defer { session.invalidateAndCancel() }
        let catalog = LyricsCatalog(session: session)
        for _ in 0..<2 {
            do { _ = try await catalog.search(title: "Song", artist: "Artist"); XCTFail("rate limit must fail") } catch { }
        }
        XCTAssertEqual(LyricsFixture.requests.count, 1)
    }
    func testNullLyricsAndInstrumentalResultsAreNotSelectable() throws {
        let data = Data(#"[{"id":1,"trackName":"A","artistName":"B","plainLyrics":null,"syncedLyrics":null},{"id":2,"trackName":"A","artistName":"B","instrumental":true,"plainLyrics":"instrumental"},{"id":3,"trackName":"A","artistName":"B","plainLyrics":"Fixture"}]"#.utf8)
        XCTAssertEqual(try LyricsCatalog.parse(data).map(\.id), [3])
    }
    func testLRCMultipleTimestampsFractionsAndOffsetKeepCorrectSeekTimes() {
        let lines = LRCParser.lines("[ar:Artist]\n[offset:-200]\n[00:01.20][00:03.125]One\n[00:02]Two\n[00:75]Invalid")
        XCTAssertEqual(lines.map(\.text), ["One", "Two", "One"])
        XCTAssertEqual(lines[0].seconds, 1, accuracy: 0.001)
        XCTAssertEqual(lines[1].seconds, 1.8, accuracy: 0.001)
        XCTAssertEqual(lines[2].seconds, 2.925, accuracy: 0.001)
        XCTAssertTrue(LRCParser.lines("Plain lyrics\nSecond line").isEmpty)
    }
    @MainActor func testLyricsSaveDoesNotChangeTheCurrentlySelectedRecording() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(documents: root, preview: true)
        defer { store.engine.shutdown(); try? FileManager.default.removeItem(at: root) }
        let original = try XCTUnwrap(store.tracks.last), currentID = store.engine.current?.id
        store.saveLyrics("[00:01]Chosen lyrics", for: original)
        XCTAssertEqual(store.tracks.first(where: { $0.id == original.id })?.lyrics, "[00:01]Chosen lyrics")
        XCTAssertEqual(store.engine.current?.id, currentID)
    }
}

final class SourcePreferenceTests: XCTestCase {
    private let catalog = Track(id: "catalog", title: "Song", artist: "Artist", album: "Album", duration: 180)
    private var drive: Track { Track(id: "drive:file123", title: "Song", artist: "Artist", album: "Album", source: .drive,
                                    duration: 181, driveFileID: "file123", driveAccountHash: "account", fileSize: 10000) }
    func testMatchingOwnedDriveRecordingWinsBeforeYouTubeResolution() {
        let root = FileManager.default.temporaryDirectory
        XCTAssertEqual(PlaybackSourcePreference.resolve(catalog, library: [drive], account: "account", documents: root).source, .drive)
    }
    func testAttachedDriveWinsOverStaleCatalogLabelAndUntaggedFilenameMatchesBothFields() {
        let root = FileManager.default.temporaryDirectory
        var stored = drive; stored.source = .youtube
        XCTAssertEqual(PlaybackSourcePreference.resolve(stored, library: [], account: "account", documents: root).source, .drive)
        stored.artist = "Google Drive"; stored.title = "01 - Artist - Song"
        XCTAssertEqual(PlaybackSourcePreference.resolve(catalog, library: [stored], account: "account", documents: root).id, stored.id)
        stored.title = "01 - Other Artist - Song"
        XCTAssertEqual(PlaybackSourcePreference.resolve(catalog, library: [stored], account: "account", documents: root), catalog)
    }
    func testMissingOwnedReferenceFailsWithoutYouTubeLookup() async {
        let resolver = StreamResolver()
        for source in [MusicSource.local, .drive] {
            var broken = catalog; broken.source = source
            do { _ = try await resolver.url(for: broken, documents: FileManager.default.temporaryDirectory); XCTFail("Missing owned reference must fail") }
            catch { XCTAssertTrue(error.localizedDescription.contains("원본 음원")) }
        }
    }
    func testDisconnectedOtherAccountWrongDurationOrAmbiguousEditionDoesNotReplaceSong() {
        let root = FileManager.default.temporaryDirectory
        XCTAssertEqual(PlaybackSourcePreference.resolve(catalog, library: [drive], account: nil, documents: root), catalog)
        XCTAssertEqual(PlaybackSourcePreference.resolve(catalog, library: [drive], account: "other", documents: root), catalog)
        var wrong = drive; wrong.duration = 240
        XCTAssertEqual(PlaybackSourcePreference.resolve(catalog, library: [wrong], account: "account", documents: root), catalog)
        var duplicate = drive; duplicate.id = "other"; duplicate.driveFileID = "file456"
        XCTAssertEqual(PlaybackSourcePreference.resolve(catalog, library: [drive, duplicate], account: "account", documents: root), catalog)
    }
    func testExistingLocalFileWinsAndMissingOrEscapingPathsAreRejected() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try Data([0]).write(to: root.appendingPathComponent("song.mp3"))
        var local = catalog; local.id = "local"; local.source = .local; local.localFile = "song.mp3"
        XCTAssertEqual(PlaybackSourcePreference.resolve(catalog, library: [drive, local], account: "account", documents: root).id, "local")
        local.localFile = "missing.mp3"
        XCTAssertEqual(PlaybackSourcePreference.resolve(catalog, library: [local], account: "account", documents: root), catalog)
        local.localFile = "../song.mp3"
        XCTAssertEqual(PlaybackSourcePreference.resolve(catalog, library: [local], account: "account", documents: root), catalog)
    }
    func testDismissalAcceptsDownwardSwipeAndRejectsHorizontalUpwardOrTinyDrags() {
        XCTAssertTrue(PlayerDismissal.shouldClose(x: 10, y: 110, predictedY: 140))
        XCTAssertTrue(PlayerDismissal.shouldClose(x: 5, y: 45, predictedY: 220))
        XCTAssertFalse(PlayerDismissal.shouldClose(x: 180, y: 110, predictedY: 220))
        XCTAssertFalse(PlayerDismissal.shouldClose(x: 0, y: -180, predictedY: -220))
        XCTAssertFalse(PlayerDismissal.shouldClose(x: 0, y: 12, predictedY: 190))
    }
    @MainActor func testTintUsesCoverColorInsteadOfBlackBorder() {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 100, height: 100)).image { context in
            UIColor.black.setFill(); context.fill(CGRect(x: 0, y: 0, width: 100, height: 100))
            UIColor.blue.setFill(); context.fill(CGRect(x: 20, y: 20, width: 60, height: 60))
        }
        var hue: CGFloat = 0, saturation: CGFloat = 0, brightness: CGFloat = 0
        image.odifyTint.getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: nil)
        XCTAssertEqual(hue, 2.0 / 3, accuracy: 0.05); XCTAssertGreaterThan(saturation, 0.5); XCTAssertGreaterThanOrEqual(brightness, 0.55)
    }
    @MainActor func testGrayscaleArtworkKeepsANeutralGlow() {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 40)).image { context in
            UIColor.gray.setFill(); context.fill(CGRect(x: 0, y: 0, width: 40, height: 40))
        }
        var saturation: CGFloat = 0, brightness: CGFloat = 0
        image.odifyTint.getHue(nil, saturation: &saturation, brightness: &brightness, alpha: nil)
        XCTAssertLessThan(saturation, 0.2); XCTAssertGreaterThan(brightness, 0.4)
    }
}

final class CountryChartFallbackTests: XCTestCase {
    private let legacy = Data(#"{"feed":{"entry":[{"id":{"attributes":{"im:id":"123"}},"im:name":{"label":"Song"},"im:artist":{"label":"Artist"},"im:image":[{"label":"https://example.com/100x100bb.jpg"}]}]}}"#.utf8)
    func testUSFailureFallsBackToTheSameCountryAndParsesLegacyShape() async throws {
        let legacy = legacy
        let catalog = MusicCatalog(chartProvider: { url in
            XCTAssertTrue(url.path.contains("/us/")); XCTAssertTrue(url.path.contains("100"))
            if url.host == "rss.marketingtools.apple.com" { throw URLError(.timedOut) }
            XCTAssertEqual(url.host, "itunes.apple.com"); return legacy
        })
        let tracks = try await catalog.chart(.unitedStates)
        XCTAssertEqual(tracks.map(\.id), ["catalog:123"]); XCTAssertEqual(tracks.first?.title, "Song")
    }
    func testCancellationNeverStartsFallbackRequest() async {
        let catalog = MusicCatalog(chartProvider: { url in
            XCTAssertEqual(url.host, "rss.marketingtools.apple.com"); throw CancellationError()
        })
        do { _ = try await catalog.chart(.unitedStates); XCTFail("cancelled") } catch { XCTAssertTrue(error is CancellationError) }
    }
}
