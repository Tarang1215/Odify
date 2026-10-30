import Foundation
@preconcurrency import YouTubeKit
import OdifyStreaming

enum MusicCountry: String, CaseIterable, Identifiable {
    case korea = "kr", japan = "jp", unitedStates = "us"
    var id: String { rawValue }
    var title: String { switch self { case .korea: return "한국"; case .japan: return "일본"; case .unitedStates: return "미국" } }
}

struct MusicHTTPFailure: LocalizedError, CustomNSError {
    var youtube: Bool
    var code: Int
    static var errorDomain: String { "com.odify.catalog.network" }
    var errorCode: Int { code }
    var errorDescription: String? {
        let service = youtube ? "YouTube 검색" : "음악 정보"
        if code == URLError.httpTooManyRedirects.rawValue { return "\(service) 응답이 반복해서 다른 주소로 이동했습니다 (\(code)). 잠시 후 다시 확인해 주세요." }
        if code == URLError.timedOut.rawValue { return "\(service) 연결 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요 (\(code))." }
        return "\(service)에 연결하지 못했습니다. 네트워크를 확인한 뒤 다시 시도해 주세요 (\(code))."
    }
    var errorUserInfo: [String: Any] { [NSLocalizedDescriptionKey: errorDescription ?? "음악 정보 연결 오류"] }
}

enum MusicHTTP {
    // Public chart JSON uses a dedicated connection pool and honest app
    // identity, independent of artwork downloads and HTML search requests.
    private static let chartSession: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 15; configuration.timeoutIntervalForResource = 20
        configuration.httpCookieStorage = nil; configuration.httpShouldSetCookies = false
        configuration.urlCache = URLCache(memoryCapacity: 2 * 1024 * 1024, diskCapacity: 0, diskPath: nil)
        return URLSession(configuration: configuration)
    }()
    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10; configuration.timeoutIntervalForResource = 15
        configuration.httpCookieStorage = nil; configuration.httpShouldSetCookies = false
        configuration.urlCache = URLCache(memoryCapacity: 4 * 1024 * 1024, diskCapacity: 0, diskPath: nil)
        return URLSession(configuration: configuration)
    }()
    static func data(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url, cachePolicy: .useProtocolCachePolicy, timeoutInterval: 10)
        request.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        return try await response(request, session: session)
    }
    static func chartData(_ url: URL, using session: URLSession? = nil) async throws -> Data {
        var request = URLRequest(url: url, cachePolicy: .useProtocolCachePolicy, timeoutInterval: 15)
        request.httpShouldHandleCookies = false
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Odify/1.1 (iOS)", forHTTPHeaderField: "User-Agent")
        return try await response(request, session: session ?? chartSession)
    }
    private static func response(_ request: URLRequest, session: URLSession) async throws -> Data {
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: request) }
        catch let error as URLError {
            try Task.checkCancellation()
            if error.code == .cancelled { throw CancellationError() }
            throw MusicHTTPFailure(youtube: request.url?.host == "www.youtube.com", code: error.code.rawValue)
        }
        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode), data.count < 12_000_000 else {
            throw OdifyError.message("음악 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")
        }
        return data
    }
    static func url(_ address: String, _ parameters: [String: String] = [:]) -> URL {
        var components = URLComponents(string: address)!
        if !parameters.isEmpty { components.queryItems = parameters.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) } }
        return components.url!
    }
}

actor MusicCatalog {
    static let shared = MusicCatalog()
    private var cache: [String: (Date, [Track])] = [:]
    private var portraits: [String: String] = [:]

    func search(_ query: String, country: MusicCountry = .korea) async throws -> [Track] {
        try await songs(MusicHTTP.url("https://itunes.apple.com/search", [
            "term": String(query.prefix(160)), "country": country.rawValue, "entity": "song", "limit": "60"
        ]))
    }
    func artist(_ track: Track) async throws -> [Track] {
        if let id = track.artistID {
            let result = try await songs(MusicHTTP.url("https://itunes.apple.com/lookup", ["id": id, "entity": "song", "limit": "200", "country": "us"]))
            if !result.isEmpty { return result }
        }
        for country in MusicCountry.allCases {
            let url = MusicHTTP.url("https://itunes.apple.com/search", ["term": track.artist, "attribute": "artistTerm", "entity": "song", "limit": "200", "country": country.rawValue])
            let found = try await songs(url)
            let exact = found.filter { Track.normalized($0.artist) == Track.normalized(track.artist) }
            if !exact.isEmpty { return exact }
            // A localized tag may use a different catalog spelling. Accept an
            // alias only when the artist query and known recording title agree
            // on a single catalog artist ID; never choose a fuzzy first result.
            let evidence = found.filter { Track.normalized($0.title) == Track.normalized(track.title) }
            let ids = Set(evidence.compactMap(\.artistID))
            if ids.count == 1, let id = ids.first { return found.filter { $0.artistID == id } }
        }
        return []
    }
    func chart(_ country: MusicCountry) async throws -> [Track] {
        let key = "chart:" + country.rawValue
        if let (date, value) = cache[key], Date().timeIntervalSince(date) < 3600 { return value }
        let data = try await MusicHTTP.chartData(MusicHTTP.url("https://rss.marketingtools.apple.com/api/v2/\(country.rawValue)/music/most-played/50/songs.json"))
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let feed = json?["feed"] as? [String: Any]
        let rows = feed?["results"] as? [[String: Any]] ?? []
        let value = Track.unique(rows.compactMap { row -> Track? in
            guard let id = row["id"] as? String, let title = row["name"] as? String, let artist = row["artistName"] as? String else { return nil }
            return Track(id: "catalog:" + id, title: title, artist: artist,
                         artwork: Self.largeArtwork(row["artworkUrl100"] as? String), artistID: row["artistId"] as? String, artistURL: row["artistUrl"] as? String)
        })
        guard !value.isEmpty else { throw OdifyError.message("차트를 불러오지 못했습니다.") }
        cache[key] = (Date(), value)
        return value
    }
    private func songs(_ url: URL) async throws -> [Track] {
        let key = url.absoluteString
        if let (date, value) = cache[key], Date().timeIntervalSince(date) < 3600 { return value }
        let data = try await MusicHTTP.data(url)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let rows = json?["results"] as? [[String: Any]] ?? []
        let value = Track.unique(rows.compactMap(Self.parseSong))
        if cache.count > 60 { cache.removeAll() }
        cache[key] = (Date(), value)
        return value
    }
    static func parseSong(_ row: [String: Any]) -> Track? {
        guard let id = row["trackId"] as? NSNumber, let title = row["trackName"] as? String,
              let artist = row["artistName"] as? String else { return nil }
        return Track(id: "catalog:" + id.stringValue, title: title, artist: artist, album: row["collectionName"] as? String ?? "",
                     artwork: largeArtwork(row["artworkUrl100"] as? String), artistID: (row["artistId"] as? NSNumber)?.stringValue,
                     artistURL: row["artistViewUrl"] as? String, duration: (row["trackTimeMillis"] as? Double ?? 0) / 1000)
    }
    static func largeArtwork(_ value: String?) -> String? { value?.replacingOccurrences(of: "100x100bb", with: "600x600bb") }

    func portrait(_ track: Track) async -> String? {
        guard let address = track.artistURL, let url = URL(string: address), url.host == "music.apple.com" else { return nil }
        if let cached = portraits[address] { return cached }
        do {
            let html = String(decoding: try await MusicHTTP.data(url), as: UTF8.self)
            let pattern = #"<meta[^>]+property="og:image"[^>]+content="([^"]+)"#
            let regex = try NSRegularExpression(pattern: pattern, options: .caseInsensitive)
            guard let match = regex.firstMatch(in: html, range: NSRange(html.startIndex..., in: html)),
                  let range = Range(match.range(at: 1), in: html) else { return nil }
            guard let value = Self.squarePortrait(String(html[range]).replacingOccurrences(of: "&amp;", with: "&")) else { return nil }
            portraits[address] = value
            return value
        } catch { return nil }
    }
    static func squarePortrait(_ value: String) -> String? {
        guard let image = URL(string: value), image.scheme == "https", image.host?.hasSuffix(".mzstatic.com") == true,
              image.path.contains("/image/thumb/"), image.path.range(of: #"/(AMCArtistImages|Features|Music)[^/]*\/"#, options: .regularExpression) != nil else { return nil }
        // OpenGraph's 1200x630cw is a social card with its own framing. Ask
        // the image service for the actual square portrait before downsampling.
        return image.deletingLastPathComponent().appendingPathComponent("600x600bb.jpg").absoluteString
    }
}

actor YouTubeCatalog {
    static let shared = YouTubeCatalog()
    private var cache: [String: [VideoResult]] = [:]
    func search(_ query: String) async throws -> [VideoResult] {
        let query = String(query.trimmingCharacters(in: .whitespacesAndNewlines).prefix(160))
        guard query.count >= 2 else { return [] }
        if let cached = cache[query] { return cached }
        let data = try await MusicHTTP.data(MusicHTTP.url("https://www.youtube.com/results", ["search_query": query, "hl": "en"]))
        let html = String(decoding: data, as: UTF8.self)
        guard let json = Self.initialData(html) else {
            throw OdifyError.message("YouTube 검색 응답을 읽지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.")
        }
        let value = Self.videoResults(json)
        guard !value.isEmpty else { throw OdifyError.message("재생 가능한 YouTube 검색 결과가 없습니다.") }
        if cache.count > 50 { cache.removeAll() }
        cache[query] = value
        return value
    }
    func match(_ track: Track) async throws -> VideoResult {
        for query in YouTubeSearchParser.queries(for: track) {
            let videos = try await search(query)
            if let result = Self.matchingVideo(track, in: videos) { return result }
        }
        throw OdifyError.message("정확한 재생 영상을 확인하지 못했습니다. 검색의 YouTube 결과에서 직접 선택해 주세요.")
    }
    static func matchingVideo(_ track: Track, in videos: [VideoResult]) -> VideoResult? {
        let title = Track.normalized(track.title), artist = Track.normalized(track.artist)
        guard !title.isEmpty, !artist.isEmpty else { return nil }
        let candidates = videos.filter { video in
            !video.isVariant && Track.normalized(video.title).contains(title) &&
            (Track.normalized(video.channel).contains(artist) || Track.normalized(video.title).contains(artist))
        }
        return candidates.sorted(by: { Self.score($0) > Self.score($1) }).first
    }
    private static func score(_ video: VideoResult) -> Int {
        let channel = video.channel.lowercased(), title = video.title.lowercased()
        return (channel.contains("topic") ? 5 : 0) + (channel.contains("official") ? 3 : 0) + (title.contains("audio") ? 2 : 0)
    }
    static func initialData(_ html: String) -> Any? {
        for marker in ["var ytInitialData =", "window[\"ytInitialData\"] =", "ytInitialData ="] {
            guard let markerRange = html.range(of: marker), let begin = html[markerRange.upperBound...].firstIndex(of: "{") else { continue }
            var depth = 0, quoted = false, escaped = false
            for index in html[begin...].indices {
                let char = html[index]
                if quoted {
                    if escaped { escaped = false } else if char == "\\" { escaped = true } else if char == "\"" { quoted = false }
                } else if char == "\"" { quoted = true }
                else if char == "{" { depth += 1 }
                else if char == "}" {
                    depth -= 1
                    if depth == 0 { return try? JSONSerialization.jsonObject(with: Data(html[begin...index].utf8)) }
                }
            }
        }
        return nil
    }
    static func videoResults(_ json: Any) -> [VideoResult] {
        YouTubeSearchParser.videos(in: json)
    }
}

actor StreamResolver {
    static let shared = StreamResolver()
    private var streams: [String: (URL, Date)] = [:]
    func url(for track: Track, documents: URL, refresh: Bool = false) async throws -> URL {
        if let file = track.localFile {
            let root = documents.standardizedFileURL
            let url = root.appendingPathComponent(file).standardizedFileURL
            guard url.path.hasPrefix(root.path + "/"), FileManager.default.fileExists(atPath: url.path) else {
                throw OdifyError.message("원본 파일을 찾을 수 없습니다. 파일을 다시 가져와 주세요.")
            }
            return url
        }
        if let file = track.driveFileID {
            guard DriveClient.validID(file), track.driveAccountHash != nil, (track.fileSize ?? 0) > 0 else {
                throw OdifyError.message("Drive에서 곡을 다시 선택해 주세요.")
            }
            var components = URLComponents(); components.scheme = "odify-drive"; components.host = "media"
            components.path = "/" + file + "." + (track.fileExtension ?? "mp3")
            guard let url = components.url else { throw OdifyError.message("Drive 음원 주소를 만들지 못했습니다.") }
            return url
        }
        let id: String
        if let videoID = track.videoID { id = videoID } else { id = try await YouTubeCatalog.shared.match(track).id }
        if !refresh, let (url, expiry) = streams[id], expiry.timeIntervalSinceNow > 60 { return url }
        try Task.checkCancellation()
        // Local extraction only: never send proxy requests or cookies to a third-party fallback server.
        let result: URL
        do { result = try await YouTubeAudio.url(videoID: id) }
        catch is CancellationError { throw CancellationError() }
        catch YouTubeAudioFailure.restricted(let reason) { throw YouTubeAudioFailure.restricted(reason) }
        catch let error as URLError {
            try Task.checkCancellation()
            if error.code == .cancelled { throw CancellationError() }
            throw OdifyError.message("YouTube 오디오 주소 연결 실패 (\(error.code.rawValue)). " + (error.code == .timedOut ? "요청 시간이 초과되었습니다. 다시 시도해 주세요." : error.localizedDescription))
        }
        catch YouTubeAudioFailure.temporarilyUnavailable { throw YouTubeAudioFailure.temporarilyUnavailable }
        catch {
            try Task.checkCancellation()
            let all = try await YouTube(videoID: id, methods: [.local]).streams
            guard let stream = all.filterAudioOnly().filter({ $0.fileExtension == .m4a && $0.isNativelyPlayable }).highestAudioBitrateStream() else {
                throw OdifyError.message("이 영상의 오디오 스트림을 연결하지 못했습니다. 다른 영상을 선택해 주세요.")
            }
            result = stream.url
        }
        try Task.checkCancellation()
        guard result.scheme == "https", result.host?.hasSuffix(".googlevideo.com") == true,
              result.user == nil, result.password == nil else { throw OdifyError.message("안전한 음원 주소를 확인하지 못했습니다.") }
        if streams.count > 40 { streams.removeAll() }
        streams[id] = (result, YouTubeAudio.validUntil(result))
        return result
    }
}
