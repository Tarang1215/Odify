import Foundation

public enum YouTubeAudioFailure: Error, LocalizedError {
    case invalidID, invalidResponse, noAudio
    case temporarilyUnavailable
    case restricted(String)
    public var errorDescription: String? {
        switch self {
        case .invalidID: return "올바른 YouTube 영상이 아닙니다."
        case .invalidResponse: return "YouTube 재생 응답을 확인하지 못했습니다."
        case .noAudio: return "연결 가능한 오디오 주소가 없습니다."
        case .temporarilyUnavailable: return "YouTube 연결이 일시적으로 불안정합니다. 잠시 후 다시 시도해 주세요."
        case .restricted(let reason): return "YouTube에서 재생을 제한했습니다. " + reason
        }
    }
}

/// Anonymous public-video requests only. Never receives a Drive token, cookie,
/// private recording title or third-party proxy URL.
public enum YouTubeAudio {
    public static let userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    public static func url(videoID: String, session: URLSession = .shared) async throws -> URL {
        guard videoID.range(of: #"^[A-Za-z0-9_-]{11}$"#, options: .regularExpression) != nil else { throw YouTubeAudioFailure.invalidID }
        var request = URLRequest(url: URL(string: "https://www.youtube.com/youtubei/v1/player?prettyPrint=false")!, timeoutInterval: 12)
        request.httpMethod = "POST"
        request.httpShouldHandleCookies = false
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("https://www.youtube.com", forHTTPHeaderField: "Origin")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "context": ["client": ["clientName": "IOS", "clientVersion": "20.10.4", "deviceModel": "iPhone16,2", "hl": "en", "gl": "US"]],
            "videoId": videoID, "contentCheckOk": true, "racyCheckOk": true
        ])
        for attempt in 0..<2 {
            do {
                try Task.checkCancellation()
                let (data, response) = try await session.data(for: request)
                guard let http = response as? HTTPURLResponse, data.count < 8_000_000 else { throw YouTubeAudioFailure.invalidResponse }
                if [401, 403, 429].contains(http.statusCode) {
                    throw YouTubeAudioFailure.restricted("이 네트워크에서 익명 오디오 연결이 허용되지 않습니다 (\(http.statusCode)).")
                }
                if (500...599).contains(http.statusCode) { throw YouTubeAudioFailure.temporarilyUnavailable }
                guard http.statusCode == 200 else { throw YouTubeAudioFailure.invalidResponse }
                return try parse(data, videoID: videoID)
            } catch {
                try Task.checkCancellation()
                let transient: Bool
                if let failure = error as? URLError {
                    transient = [.timedOut, .networkConnectionLost, .cannotConnectToHost, .notConnectedToInternet].contains(failure.code)
                } else if case YouTubeAudioFailure.temporarilyUnavailable = error { transient = true }
                else { transient = false }
                guard attempt == 0, transient else { throw error }
                try await Task.sleep(nanoseconds: 700_000_000)
            }
        }
        throw YouTubeAudioFailure.temporarilyUnavailable
    }
    public static func validUntil(_ url: URL, now: Date = Date()) -> Date {
        let ceiling = now.addingTimeInterval(1800)
        guard let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "expire" })?.value,
              let seconds = TimeInterval(value), seconds.isFinite else { return ceiling }
        return min(ceiling, Date(timeIntervalSince1970: seconds).addingTimeInterval(-120))
    }
    public static func parse(_ data: Data, videoID: String) throws -> URL {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw YouTubeAudioFailure.invalidResponse }
        let status = root["playabilityStatus"] as? [String: Any]
        if let state = status?["status"] as? String, state != "OK" {
            throw YouTubeAudioFailure.restricted(status?["reason"] as? String ?? "이 영상의 재생 권한을 확인해 주세요.")
        }
        guard status?["status"] as? String == "OK", (root["videoDetails"] as? [String: Any])?["videoId"] as? String == videoID else { throw YouTubeAudioFailure.invalidResponse }
        let formats = (root["streamingData"] as? [String: Any])?["adaptiveFormats"] as? [[String: Any]] ?? []
        let audio = formats.filter { format in
            let mime = format["mimeType"] as? String ?? ""
            let track = format["audioTrack"] as? [String: Any]
            return (mime == "audio/mp4" || mime.hasPrefix("audio/mp4;")) && track?["audioIsDefault"] as? Bool != false
        }.sorted { ($0["bitrate"] as? Int ?? 0) > ($1["bitrate"] as? Int ?? 0) }
        for format in audio {
            guard let value = format["url"] as? String, let url = URL(string: value), url.scheme == "https",
                  url.host?.hasSuffix(".googlevideo.com") == true, url.user == nil, url.password == nil,
                  validUntil(url).timeIntervalSinceNow > 0 else { continue }
            return url
        }
        throw YouTubeAudioFailure.noAudio
    }
}
