import AVFoundation
import XCTest
@testable import Odify

final class DriveModelTests: XCTestCase {
    func testFolderQueryAndPageTokenDoNotBecomeQueryInstructions() throws {
        let url = try DriveClient.listingURL(parent: "abc_123", page: "opaque+token/next==")
        let parameters = Dictionary(uniqueKeysWithValues: URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!.map { ($0.name, $0.value!) })
        XCTAssertEqual(parameters["q"], "'abc_123' in parents and trashed=false")
        XCTAssertEqual(parameters["pageToken"], "opaque+token/next==")
        XCTAssertThrowsError(try DriveClient.listingURL(parent: "abc' or trashed=true"))
    }
    func testDriveTrackRetainsAccountAndFileIdentityWithoutAccessTokens() throws {
        let json = #"{"files":[{"id":"folder1","name":"Music","mimeType":"application/vnd.google-apps.folder"},{"id":"song1","name":"Artist - Song.mp3","mimeType":"audio/mpeg","size":"12345","capabilities":{"canDownload":true}}],"nextPageToken":"next"}"#
        let page = try JSONDecoder().decode(DrivePage.self, from: Data(json.utf8))
        XCTAssertTrue(page.files[0].isFolder); XCTAssertTrue(page.files[1].playable)
        let a = page.files[1].track(account: DriveAccount.hash("account-a"))
        let b = page.files[1].track(account: DriveAccount.hash("account-b"))
        XCTAssertNotEqual(a.id, b.id)
        XCTAssertEqual(a.title, "Song"); XCTAssertEqual(a.artist, "Artist")
        XCTAssertNil(a.localFile); XCTAssertNil(a.videoID)
        XCTAssertEqual(a.driveFileID, "song1"); XCTAssertEqual(a.fileSize, 12345)
        let persisted = String(decoding: try JSONEncoder().encode(a), as: UTF8.self)
        XCTAssertFalse(persisted.contains("account-a")); XCTAssertFalse(persisted.contains("Bearer"))
    }
    func testRangeParserAndRedirectNeverLeakAuthorizationAcrossHosts() {
        XCTAssertEqual(DriveByteRange.parse("bytes 200-299/1000"), DriveByteRange(start: 200, end: 299, total: 1000))
        XCTAssertNil(DriveByteRange.parse("bytes 200-100/1000")); XCTAssertNil(DriveByteRange.parse("bytes 0-10/10"))
        var request = URLRequest(url: URL(string: "https://doc-1.googleusercontent.com/media")!)
        request.setValue("Bearer test-fixture", forHTTPHeaderField: "Authorization")
        let safe = DriveRedirects.safeRedirect(from: URL(string: "https://www.googleapis.com/drive/v3/files/x"), request: request)
        XCTAssertNotNil(safe); XCTAssertNil(safe?.value(forHTTPHeaderField: "Authorization"))
        request.url = URL(string: "https://untrusted.example/media")!
        XCTAssertNil(DriveRedirects.safeRedirect(from: nil, request: request))
    }
}

private final class DriveMockProtocol: URLProtocol {
    static let lock = NSLock()
    static var requests: [URLRequest] = []
    static var media = Data()
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "www.googleapis.com" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); Self.requests.append(request); let data = Self.media; Self.lock.unlock()
        if request.url?.path.hasSuffix("/broken-file") == true {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotDecodeContentData)); return
        }
        let range = request.value(forHTTPHeaderField: "Range")?.replacingOccurrences(of: "bytes=", with: "").split(separator: "-") ?? []
        guard range.count == 2, let begin = Int(range[0]), let end = Int(range[1]), begin >= 0, end < data.count, end >= begin else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
        }
        let headers = ["Content-Range": "bytes \(begin)-\(end)/\(data.count)", "Content-Length": String(end - begin + 1), "Content-Type": "audio/wav", "Accept-Ranges": "bytes"]
        let response = HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data.subdata(in: begin..<(end + 1)))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

@MainActor final class DriveStreamingTests: XCTestCase {
    func testMP3DriveStartsSeeksAndAutomaticallyPlaysTheNextSong() async throws {
        let bundle = Bundle(for: DriveStreamingTests.self)
        let url = try XCTUnwrap(bundle.url(forResource: "silence-320k", withExtension: "mp3"), "generated MP3 fixture missing")
        let media = try Data(contentsOf: url)
        DriveMockProtocol.lock.lock(); DriveMockProtocol.media = media; DriveMockProtocol.requests = []; DriveMockProtocol.lock.unlock()
        let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [DriveMockProtocol.self]
        let client = DriveClient(session: URLSession(configuration: configuration), token: { _ in "fixture-token" })
        let tracks = (0..<2).map {
            Track(id: "mp3:\($0)", title: "MP3 \($0)", artist: "Test", source: .drive, duration: 4,
                  driveFileID: "fixture-file", driveAccountHash: "fixture-account", fileSize: Int64(media.count), mimeType: "audio/mpeg", fileExtension: "mp3")
        }
        let engine = PlaybackEngine(documents: FileManager.default.temporaryDirectory, autoplay: false, driveClient: client)
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play(tracks)
        for _ in 0..<300 {
            if engine.player.rate > 0, engine.player.currentItem?.status == .readyToPlay { break }
            try await Task.sleep(for: .milliseconds(30))
        }
        XCTAssertGreaterThan(engine.player.rate, 0, engine.error ?? "MP3 never started")
        let seek = await engine.player.seek(to: CMTime(seconds: 2.5, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        XCTAssertTrue(seek)
        XCTAssertEqual(engine.player.currentTime().seconds, 2.5, accuracy: 0.2)
        for _ in 0..<300 {
            if engine.current?.id == "mp3:1", engine.player.rate > 0 { break }
            try await Task.sleep(for: .milliseconds(30))
        }
        XCTAssertEqual(engine.current?.id, "mp3:1", engine.error ?? "MP3 queue did not advance")
        XCTAssertGreaterThan(engine.player.rate, 0)
    }
    func testFailedRemoteCurrentRecoversToNextPlayableStream() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("fixture.m4a"); try Self.aac(file)
        let media = try Data(contentsOf: file)
        DriveMockProtocol.lock.lock(); DriveMockProtocol.media = media; DriveMockProtocol.requests = []; DriveMockProtocol.lock.unlock()
        let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [DriveMockProtocol.self]
        let client = DriveClient(session: URLSession(configuration: configuration), token: { _ in "fixture-token" })
        let good = Track(id: "good", title: "Good", artist: "Test", source: .drive, driveFileID: "fixture-file", driveAccountHash: "fixture-account", fileSize: Int64(media.count), mimeType: "audio/mp4", fileExtension: "m4a")
        var bad = good; bad.id = "bad"; bad.title = "Broken"; bad.driveFileID = "broken-file"
        let engine = PlaybackEngine(documents: directory, autoplay: false, driveClient: client)
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play([bad, good])
        for _ in 0..<500 {
            if engine.current?.id == "good", engine.player.rate > 0 { break }
            try await Task.sleep(for: .milliseconds(40))
        }
        XCTAssertEqual(engine.current?.id, "good", engine.error ?? "remote failure poisoned player")
        XCTAssertGreaterThan(engine.player.rate, 0, engine.error ?? "recovery did not resume")
        XCTAssertEqual(engine.player.volume, 0, "recreated players must preserve mute")
    }
    func testCompressedDriveAudioStartsAndAdvancesUsingActualPlaybackEngine() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("fixture.m4a")
        try Self.aac(file)
        let media = try Data(contentsOf: file)
        DriveMockProtocol.lock.lock(); DriveMockProtocol.media = media; DriveMockProtocol.requests = []; DriveMockProtocol.lock.unlock()
        let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [DriveMockProtocol.self]
        let client = DriveClient(session: URLSession(configuration: configuration), token: { _ in "fixture-token-not-a-real-credential" })
        let tracks = (0..<3).map {
            Track(id: "drive:\($0)", title: "AAC \($0)", artist: "Test", source: .drive,
                  driveFileID: "fixture-file", driveAccountHash: "fixture-account", fileSize: Int64(media.count), mimeType: "audio/mp4", fileExtension: "m4a")
        }
        let engine = PlaybackEngine(documents: directory, autoplay: false, driveClient: client)
        defer { engine.shutdown() }; engine.player.volume = 0
        engine.play(tracks)
        for _ in 0..<500 {
            if engine.current?.id == "drive:2", engine.player.rate > 0 { break }
            try await Task.sleep(for: .milliseconds(40))
        }
        XCTAssertEqual(engine.current?.id, "drive:2", engine.error ?? "compressed Drive playback never advanced")
        XCTAssertGreaterThan(engine.player.rate, 0, engine.error ?? "AAC did not start")
        XCTAssertTrue(engine.diagnostics.contains { $0.hasPrefix("advanced:") })
    }
    private static func aac(_ url: URL) throws {
        let file = try AVAudioFile(forWriting: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64000])
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 44100 * 2))
        buffer.frameLength = buffer.frameCapacity
        for channel in 0..<Int(buffer.format.channelCount) {
            buffer.floatChannelData?[channel].initialize(repeating: 0, count: Int(buffer.frameLength))
        }
        try file.write(from: buffer)
    }
    func testAuthenticatedRangeLoaderReadsNativeAssetAndSeeksWithoutDownload() async throws {
        let wave = Self.wave()
        DriveMockProtocol.lock.lock(); DriveMockProtocol.media = wave; DriveMockProtocol.requests = []; DriveMockProtocol.lock.unlock()
        let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [DriveMockProtocol.self]
        let client = DriveClient(session: URLSession(configuration: configuration), token: { expected in
            XCTAssertEqual(expected, "fixture-account"); return "fixture-token-not-a-real-credential"
        })
        let track = Track(id: "drive:fixture", title: "Test", artist: "Test", source: .drive, driveFileID: "fixture-file", driveAccountHash: "fixture-account", fileSize: Int64(wave.count), mimeType: "audio/wav", fileExtension: "wav")
        let loader = DriveResourceLoader(track: track, client: client)
        defer { loader.cancel() }
        let asset = AVURLAsset(url: URL(string: "odify-drive://media/fixture-file.wav")!)
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        let playable = try await asset.load(.isPlayable)
        XCTAssertTrue(playable)
        let duration = try await asset.load(.duration)
        XCTAssertEqual(duration.seconds, 4, accuracy: 0.1)
        let player = AVPlayer(playerItem: AVPlayerItem(asset: asset)); player.volume = 0
        defer { player.pause(); player.replaceCurrentItem(with: nil) }
        for _ in 0..<100 {
            if player.currentItem?.status == .readyToPlay { break }
            try await Task.sleep(for: .milliseconds(30))
        }
        await player.seek(to: CMTime(seconds: 2, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        XCTAssertEqual(player.currentTime().seconds, 2, accuracy: 0.2)
        DriveMockProtocol.lock.lock(); let requests = DriveMockProtocol.requests; DriveMockProtocol.lock.unlock()
        XCTAssertFalse(requests.isEmpty)
        for request in requests {
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token-not-a-real-credential")
            XCTAssertNotNil(request.value(forHTTPHeaderField: "Range"))
            XCTAssertFalse(request.url!.absoluteString.contains("fixture-token"))
        }
    }
    private static func wave() -> Data {
        var data = Data()
        func string(_ value: String) { data.append(contentsOf: value.utf8) }
        func integer<T: FixedWidthInteger>(_ value: T) { var little = value.littleEndian; withUnsafeBytes(of: &little) { data.append(contentsOf: $0) } }
        let count: UInt32 = 8000 * 4 * 2
        string("RIFF"); integer(count + 36); string("WAVEfmt "); integer(UInt32(16)); integer(UInt16(1)); integer(UInt16(1))
        integer(UInt32(8000)); integer(UInt32(16000)); integer(UInt16(2)); integer(UInt16(16)); string("data"); integer(count)
        data.append(Data(repeating: 0, count: Int(count))); return data
    }
}
