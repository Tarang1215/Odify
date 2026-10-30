import AVFoundation
import JavaScriptCore
import UIKit
import XCTest
@testable import Odify

@MainActor private final class WebDriverFixture: YouTubeWebPlaying {
    var onEvent: ((UUID, YouTubeWebEvent) -> Void)?
    var attached = true
    var loads: [(String, UUID, Double)] = []
    var searches: [Track] = []
    var plays = 0, pauses = 0, stops = 0
    var seeks: [Double] = []
    var active: [Bool] = []
    func load(videoID: String, request: UUID, position: Double) { loads.append((videoID, request, position)) }
    func search(track: Track, request: UUID) { searches.append(track) }
    func play() { plays += 1 }
    func pause() { pauses += 1 }
    func seek(_ seconds: Double) { seeks.append(seconds) }
    func setActive(_ active: Bool) { self.active.append(active) }
    func stop() { stops += 1 }
    func event(_ value: YouTubeWebEvent, request: UUID? = nil) { onEvent?(request ?? loads.last!.1, value) }
}

@MainActor final class YouTubeWebPlaybackTests: XCTestCase {
    private let a = Track(id: "a", title: "A", artist: "Test", videoID: "abcdefghijk")
    private let b = Track(id: "b", title: "B", artist: "Test", videoID: "lmnopqrstuv")
    private let c = Track(id: "c", title: "C", artist: "Test", videoID: "01234567890")
    private func directory() throws -> URL {
        let value = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: value, withIntermediateDirectories: true); return value
    }
    private func waitForLoads(_ count: Int, _ driver: WebDriverFixture) async throws {
        for _ in 0..<100 {
            if driver.loads.count >= count { return }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTFail("web selection did not load")
    }
    func testWebMusicControlsReorderedQueueAndStaleEventsUseTheCurrentOccurrence() async throws {
        let root = try directory(), driver = WebDriverFixture()
        let engine = PlaybackEngine(documents: root, autoplay: false, youtubeWebPlayer: driver,
            sourceResolver: { _, _, _ in XCTFail("web YouTube must not resolve a direct audio URL"); throw URLError(.badURL) })
        defer { engine.shutdown(); try? FileManager.default.removeItem(at: root) }
        engine.play([a, b, c]); try await waitForLoads(1, driver)
        XCTAssertTrue(engine.usingYouTubeWebPlayer); XCTAssertTrue(engine.player.items().isEmpty)
        driver.event(.state(1, 12, 180, true))
        XCTAssertTrue(engine.isPlaying); XCTAssertEqual(engine.clock.position, 12)
        engine.seek(45); XCTAssertEqual(driver.seeks.last, 45)
        engine.pause(); driver.event(.state(2, 45, 180, false)); XCTAssertFalse(engine.isPlaying)
        engine.resume(); XCTAssertEqual(driver.plays, 1)
        driver.event(.state(1, 45, 180, true))
        let oldRequest = driver.loads.last!.1
        engine.move(IndexSet(integer: 2), to: 1)
        driver.event(.state(0, 180, 180, true)); try await waitForLoads(2, driver)
        XCTAssertEqual(engine.current?.id, "c"); XCTAssertEqual(driver.loads.last?.0, c.videoID)
        driver.event(.state(0, 180, 180, true), request: oldRequest)
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(driver.loads.count, 2); XCTAssertEqual(engine.current?.id, "c")
    }
    func testBackgroundRendererEndAdvancesButUserPausePreventsAdvancement() async throws {
        let root = try directory(), driver = WebDriverFixture()
        let engine = PlaybackEngine(documents: root, autoplay: false, youtubeWebPlayer: driver)
        defer { engine.shutdown(); try? FileManager.default.removeItem(at: root) }
        engine.play([a, b, c]); try await waitForLoads(1, driver)
        driver.event(.state(1, 5, 100, true))
        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertEqual(driver.active.last, false)
        driver.event(.state(0, 100, 100, true)); try await waitForLoads(2, driver)
        XCTAssertEqual(engine.current?.id, "b")
        engine.pause(); driver.event(.state(0, 100, 100, false))
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertEqual(driver.loads.count, 2); XCTAssertFalse(engine.isPlaying)
        // This simulates delivery of WebKit events, not physical suspension.
    }
    func testPlayerFailureExitsLoadingAndDoesNotChangeSelectedRecording() async throws {
        let root = try directory(), driver = WebDriverFixture()
        let engine = PlaybackEngine(documents: root, autoplay: false, youtubeWebPlayer: driver)
        defer { engine.shutdown(); try? FileManager.default.removeItem(at: root) }
        engine.play([a, b]); try await waitForLoads(1, driver)
        driver.event(.failed(150))
        XCTAssertTrue(engine.error?.contains("150") == true); XCTAssertFalse(engine.isBuffering)
        XCTAssertEqual(engine.current?.id, "a"); XCTAssertEqual(driver.loads.count, 1)
    }
    func testMixedWebLocalWebQueueReturnsToTheSameMusicControls() async throws {
        let root = try directory(), driver = WebDriverFixture()
        let original = try XCTUnwrap(Bundle(for: YouTubeWebPlaybackTests.self).url(forResource: "silence-320k", withExtension: "mp3"))
        try FileManager.default.copyItem(at: original, to: root.appendingPathComponent("local.mp3"))
        let local = Track(id: "local", title: "Local", artist: "Test", source: .local, localFile: "local.mp3", duration: 4)
        let engine = PlaybackEngine(documents: root, autoplay: false, youtubeWebPlayer: driver)
        defer { engine.shutdown(); try? FileManager.default.removeItem(at: root) }
        engine.player.volume = 0
        engine.play([a, local, b]); try await waitForLoads(1, driver)
        driver.event(.state(1, 179, 180, true)); driver.event(.state(0, 180, 180, true))
        for _ in 0..<200 {
            if engine.current?.id == "local", engine.player.rate > 0 { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertEqual(engine.current?.id, "local"); XCTAssertFalse(engine.usingYouTubeWebPlayer)
        XCTAssertGreaterThan(engine.player.rate, 0)
        engine.seek(3.7); try await waitForLoads(2, driver)
        XCTAssertTrue(engine.usingYouTubeWebPlayer); XCTAssertEqual(engine.current?.id, "b")
        XCTAssertEqual(driver.loads.last?.0, b.videoID)
    }
    func testCatalogMatchingKeepsArtistIdentityAndRejectsLiveOrCoverVariants() {
        let track = Track(id: "catalog", title: "One Song", artist: "Test Artist")
        let videos = [
            VideoResult(id: "abcdefghijk", title: "One Song official audio", channel: "Different Artist"),
            VideoResult(id: "lmnopqrstuv", title: "Test Artist One Song live", channel: "Test Artist"),
            VideoResult(id: "01234567890", title: "One Song", channel: "Test Artist - Topic")
        ]
        XCTAssertEqual(YouTubeCatalog.matchingVideo(track, in: videos)?.id, "01234567890")
    }
    func testGeneratedPlayerCommandsRespectPauseBlockedAutoplayAndNewRequestTags() throws {
        let token = UUID(), next = UUID(), context = try XCTUnwrap(JSContext())
        context.evaluateScript("""
        var window=this,messages=[];window.webkit={messageHandlers:{odify:{postMessage:function(v){messages.push(v)}}}};
        function setInterval(){};var fakeState=-1,current=0,options,video;
        var YT={Player:function(id,o){options=o;this.getPlayerState=function(){return fakeState};this.getCurrentTime=function(){return current};
        this.getDuration=function(){return 180};this.playVideo=function(){};this.pauseVideo=function(){};
        this.seekTo=function(t){current=t};this.loadVideoById=function(v){video=v.videoId};}};
        """)
        let html = YouTubeWebPlayback.html(videoID: "abcdefghijk", origin: "https://com.odify.music.ios", request: token, position: 12, visible: true)
        let script = try XCTUnwrap(html.components(separatedBy: "<script>").last?.components(separatedBy: "</script>").first)
        context.evaluateScript(script)
        XCTAssertNil(context.exception)
        context.evaluateScript("onYouTubeIframeAPIReady();options.events.onReady();odifyPause();odifyPlay();options.events.onStateChange({data:2});snapshot()")
        XCTAssertEqual(context.evaluateScript("messages[messages.length-1].intent")?.toBool(), true)
        context.evaluateScript("options.events.onAutoplayBlocked();snapshot()")
        XCTAssertEqual(context.evaluateScript("messages[messages.length-1].intent")?.toBool(), false)
        context.evaluateScript("odifyLoad('lmnopqrstuv','\(next.uuidString)',0);snapshot()")
        XCTAssertEqual(context.evaluateScript("messages[messages.length-1].request")?.toString(), next.uuidString)
        XCTAssertEqual(context.evaluateScript("video")?.toString(), "lmnopqrstuv")
        XCTAssertNil(context.exception)
    }
}
