import AVFoundation
import UIKit
import XCTest
@testable import Odify

private final class BufferedAudioFixture: URLProtocol {
    enum Mode { case normal, oversized, timeout, denied, prefixOnly, noResponse }
    private static let lock = NSLock()
    private static var media = Data()
    private static var mode = Mode.normal
    private static var seen: [URLRequest] = []
    private let stateLock = NSLock()
    private var stopped = false
    static func reset(_ media: Data, mode: Mode = .normal) {
        lock.lock(); self.media = media; self.mode = mode; seen = []; lock.unlock()
    }
    static func setMode(_ mode: Mode) { lock.lock(); self.mode = mode; lock.unlock() }
    static var requests: [URLRequest] { lock.lock(); defer { lock.unlock() }; return seen }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "buffer.googlevideo.com" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); Self.seen.append(request); let media = Self.media, mode = Self.mode; Self.lock.unlock()
        guard mode != .noResponse else { return }
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.025) { [self] in
            stateLock.lock(); defer { stateLock.unlock() }
            guard !stopped else { return }
            if mode == .timeout { client?.urlProtocol(self, didFailWithError: URLError(.timedOut)); return }
            if mode == .denied || (mode == .prefixOnly && request.value(forHTTPHeaderField: "Range") != "bytes=0-32767") {
                client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 403, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
                client?.urlProtocolDidFinishLoading(self); return
            }
            let parts = request.value(forHTTPHeaderField: "Range")!.dropFirst(6).split(separator: "-")
            let start = Int(parts[0])!, end = min(Int(parts[1])!, media.count - 1)
            let headers = ["Content-Type": "audio/mp4", "Content-Length": String(end - start + 1),
                           "Content-Range": "bytes \(start)-\(end)/\(media.count)"]
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil, headerFields: headers)!, cacheStoragePolicy: .notAllowed)
            if mode == .oversized {
                client?.urlProtocol(self, didLoad: Data(repeating: 0, count: end - start + 2))
            } else {
                for offset in stride(from: start, through: end, by: 8192) {
                    client?.urlProtocol(self, didLoad: media.subdata(in: offset..<min(end + 1, offset + 8192)))
                }
            }
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() { stateLock.lock(); stopped = true; stateLock.unlock() }
}

@MainActor final class AudioBufferingTests: XCTestCase {
    private let url = URL(string: "https://buffer.googlevideo.com/media")!
    private func session(_ data: Data, mode: BufferedAudioFixture.Mode = .normal) -> URLSession {
        BufferedAudioFixture.reset(data, mode: mode)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [BufferedAudioFixture.self]
        return URLSession(configuration: config)
    }
    func testOverlappingReadsShareOneRequestAndCachedReadsNeedNoNetwork() async throws {
        let media = pattern(count: 2_097_152)
        let http = session(media); defer { http.invalidateAndCancel() }
        let source = try await YouTubeByteSource.open(url, session: http)
        async let a = source.range(start: 40_000, length: 90_000)
        async let b = source.range(start: 60_000, length: 60_000)
        let (first, second) = try await (a, b)
        XCTAssertEqual(first, media.subdata(in: 40_000..<130_000))
        XCTAssertEqual(second, media.subdata(in: 60_000..<120_000))
        XCTAssertEqual(BufferedAudioFixture.requests.count, 2, "one prefix and one shared block, without duplicate overlap")
        BufferedAudioFixture.setMode(.denied)
        let cached = try await source.range(start: 50_000, length: 40_000)
        XCTAssertEqual(cached, media.subdata(in: 50_000..<90_000))
        XCTAssertEqual(BufferedAudioFixture.requests.count, 2)
        await source.close()
    }
    func testWarmStartKeepsBeginningAndTailAvailableWhenNetworkDisappearsAndMemoryIsBounded() async throws {
        let media = pattern(count: 4_314_304)
        let http = session(media); defer { http.invalidateAndCancel() }
        let source = try await YouTubeByteSource.open(url, session: http)
        try await source.warmStart()
        let requests = BufferedAudioFixture.requests.count
        BufferedAudioFixture.setMode(.denied)
        let start = try await source.range(start: 0, length: 100_000)
        let end = try await source.range(start: Int64(media.count - 50_000), length: 50_000)
        XCTAssertEqual(start, media.prefix(100_000)); XCTAssertEqual(end, media.suffix(50_000))
        XCTAssertEqual(BufferedAudioFixture.requests.count, requests)
        BufferedAudioFixture.setMode(.normal)
        for index in 1..<15 { _ = try await source.range(start: Int64(index * 262_144), length: 262_144) }
        let retained = await source.bufferedByteCount
        XCTAssertLessThanOrEqual(retained, 4 * 262_144 + 32_768)
        await source.close()
        let closedBytes = await source.bufferedByteCount
        XCTAssertEqual(closedBytes, 0)
    }
    func testTimeoutCanBeRetriedButDeniedMediaIsNotAutomaticallyRetried() async throws {
        let media = Data(repeating: 7, count: 800_000)
        let http = session(media); defer { http.invalidateAndCancel() }
        let source = try await YouTubeByteSource.open(url, session: http)
        BufferedAudioFixture.setMode(.timeout)
        do { _ = try await source.range(start: 300_000, length: 1000); XCTFail("timeout was ignored") }
        catch { XCTAssertEqual((error as? URLError)?.code, .timedOut) }
        BufferedAudioFixture.setMode(.normal)
        let recovered = try await source.range(start: 300_000, length: 1000)
        XCTAssertEqual(recovered, Data(repeating: 7, count: 1000))
        BufferedAudioFixture.setMode(.denied)
        let before = BufferedAudioFixture.requests.count
        do { _ = try await source.range(start: 600_000, length: 1000); XCTFail("denial was ignored") }
        catch { XCTAssertEqual((error as? AudioHTTPFailure)?.statusCode, 403) }
        XCTAssertEqual(BufferedAudioFixture.requests.count, before + 1)
        await source.close()
    }
    func testOversizedBodyIsRejectedAndCancellationFinishesWithoutWaitingForTimeout() async throws {
        let http = session(Data(repeating: 0, count: 100_000), mode: .oversized)
        defer { http.invalidateAndCancel() }
        do { _ = try await YouTubeByteSource.open(url, session: http); XCTFail("oversized response accepted") }
        catch { XCTAssertTrue(error.localizedDescription.contains("길이")) }
        BufferedAudioFixture.setMode(.noResponse)
        let task = Task { try await YouTubeByteSource.open(url, session: http) }
        try await Task.sleep(for: .milliseconds(50))
        let before = Date(); task.cancel()
        do { _ = try await task.value; XCTFail("cancellation ignored") }
        catch { XCTAssertTrue(PlaybackEngine.isCancelledRequest(error)) }
        XCTAssertLessThan(Date().timeIntervalSince(before), 1)
    }
    func testDenialIsRememberedAcrossFurtherReadsAndDoesNotRefetchCachedPrefix() async throws {
        let http = session(Data(repeating: 7, count: 800_000)); defer { http.invalidateAndCancel() }
        let source = try await YouTubeByteSource.open(url, session: http)
        BufferedAudioFixture.setMode(.denied)
        for offset in [Int64(40_000), 0, 200_000] {
            do { _ = try await source.range(start: offset, length: 1000); XCTFail("a denied source returned bytes") }
            catch { XCTAssertEqual((error as? AudioHTTPFailure)?.statusCode, 403) }
        }
        XCTAssertEqual(BufferedAudioFixture.requests.count, 2, "only the prefix and the first denied request may reach the server")
        await source.close()
    }
    func testDeniedRangeImmediatelyStopsCurrentLoadingAndDoesNotScanTheQueue() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("denial.m4a"); try makeAudio(file)
        let http = session(try Data(contentsOf: file), mode: .prefixOnly)
        defer { http.invalidateAndCancel() }
        let engine = PlaybackEngine(documents: root, autoplay: false, youtubeSession: http,
            sourceResolver: { _, _, _ in URL(string: "https://buffer.googlevideo.com/media")! })
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play((0..<8).map { Track(id: "song\($0)", title: "Generated", artist: "Test") })
        for _ in 0..<200 {
            if engine.playbackRestricted, engine.error?.contains("403") == true { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertTrue(engine.playbackRestricted); XCTAssertFalse(engine.isBuffering); XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(engine.current?.id, "song0"); XCTAssertTrue(engine.player.items().isEmpty)
        XCTAssertNotNil(engine.externalYouTubeURL)
        try await Task.sleep(for: .milliseconds(100))
        let before = BufferedAudioFixture.requests.count
        engine.enqueue(Track(id: "new", title: "Generated", artist: "Test"))
        engine.appendRecommendations([Track(id: "recommended", title: "Generated", artist: "Test")])
        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(BufferedAudioFixture.requests.count, before)
        XCTAssertFalse(engine.canPrepareAutomatically)
    }
    func testDeniedSuccessorKeepsCurrentAudioAndStopsResolvingLaterCandidates() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("current.m4a"); try makeAudio(file)
        let calls = ResolutionCalls()
        let engine = PlaybackEngine(documents: root, autoplay: false, sourceResolver: { track, _, _ in
            await calls.record(track.id)
            if track.id == "b" { throw AudioHTTPFailure(statusCode: 403) }
            return file
        })
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play(["a", "b", "c", "d"].map { Track(id: $0, title: "Generated", artist: "Test") })
        for _ in 0..<150 {
            if engine.error?.contains("403") == true, engine.player.rate > 0 { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertEqual(engine.current?.id, "a"); XCTAssertGreaterThan(engine.player.rate, 0)
        engine.enqueue(Track(id: "e", title: "Generated", artist: "Test"))
        try await Task.sleep(for: .milliseconds(100))
        let ids = await calls.ids
        XCTAssertEqual(ids, ["a", "b"], "one denied successor must not trigger a scan of other recordings")
        engine.pause()
    }
    func testPreparedYouTubeSuccessorsAdvanceWithNetworkUnavailableAfterBackgroundNotification() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("generated.m4a")
        try makeAudio(file)
        let media = try Data(contentsOf: file)
        XCTAssertGreaterThan(media.count, 32_768, "fixture must require more than the initial prefix")
        let http = session(media); defer { http.invalidateAndCancel() }
        let engine = PlaybackEngine(documents: root, autoplay: false, youtubeSession: http,
            sourceResolver: { _, _, _ in URL(string: "https://buffer.googlevideo.com/media")! })
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play(["a", "b", "c"].map { Track(id: $0, title: "Generated", artist: "Test") })
        for _ in 0..<250 {
            if engine.preparedEntryIDs.count == 3, engine.player.rate > 0 { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        guard engine.preparedEntryIDs.count == 3 else { XCTFail(engine.error ?? "successors not prepared"); return }
        let prepared = engine.player.items(), before = BufferedAudioFixture.requests.count
        // Lifecycle notification coverage, not a physical suspension claim.
        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        BufferedAudioFixture.setMode(.denied)
        for (index, id) in ["b", "c"].enumerated() {
            engine.seek(3.7)
            for _ in 0..<180 {
                if engine.current?.id == id, engine.player.rate > 0 { break }
                try await Task.sleep(for: .milliseconds(20))
            }
            XCTAssertEqual(engine.current?.id, id, engine.error ?? "cached transition failed")
            XCTAssertGreaterThan(engine.player.rate, 0)
            XCTAssertTrue(engine.player.currentItem === prepared[index + 1])
        }
        XCTAssertEqual(BufferedAudioFixture.requests.count, before, "prepared transitions must not resolve/refetch after the connection is lost")
        XCTAssertNil(engine.error)
    }
    private func makeAudio(_ url: URL) throws {
        let file = try AVAudioFile(forWriting: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 128000])
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 44100 * 4))
        buffer.frameLength = buffer.frameCapacity
        var seed: UInt32 = 17
        for index in 0..<Int(buffer.frameLength) {
            seed = 1664525 &* seed &+ 1013904223
            buffer.floatChannelData?[0][index] = Float(Int32(bitPattern: seed)) / Float(Int32.max) * 0.02
        }
        try file.write(from: buffer)
    }
    private func pattern(count: Int) -> Data {
        var data = Data(count: count)
        data.withUnsafeMutableBytes { (raw: UnsafeMutableRawBufferPointer) in
            let bytes = raw.bindMemory(to: UInt8.self)
            for index in 0..<count { bytes[index] = UInt8(index % 251) }
        }
        return data
    }
    func testRepeatOneRefillsWhenPlayerItemChangesButQueueOccurrenceIsTheSame() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try makeAudio(root.appendingPathComponent("repeat.m4a"))
        let engine = PlaybackEngine(documents: root, autoplay: false)
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.cycleRepeat(); engine.cycleRepeat()
        engine.play([Track(id: "repeat", title: "Generated", artist: "Test", source: .local, localFile: "repeat.m4a")])
        for _ in 0..<150 {
            if engine.preparedEntryIDs.count == 3, engine.player.rate > 0 { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        let occurrence = engine.queue.currentID
        for _ in 0..<4 {
            let before = engine.player.currentItem
            engine.seek(3.7)
            for _ in 0..<200 {
                if engine.player.currentItem !== before, engine.preparedEntryIDs.count == 3, engine.player.rate > 0 { break }
                try await Task.sleep(for: .milliseconds(20))
            }
            XCTAssertTrue(engine.player.currentItem !== before)
            XCTAssertEqual(engine.queue.currentID, occurrence)
            XCTAssertEqual(engine.preparedEntryIDs.count, 3, "repeating must keep successors prepared")
        }
        XCTAssertEqual(engine.diagnostics.filter { $0.hasPrefix("advanced:") }.count, 4)
    }
}

private actor ResolutionCalls {
    private(set) var ids: [String] = []
    func record(_ id: String) { ids.append(id) }
}
