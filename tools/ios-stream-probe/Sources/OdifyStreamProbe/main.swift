import Foundation
import YouTubeKit

@main struct Probe {
    static func main() async {
        let id = "9bZkp7q19f0"
        do {
            let streams = try await YouTube(videoID: id, methods: [.local]).streams
            guard let stream = streams.filterAudioOnly().filter({ $0.fileExtension == .m4a && $0.isNativelyPlayable }).highestAudioBitrateStream() else {
                throw NSError(domain: "Odify", code: 1, userInfo: [NSLocalizedDescriptionKey: "No native m4a audio stream"])
            }
            var request = URLRequest(url: stream.url, timeoutInterval: 20)
            request.setValue("bytes=0-1023", forHTTPHeaderField: "Range")
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let pass = (200..<300).contains(status) && !data.isEmpty
            write(["success": pass, "videoID": id, "host": stream.url.host ?? "", "httpStatus": status, "bytes": data.count, "audioOnly": true, "devicePlaybackTested": false])
            if !pass { exit(1) }
        } catch {
            write(["success": false, "videoID": id, "error": error.localizedDescription, "devicePlaybackTested": false]); exit(1)
        }
    }
    static func write(_ object: [String: Any]) {
        let data = try! JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys])
        print(String(decoding: data, as: UTF8.self))
    }
}
