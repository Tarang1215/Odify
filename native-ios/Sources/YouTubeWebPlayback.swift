import SwiftUI
import WebKit

enum YouTubeWebEvent {
    case state(Int, Double, Double, Bool)
    case selected(String)
    case failed(Int)
    case needsTap
}

@MainActor protocol YouTubeWebPlaying: AnyObject {
    var onEvent: ((UUID, YouTubeWebEvent) -> Void)? { get set }
    var attached: Bool { get }
    func load(videoID: String, request: UUID, position: Double, autoplay: Bool)
    func search(track: Track, request: UUID)
    func play()
    func pause()
    func seek(_ seconds: Double)
    func setActive(_ active: Bool)
    func stop()
    func setVolume(_ volume: Float)
}

extension YouTubeWebPlaying { func setVolume(_ volume: Float) {} }

struct YouTubeWebFailure: LocalizedError, CustomNSError {
    var code: Int
    static var errorDomain: String { "com.odify.youtube.web" }
    var errorCode: Int { code }
    var errorDescription: String? {
        switch code {
        case -2: return "가수와 곡명이 일치하는 YouTube 영상을 찾지 못했습니다. 검색의 YouTube 결과에서 직접 선택해 주세요 (-2)."
        case -3: return "YouTube 검색 결과를 읽지 못했습니다. 잠시 후 다시 시도해 주세요 (-3)."
        case -4: return "YouTube 검색에 사용자 확인이 필요합니다. YouTube에서 연결 상태를 확인해 주세요 (-4)."
        case 100: return "YouTube에서 이 영상을 찾을 수 없습니다 (100)."
        case 101, 150: return "이 영상은 앱 안 재생이 허용되지 않습니다. YouTube에서 확인해 주세요 (\(code))."
        case 153: return "YouTube가 앱의 플레이어 식별 정보를 확인하지 못했습니다 (153)."
        default: return "YouTube 플레이어에 연결하지 못했습니다 (\(code)). 다시 열어 확인해 주세요."
        }
    }
    var errorUserInfo: [String: Any] { [NSLocalizedDescriptionKey: errorDescription ?? "YouTube 오류"] }
}

/// OS-provided media renderer underneath Odify's music UI, as on Android.
/// No media URL extraction, page-visibility overrides, ad changes or imported
/// credentials. Actual screen-off survival is determined by WebKit/the service.
@MainActor final class YouTubeWebPlayback: NSObject, YouTubeWebPlaying, WKNavigationDelegate {
    var onEvent: ((UUID, YouTubeWebEvent) -> Void)?
    private(set) lazy var view = makeView()
    private var hasLoadedPage = false
    private(set) var attached = false
    private var mounted = false
    private var active = true
    private var request = UUID()
    private var choosing = false
    private var searchTrack: Track?
    private var searchIndex = 0
    private var searchRead: Task<Void, Never>?
    private var searchNavigation: WKNavigation?
    private var wantsPlayback = false
    private var loaded = false
    private var volume: Float = 1
    private var timeout: Task<Void, Never>?

    override init() {
        super.init()
    }
    private func makeView() -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.allowsPictureInPictureMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.websiteDataStore = .default()
        let view = WKWebView(frame: .zero, configuration: config)
        view.isOpaque = false; view.backgroundColor = .black
        view.scrollView.isScrollEnabled = false
        view.navigationDelegate = self
        config.userContentController.add(WeakYouTubeMessages(self), name: "odify")
        return view
    }
    nonisolated static func validID(_ id: String) -> Bool { id.range(of: #"^[A-Za-z0-9_-]{11}$"#, options: .regularExpression) != nil }
    private var origin: URL {
        let value = Bundle.main.bundleIdentifier?.lowercased() ?? "com.odify.music.ios"
        return URL(string: "https://" + value) ?? URL(string: "https://com.odify.music.ios")!
    }
    func load(videoID: String, request: UUID, position: Double, autoplay: Bool = true) {
        guard Self.validID(videoID) else { onEvent?(request, .failed(2)); return }
        searchRead?.cancel(); searchRead = nil
        let reuse = loaded && !choosing
        self.request = request; choosing = false; wantsPlayback = autoplay; loaded = false
        hasLoadedPage = true
        view.scrollView.isScrollEnabled = false
        view.configuration.userContentController.removeAllUserScripts()
        let html = Self.html(videoID: videoID, origin: origin.absoluteString, request: request, position: position, visible: attached, autoplay: autoplay)
        if reuse {
            let start = position.isFinite ? max(0, position) : 0
            view.evaluateJavaScript("Boolean(window.odifyLoad && odifyLoad('\(videoID)','\(request.uuidString)',\(start),\(autoplay ? "true" : "false")))") { [weak self] result, _ in
                Task { @MainActor in
                    guard let self, self.request == request else { return }
                    if (result as? Bool) != true { self.view.loadHTMLString(html, baseURL: self.origin) }
                }
            }
        } else { view.loadHTMLString(html, baseURL: origin) }
        timeout?.cancel()
        timeout = nil; scheduleTimeout(request)
    }
    func search(track: Track, request: UUID) {
        timeout?.cancel(); searchRead?.cancel(); self.request = request; choosing = true; loaded = false
        searchTrack = track; wantsPlayback = false
        hasLoadedPage = true
        let scripts = view.configuration.userContentController
        scripts.removeAllUserScripts()
        searchIndex = 0; loadSearchPage()
        timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(20)) } catch { return }
            guard let self, self.request == request, self.choosing else { return }
            self.finishSearch(.failed(-1001))
        }
    }
    func play() { wantsPlayback = true; command("odifyPlay()") }
    func pause() {
        wantsPlayback = false
        // A paused lookup still needs a bounded deadline. Playback intent is
        // owned by PlaybackEngine and is checked when the selected ID arrives.
        if !choosing { timeout?.cancel(); timeout = nil }
        command("odifyPause()")
    }
    func seek(_ seconds: Double) { if seconds.isFinite { command("odifySeek(\(max(0, seconds)))") } }
    func setVolume(_ volume: Float) { self.volume = max(0, min(1, volume)); command("odifyVolume(\(self.volume))") }
    func mount(_ mounted: Bool) {
        self.mounted = mounted; attached = mounted
        if mounted { command("odifyMounted(true)") }
    }
    // Do not synthesize visibility or a playback gesture on screen lock.
    // The ordinary WebKit/media lifecycle remains in charge.
    func setActive(_ active: Bool) {
        self.active = active
        if !active, !choosing { timeout?.cancel(); timeout = nil }
        // Reconcile a system pause only after returning to the foreground.
        // Never invent page visibility or loop play commands on screen lock.
        if active, wantsPlayback, loaded, !choosing {
            command("odifyPlay()"); scheduleTimeout(request)
        }
    }
    func stop() {
        guard hasLoadedPage else { return }; hasLoadedPage = false
        timeout?.cancel(); timeout = nil; searchRead?.cancel(); searchRead = nil
        wantsPlayback = false; loaded = false
        choosing = false; searchTrack = nil
        request = UUID(); view.stopLoading(); view.loadHTMLString("", baseURL: nil)
    }
    private func command(_ script: String) {
        guard hasLoadedPage, !choosing else { return }
        view.evaluateJavaScript("if(window.odifyPlay){\(script)}", completionHandler: nil)
    }
    func receive(_ message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame, let data = message.body as? [String: Any],
              data["request"] as? String == request.uuidString, let kind = data["kind"] as? String else { return }
        if kind == "ready", !choosing {
            loaded = true
            command("odifyVolume(\(volume))")
            command(wantsPlayback ? "odifyPlay()" : "odifyPause()")
        } else if kind == "state", !choosing {
            guard let state = data["state"] as? Int, [-1, 0, 1, 2, 3, 5].contains(state),
                  let position = data["position"] as? Double, position.isFinite, position >= 0,
                  let duration = data["duration"] as? Double, duration.isFinite, duration >= 0,
                  (data["intent"] as? Bool) != nil else { return }
            loaded = true
            if state == 2, active, wantsPlayback { command("odifyRecoverStart()") }
            if state == 1 || !wantsPlayback { timeout?.cancel(); timeout = nil }
            else if [-1, 2, 3].contains(state) { scheduleTimeout(request) }
            onEvent?(request, .state(state, position, duration, wantsPlayback))
        } else if kind == "error", let code = data["code"] as? Int {
            wantsPlayback = false; timeout?.cancel(); onEvent?(request, .failed(code))
        } else if kind == "blocked" { wantsPlayback = false; timeout?.cancel(); onEvent?(request, .needsTap) }
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code != URLError.cancelled.rawValue { navigationFailed(error) }
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { navigationFailed(error) }
    private func navigationFailed(_ error: Error) {
        guard (error as NSError).code != URLError.cancelled.rawValue else { return }
        if choosing { finishSearch(.failed((error as NSError).code)) }
        else { timeout?.cancel(); onEvent?(request, .failed((error as NSError).code)) }
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        if choosing { finishSearch(.failed(-1)) }
        else { timeout?.cancel(); onEvent?(request, .failed(-1)) }
    }
    private func scheduleTimeout(_ request: UUID) {
        guard active, timeout == nil else { return }
        timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(20)) } catch { return }
            guard let self, self.request == request, self.wantsPlayback else { return }
            self.timeout = nil; self.onEvent?(request, .failed(YouTubeWebFailureTimeout))
        }
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard choosing, navigation === searchNavigation, let track = searchTrack else { return }
        guard ["www.youtube.com", "m.youtube.com"].contains(webView.url?.host ?? ""), webView.url?.path == "/results" else {
            finishSearch(.failed(-4)); return
        }
        let token = request, index = searchIndex
        searchRead?.cancel()
        searchRead = Task { [weak self] in
            guard let self else { return }
            // didFinish can precede YouTube's result hydration. Re-read only
            // while the same query and queue occurrence are still selected.
            for _ in 0..<12 {
                guard !Task.isCancelled, self.request == token, self.choosing, self.searchIndex == index else { return }
                let value = try? await webView.evaluateJavaScript(Self.searchSnapshotScript)
                guard !Task.isCancelled, self.request == token, self.choosing, self.searchIndex == index else { return }
                if let text = value as? String, let data = text.data(using: .utf8), data.count < 8_000_000,
                   let json = try? JSONSerialization.jsonObject(with: data) {
                    let videos = YouTubeCatalog.videoResults(json)
                    if let video = YouTubeCatalog.matchingVideo(track, in: videos) {
                        self.finishSearch(.selected(video.id)); return
                    }
                    if !videos.isEmpty {
                        if index + 1 < YouTubeSearchParser.queries(for: track).count {
                            self.searchIndex += 1; self.loadSearchPage()
                        } else { self.finishSearch(.failed(-2)) }
                        return
                    }
                }
                do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
            }
            self.finishSearch(.failed(-3))
        }
    }
    private func loadSearchPage() {
        guard let track = searchTrack else { return }
        let query = YouTubeSearchParser.queries(for: track)[searchIndex]
        searchNavigation = view.load(URLRequest(url: MusicHTTP.url("https://www.youtube.com/results", ["search_query": query, "hl": "en"])))
    }
    private func finishSearch(_ event: YouTubeWebEvent) {
        guard choosing else { return }
        choosing = false; searchNavigation = nil; timeout?.cancel(); timeout = nil; searchRead?.cancel(); searchRead = nil
        onEvent?(request, event)
    }
    // YouTube mobile sometimes assigns JSON as a JavaScript string, whereas
    // desktop assigns an object. Never execute page text to decode either.
    static let searchSnapshotScript = """
    (function(){var d=window.ytInitialData;if(typeof d==='string'){try{d=JSON.parse(d)}catch(e){return null}}return d?JSON.stringify(d):null})()
    """
    static func html(videoID: String, origin: String, request: UUID, position: Double, visible: Bool, autoplay: Bool = true) -> String {
        precondition(validID(videoID))
        let encodedOrigin = String(data: try! JSONSerialization.data(withJSONObject: origin, options: .fragmentsAllowed), encoding: .utf8)!
        let start = position.isFinite ? max(0, position) : 0
        return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body{margin:0;width:100%;height:100%;background:#000}#player{width:100%;height:100%;position:absolute;inset:0}</style></head>
        <body><div id="player"></div><script>
        try{if(navigator.audioSession)navigator.audioSession.type='playback'}catch(e){}
        var p,ready=false,mounted=\(visible ? "true" : "false"),intent=\(autoplay ? "true" : "false"),pending=\(autoplay ? "1" : "2"),started=false,recovered=false,activeID='\(videoID)',commanded=Date.now(),tag='\(request.uuidString)';
        function send(kind,extra){window.webkit.messageHandlers.odify.postMessage(Object.assign({request:tag,kind:kind},extra||{}))}
        function currentVideo(){return !p.getVideoData||(p.getVideoData()||{}).video_id===activeID}
        function snapshot(){if(!ready||!currentVideo())return;var s=p.getPlayerState();if(s===0&&!started)return;send('state',{state:s,position:p.getCurrentTime()||0,duration:p.getDuration()||0,intent:intent})}
        function onYouTubeIframeAPIReady(){p=new YT.Player('player',{videoId:'\(videoID)',width:'100%',height:'100%',
        playerVars:{playsinline:1,autoplay:0,controls:1,origin:\(encodedOrigin)},events:{onReady:function(){ready=true;
        if(\(start)>0)p.seekTo(\(start),true);if(intent&&mounted)p.playVideo();send('ready')},
        onStateChange:function(e){if(!currentVideo())return;if(e.data===1){if(pending===2){p.pauseVideo();return}started=true;intent=true;pending=0}
        if(e.data===2&&pending===1&&!started)return;snapshot()},
        onError:function(e){intent=false;pending=0;send('error',{code:e.data})},onAutoplayBlocked:function(){intent=false;pending=0;send('blocked')}}})}
        function odifyPlay(){intent=true;pending=1;recovered=false;commanded=Date.now();if(ready&&mounted)p.playVideo()}
        function odifyRecoverStart(){if(ready&&mounted&&intent&&!recovered){recovered=true;p.playVideo()}}
        function odifyPause(){intent=false;pending=2;commanded=Date.now();if(ready)p.pauseVideo()}
        function odifyLoad(id,request,t,play){if(!ready)return false;tag=request;activeID=id;started=false;recovered=false;intent=play!==false;pending=intent?1:2;commanded=Date.now();
        if(intent)p.loadVideoById({videoId:id,startSeconds:t});else p.cueVideoById({videoId:id,startSeconds:t});return true}
        function odifySeek(t){if(ready)p.seekTo(t,true)}
        function odifyVolume(v){if(ready&&p.setVolume)p.setVolume(v*100)}
        function odifyMounted(v){mounted=v;if(ready&&mounted&&intent)p.playVideo()}
        setInterval(snapshot,500);
        </script><script src="https://www.youtube.com/iframe_api"></script></body></html>
        """
    }
}

private let YouTubeWebFailureTimeout = -1001

@MainActor private final class WeakYouTubeMessages: NSObject, WKScriptMessageHandler {
    weak var owner: YouTubeWebPlayback?
    init(_ owner: YouTubeWebPlayback) { self.owner = owner }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) { owner?.receive(message) }
}

/// Attach the renderer beneath the scene's opaque native music UI. Keeping it
/// at the scene window avoids re-creating it when the full music player opens.
struct YouTubeWebSurface: UIViewRepresentable {
    let player: YouTubeWebPlayback
    final class Anchor: UIView {
        weak var player: YouTubeWebPlayback?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let window, let player else { return }
            if player.view.superview !== window {
                player.view.removeFromSuperview()
                player.view.frame = CGRect(x: 0, y: 0, width: 240, height: 240)
                player.view.isUserInteractionEnabled = false
                player.view.accessibilityElementsHidden = true
                window.insertSubview(player.view, at: 0)
            }
            player.mount(true)
        }
    }
    func makeUIView(context: Context) -> Anchor {
        let view = Anchor(); view.player = player; view.isUserInteractionEnabled = false; return view
    }
    func updateUIView(_ uiView: Anchor, context: Context) { }
    static func dismantleUIView(_ uiView: Anchor, coordinator: ()) {
        uiView.player?.mount(false); uiView.player?.view.removeFromSuperview()
    }
}
