import JavaScriptCore
import XCTest
@testable import Odify

final class YouTubeSearchTests: XCTestCase {
    private func mobile(_ id: String = "IXaOUXVawN4", title: String = "IU 'Love wins all' Lyrics (아이유 Love wins all 가사)") -> [String: Any] {
        // Reduced public iPad response captured on 2026-10-31. No cookies,
        // tracking parameters, viewer/account fields or private metadata.
        ["videoWithContextRenderer": ["videoId": id,
            "headline": ["runs": [["text": title]]],
            "shortBylineText": ["runs": [["text": "Jaeguchi"]]]]]
    }
    func testActualIPadCardShapeResolvesArtistAndTitleToAStableVideoID() throws {
        let videos = YouTubeCatalog.videoResults(["contents": [mobile()]])
        XCTAssertEqual(videos.count, 1)
        XCTAssertEqual(videos.first?.channel, "Jaeguchi")
        let track = Track(id: "catalog", title: "Love wins all", artist: "IU")
        XCTAssertEqual(YouTubeCatalog.matchingVideo(track, in: videos)?.id, "IXaOUXVawN4")
        XCTAssertEqual(YouTubeCatalog.matchingVideo(track, in: videos)?.track(catalog: track).title, track.title)
    }
    func testMobileJSONEncodedAsAStringIsParsedWithoutEvaluatingIt() throws {
        let data = try JSONSerialization.data(withJSONObject: ["contents": [mobile()]])
        let encoded = String(decoding: data, as: UTF8.self)
        XCTAssertEqual(YouTubeCatalog.videoResults(encoded).first?.id, "IXaOUXVawN4")
        XCTAssertTrue(YouTubeCatalog.videoResults("alert('not JSON')").isEmpty)
    }
    func testCompactAndDesktopCardsPreserveIdentityAndDeduplicate() {
        let row: [String: Any] = ["videoId": "abcdefghijk", "title": ["simpleText": "Song"],
                                  "longBylineText": ["runs": [["text": "Artist - Topic"]]]]
        let videos = YouTubeCatalog.videoResults(["contents": [["compactVideoRenderer": row], ["videoRenderer": row]]])
        XCTAssertEqual(videos.map(\.id), ["abcdefghijk"])
        XCTAssertEqual(videos.first?.title, "Song")
        XCTAssertEqual(videos.first?.channel, "Artist - Topic")
    }
    func testLockupVideoCardsDoNotTreatPlaylistsOrChannelNamesAsSongs() {
        let row: [String: Any] = ["contentId": "abcdefghijk", "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
            "metadata": ["lockupMetadataViewModel": ["title": ["content": "Song"],
                "metadata": ["contentMetadataViewModel": ["metadataRows": [
                    ["metadataParts": [["text": ["content": "Artist - Topic"]]]]
                ]]]]]]
        var playlist = row; playlist["contentType"] = "LOCKUP_CONTENT_TYPE_PLAYLIST"; playlist["contentId"] = "01234567890"
        let videos = YouTubeCatalog.videoResults(["contents": [["lockupViewModel": row], ["lockupViewModel": playlist]]])
        XCTAssertEqual(videos.map(\.id), ["abcdefghijk"])
        XCTAssertEqual(videos.first?.channel, "Artist - Topic")
    }
    func testMobileMatchingRejectsWrongArtistCoverLiveAndInvalidIDs() {
        let videos = YouTubeCatalog.videoResults(["contents": [
            mobile("abcdefghijk", title: "Different Artist Love wins all lyrics"),
            mobile("lmnopqrstuv", title: "IU Love wins all cover"),
            mobile("01234567890", title: "IU Love wins all live"),
            mobile("invalid", title: "IU Love wins all lyrics"), mobile()
        ]])
        let track = Track(id: "catalog", title: "Love wins all", artist: "IU")
        XCTAssertEqual(YouTubeCatalog.matchingVideo(track, in: videos)?.id, "IXaOUXVawN4")
        XCTAssertNil(YouTubeCatalog.matchingVideo(Track(id: "blank", title: "", artist: ""), in: videos))
    }
    func testAdsAndNestedMenuWatchEndpointsAreNotSearchResults() {
        let json: [String: Any] = ["contents": [["adSlotRenderer": ["content": mobile()]],
            ["watchEndpoint": ["videoId": "abcdefghijk"]]]]
        XCTAssertTrue(YouTubeCatalog.videoResults(json).isEmpty)
    }
    func testBothSearchQueriesIncludeArtistAndTitleAndLyricsIsOnlyASearchHint() {
        let track = Track(id: "catalog", title: "Love wins all", artist: "IU")
        XCTAssertEqual(YouTubeSearchParser.queries(for: track), ["IU Love wins all official audio", "IU Love wins all lyrics"])
        XCTAssertNil(track.lyrics)
    }
    @MainActor func testWebSnapshotHandlesMobileStringDesktopObjectAndPendingHydration() throws {
        let context = try XCTUnwrap(JSContext())
        context.evaluateScript("var window=this; var ytInitialData;")
        XCTAssertTrue(context.evaluateScript(YouTubeWebPlayback.searchSnapshotScript)?.isNull == true)
        let data = try JSONSerialization.data(withJSONObject: ["contents": [mobile()]])
        let json = String(decoding: data, as: UTF8.self)
        let quoted = String(decoding: try JSONSerialization.data(withJSONObject: json, options: .fragmentsAllowed), as: UTF8.self)
        for assignment in [json, quoted] {
            context.evaluateScript("window.ytInitialData=" + assignment)
            let text = try XCTUnwrap(context.evaluateScript(YouTubeWebPlayback.searchSnapshotScript)?.toString())
            let object = try JSONSerialization.jsonObject(with: Data(text.utf8))
            XCTAssertEqual(YouTubeCatalog.videoResults(object).first?.id, "IXaOUXVawN4")
        }
        XCTAssertNil(context.exception)
    }
    func testSearchFailureMessagesDistinguishNoMatchUnreadableAndUserConfirmation() {
        XCTAssertTrue(YouTubeWebFailure(code: -2).localizedDescription.contains("가수와 곡명"))
        XCTAssertTrue(YouTubeWebFailure(code: -3).localizedDescription.contains("읽지 못했습니다"))
        XCTAssertTrue(YouTubeWebFailure(code: -4).localizedDescription.contains("사용자 확인"))
    }
}
