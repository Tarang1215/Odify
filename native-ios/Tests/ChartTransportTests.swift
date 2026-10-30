import Foundation
import XCTest
@testable import Odify

private final class ChartFixture: URLProtocol {
    static var requestSeen: URLRequest?
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "rss.marketingtools.apple.com" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requestSeen = request
        let data = Data(#"{"feed":{"results":[{"id":"fixture"}]}}"#.utf8)
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
                                       headerFields: ["Content-Type": "application/json", "Content-Length": String(data.count)])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

final class ChartTransportTests: XCTestCase {
    func testPublicChartUsesJsonAppIdentityAndNoCredentials() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ChartFixture.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        ChartFixture.requestSeen = nil
        let url = URL(string: "https://rss.marketingtools.apple.com/api/v2/kr/music/most-played/50/songs.json")!
        let data = try await MusicHTTP.chartData(url, using: session)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertNotNil(json["feed"])
        let request = try XCTUnwrap(ChartFixture.requestSeen)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "application/json")
        XCTAssertEqual(request.value(forHTTPHeaderField: "User-Agent"), "Odify/1.1 (iOS)")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization")); XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        XCTAssertFalse(request.httpShouldHandleCookies)
    }
}
