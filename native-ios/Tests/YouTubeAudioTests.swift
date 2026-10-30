import XCTest
import OdifyStreaming

final class YouTubeAudioTests: XCTestCase {
    func testSignedStreamExpiryHasSafetyMarginAndNeverExceedsThirtyMinutes() throws {
        let now = Date(timeIntervalSince1970: 1000)
        XCTAssertEqual(YouTubeAudio.validUntil(URL(string: "https://media.googlevideo.com/a?expire=1400")!, now: now), Date(timeIntervalSince1970: 1280))
        XCTAssertEqual(YouTubeAudio.validUntil(URL(string: "https://media.googlevideo.com/a?expire=9000")!, now: now), Date(timeIntervalSince1970: 2800))
        XCTAssertEqual(YouTubeAudio.validUntil(URL(string: "https://media.googlevideo.com/a")!, now: now), Date(timeIntervalSince1970: 2800))
    }
    func testTransientServerFailureRetriesOnceButAccessRestrictionNeverRetries() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [YouTubeMockProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        YouTubeMockProtocol.configure([503, 200])
        let url = try await YouTubeAudio.url(videoID: "abcdefghijk", session: session)
        XCTAssertEqual(url.host, "media.googlevideo.com")
        XCTAssertEqual(YouTubeMockProtocol.requestCount, 2)
        YouTubeMockProtocol.configure([403, 200])
        do { _ = try await YouTubeAudio.url(videoID: "abcdefghijk", session: session); XCTFail("restriction ignored") }
        catch { guard case YouTubeAudioFailure.restricted = error else { return XCTFail("wrong restriction: \(error)") } }
        XCTAssertEqual(YouTubeMockProtocol.requestCount, 1)
    }
    func testOnlyMatchingVideoDefaultAudioAndTrustedHTTPSCanBeSelected() throws {
        let rows: [[String: Any]] = [
            ["mimeType": "video/mp4", "bitrate": 999_999, "url": "https://video.googlevideo.com/video"],
            ["mimeType": "audio/mp4", "bitrate": 900_000, "url": "https://untrusted.example/audio"],
            ["mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 300_000, "audioTrack": ["audioIsDefault": false], "url": "https://media.googlevideo.com/dubbed"],
            ["mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128_000, "url": "https://media.googlevideo.com/original"],
            ["mimeType": "audio/mp4", "bitrate": 48_000, "url": "https://media.googlevideo.com/low"]
        ]
        let data = try JSONSerialization.data(withJSONObject: ["playabilityStatus": ["status": "OK"], "videoDetails": ["videoId": "abcdefghijk"], "streamingData": ["adaptiveFormats": rows]])
        XCTAssertEqual(try YouTubeAudio.parse(data, videoID: "abcdefghijk").path, "/original")
        XCTAssertThrowsError(try YouTubeAudio.parse(data, videoID: "differentID"))
    }
    func testRestrictedPlayerResponseIsNotSilentlyPlayedThroughAnotherClient() throws {
        let data = try JSONSerialization.data(withJSONObject: ["playabilityStatus": ["status": "LOGIN_REQUIRED", "reason": "Sign in to confirm your age"]])
        XCTAssertThrowsError(try YouTubeAudio.parse(data, videoID: "abcdefghijk")) { error in
            guard case YouTubeAudioFailure.restricted = error else { return XCTFail("lost playback restriction") }
        }
    }
}

private final class YouTubeMockProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var statuses: [Int] = []
    private static var count = 0
    static var requestCount: Int { lock.lock(); defer { lock.unlock() }; return count }
    static func configure(_ values: [Int]) { lock.lock(); statuses = values; count = 0; lock.unlock() }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "www.youtube.com" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); let status = Self.statuses.isEmpty ? 500 : Self.statuses.removeFirst(); Self.count += 1; Self.lock.unlock()
        let data = Data(#"{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abcdefghijk"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","bitrate":128000,"url":"https://media.googlevideo.com/audio"}]}}"#.utf8)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}
