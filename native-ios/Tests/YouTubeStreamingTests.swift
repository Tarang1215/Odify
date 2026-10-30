import AVFoundation
import XCTest
import OdifyStreaming
@testable import Odify

private actor HeldSuccessor {
    private var held: CheckedContinuation<Void, Never>?
    private var attempts = 0
    private let denied: Bool
    init(denied: Bool) { self.denied = denied }
    var isWaiting: Bool { held != nil }
    func resolve(_ track: Track, file: URL) async throws -> URL {
        if track.id == "b" {
            attempts += 1
            if attempts == 1 {
                // Deliberately finish after cancellation, as a URLSession
                // delegate completion can outlive the task that requested it.
                await withCheckedContinuation { held = $0 }
                if denied { throw AudioHTTPFailure(statusCode: 403) }
                throw URLError(.cancelled)
            }
        }
        return file
    }
    func release() { held?.resume(); held = nil }
}

private final class AudioRangeFixture: URLProtocol {
    static var media = Data()
    static var status = 206
    static var requests: [URLRequest] = []
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "media.googlevideo.com" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        if Self.status != 206 {
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: Self.status, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self); return
        }
        let parts = request.value(forHTTPHeaderField: "Range")?.replacingOccurrences(of: "bytes=", with: "").split(separator: "-") ?? []
        guard parts.count == 2, let start = Int(parts[0]), let last = Int(parts[1]), start >= 0, start < Self.media.count else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
        }
        let end = min(last, Self.media.count - 1)
        let data = Self.media.subdata(in: start..<(end + 1))
        let headers = ["Content-Type": "audio/mp4", "Content-Length": String(data.count), "Content-Range": "bytes \(start)-\(end)/\(Self.media.count)"]
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil, headerFields: headers)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

@MainActor final class YouTubeStreamingTests: XCTestCase {
    func testCancelledOldPrefetchDoesNotShowErrorOrRemoveSuccessor() async throws {
        try await verifyObsoletePrefetch(denied: false)
    }
    func testLateRealFailureFromOldPrefetchCannotOverwriteReplacementQueue() async throws {
        try await verifyObsoletePrefetch(denied: true)
    }
    func testNetworkCancellationRecognitionPreservesOtherFailures() {
        XCTAssertTrue(PlaybackEngine.isCancelledRequest(CancellationError()))
        XCTAssertTrue(PlaybackEngine.isCancelledRequest(URLError(.cancelled)))
        XCTAssertTrue(PlaybackEngine.isCancelledRequest(NSError(domain: "Outer", code: 1,
            userInfo: [NSUnderlyingErrorKey: URLError(.cancelled)])))
        XCTAssertFalse(PlaybackEngine.isCancelledRequest(URLError(.timedOut)))
        XCTAssertFalse(PlaybackEngine.isCancelledRequest(AudioHTTPFailure(statusCode: 403)))
    }
    func testCurrentPrefetchDenialIsReportedAndTracedWithoutReplacingPlayingSong() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("fixture.m4a"); try aac(file)
        let engine = PlaybackEngine(documents: directory, autoplay: false, sourceResolver: { track, _, _ in
            if track.id == "b" { throw AudioHTTPFailure(statusCode: 403) }
            return file
        })
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play([Track(id: "a", title: "A", artist: "Test"), Track(id: "b", title: "B", artist: "Test")])
        for _ in 0..<100 {
            if engine.error?.contains("403") == true { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        engine.pause()
        XCTAssertTrue(engine.error?.contains("403") == true, engine.error ?? "real denial was lost")
        XCTAssertEqual(engine.current?.id, "a")
        XCTAssertNotNil(engine.player.currentItem)
        let data = try Data(contentsOf: directory.appendingPathComponent("odify-playback-diagnostics.json"))
        let entries = try JSONDecoder().decode([PlaybackTrace.Entry].self, from: data)
        XCTAssertTrue(entries.contains { $0.stage == "next-prepare-failed" && $0.source == "YouTube" && $0.codes.contains("com.odify.audio.http 403") })
    }
    private func verifyObsoletePrefetch(denied: Bool) async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("fixture.m4a"); try aac(file)
        let gate = HeldSuccessor(denied: denied)
        let engine = PlaybackEngine(documents: directory, autoplay: false, sourceResolver: { track, _, _ in
            try await gate.resolve(track, file: file)
        })
        defer { engine.shutdown() }
        engine.player.volume = 0
        func track(_ id: String) -> Track { Track(id: id, title: id.uppercased(), artist: "Test") }
        engine.play([track("a"), track("b")])
        for _ in 0..<150 {
            if await gate.isWaiting { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        let waiting = await gate.isWaiting
        guard waiting else { await gate.release(); XCTFail("next request never started"); return }
        engine.pause()
        let current = engine.player.currentItem
        engine.enqueue(track("c"))
        for _ in 0..<150 {
            if engine.preparedEntryIDs.count == 3 { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        await gate.release()
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertNil(engine.error, engine.error ?? "")
        // A later rebuild must still include B; the obsolete request must not
        // put it in the failed-ID set after its replacement was prepared.
        engine.enqueue(track("d"))
        for _ in 0..<100 {
            if engine.preparedEntryIDs.count == 3 { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertEqual(engine.preparedEntryIDs, Array(engine.queue.entries.prefix(3)).map(\.id))
        XCTAssertTrue(engine.player.currentItem === current)
        XCTAssertEqual(engine.player.rate, 0, "queue maintenance must respect pause")
        engine.next()
        for _ in 0..<150 {
            if engine.current?.id == "b", engine.player.currentItem?.status == .readyToPlay { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertEqual(engine.current?.id, "b")
        XCTAssertEqual(engine.player.currentItem?.status, .readyToPlay)
        XCTAssertNil(engine.error, engine.error ?? "")
    }
    private func session() -> URLSession {
        AudioRangeFixture.requests = []; AudioRangeFixture.status = 206
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [AudioRangeFixture.self]
        return URLSession(configuration: config)
    }
    func testVerifiedAudioRangesStartSeekAndAdvanceInRealQueueEngine() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("fixture.m4a"); try aac(file)
        AudioRangeFixture.media = try Data(contentsOf: file)
        let http = session(); defer { http.invalidateAndCancel() }
        let engine = PlaybackEngine(documents: directory, autoplay: false, youtubeSession: http, sourceResolver: { _, _, _ in
            URL(string: "https://media.googlevideo.com/audio?expire=9999999999")!
        })
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play([Track(id: "a", title: "A", artist: "Test"), Track(id: "b", title: "B", artist: "Test")])
        for _ in 0..<250 {
            if engine.player.rate > 0, engine.preparedEntryIDs.count == 2 { break }
            try await Task.sleep(for: .milliseconds(30))
        }
        XCTAssertGreaterThan(engine.player.rate, 0, engine.error ?? "range playback did not start")
        let seek = await engine.player.seek(to: CMTime(seconds: 1.4, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        XCTAssertTrue(seek)
        for _ in 0..<200 {
            if engine.current?.id == "b", engine.player.rate > 0 { break }
            try await Task.sleep(for: .milliseconds(30))
        }
        XCTAssertEqual(engine.current?.id, "b", engine.error ?? "did not advance")
        XCTAssertGreaterThan(engine.player.rate, 0)
        XCTAssertFalse(AudioRangeFixture.requests.isEmpty)
        for request in AudioRangeFixture.requests {
            XCTAssertEqual(request.value(forHTTPHeaderField: "User-Agent"), YouTubeAudio.userAgent)
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization")); XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
            XCTAssertNotNil(request.value(forHTTPHeaderField: "Range"))
        }
    }
    func testDeniedMediaResponseDoesNotRetryAnotherClientOrSkipSelectedSong() async throws {
        let http = session(); AudioRangeFixture.status = 403; defer { http.invalidateAndCancel() }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let engine = PlaybackEngine(documents: directory, autoplay: false, youtubeSession: http, sourceResolver: { _, _, _ in URL(string: "https://media.googlevideo.com/denied")! })
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play([Track(id: "a", title: "A", artist: "Test"), Track(id: "b", title: "B", artist: "Test")])
        for _ in 0..<100 {
            if engine.error != nil { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertTrue(engine.error?.contains("403") == true, engine.error ?? "missing denial")
        XCTAssertEqual(engine.current?.id, "a"); XCTAssertTrue(engine.player.items().isEmpty)
        XCTAssertEqual(AudioRangeFixture.requests.count, 1)
    }
    func testPersistentTraceContainsOnlyStagesSourcesAndErrorCodes() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let trace = PlaybackTrace(documents: root)
        for _ in 0..<85 {
            trace.record("failed", source: .youtube, error: NSError(domain: NSURLErrorDomain, code: -1,
                userInfo: [NSLocalizedDescriptionKey: "private-title", NSURLErrorFailingURLStringErrorKey: "https://private.example/?token=secret"]))
        }
        let data = try Data(contentsOf: root.appendingPathComponent("odify-playback-diagnostics.json"))
        let entries = try JSONDecoder().decode([PlaybackTrace.Entry].self, from: data)
        XCTAssertEqual(entries.count, 80)
        XCTAssertTrue(entries[0].codes.contains("NSURLErrorDomain -1"))
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertFalse(text.contains("private")); XCTAssertFalse(text.contains("secret")); XCTAssertFalse(text.contains("https://"))
    }
    private func aac(_ url: URL) throws {
        let file = try AVAudioFile(forWriting: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64000])
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 44100 * 2)); buffer.frameLength = buffer.frameCapacity
        buffer.floatChannelData?[0].initialize(repeating: 0, count: Int(buffer.frameLength))
        try file.write(from: buffer)
    }
}
