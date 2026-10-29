import XCTest
import OdifyStreaming

final class YouTubeAudioTests: XCTestCase {
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
