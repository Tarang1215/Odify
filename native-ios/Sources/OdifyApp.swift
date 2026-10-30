import SwiftUI
import UniformTypeIdentifiers
import GoogleSignIn

final class OdifyOrientationDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        if ProcessInfo.processInfo.arguments.contains("--odify-landscape") { return .landscape }
        return UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    }
}

@main struct OdifyApp: App {
    @UIApplicationDelegateAdaptor(OdifyOrientationDelegate.self) private var appDelegate
    @StateObject private var store = LibraryStore(preview: ProcessInfo.processInfo.arguments.contains("--odify-preview"))
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).environmentObject(store.engine)
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    if GIDSignIn.sharedInstance.handle(url) { return }
                    if url.isFileURL { Task { await store.importFiles([url], source: .local) } }
                }
                .task { await DriveAccount.shared.restore() }
        }
    }
}

enum MainPage: String, CaseIterable, Identifiable {
    case home = "Home", explore = "Explore", search = "Search", library = "Library"
    var id: String { rawValue }
    var symbol: String { switch self { case .home: return "house"; case .explore: return "safari"; case .search: return "magnifyingglass"; case .library: return "square.stack" } }
}

struct RootView: View {
    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var engine: PlaybackEngine
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var page: MainPage = .home
    @State private var fullPlayer = ProcessInfo.processInfo.arguments.contains("--odify-player")
    @State private var importer = false
    @State private var importSource: MusicSource = .local
    @State private var showAbout = false
    @State private var showDrive = false

    var body: some View {
        GeometryReader { geometry in
            let sidebar = geometry.size.width >= 760
            let inspector = geometry.size.width >= 1100
            HStack(spacing: 0) {
                if sidebar { navigationSidebar.frame(width: geometry.size.width >= 1100 ? 202 : 184) }
                NavigationStack {
                    Group {
                        switch page {
                        case .home: HomeView()
                        case .explore: ExploreView()
                        case .search: SearchView()
                        case .library: LibraryView(importAction: importMusic)
                        }
                    }
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Menu {
                                Button("파일 가져오기", systemImage: "folder") { importMusic(.local) }
                                Button("Google Drive 연결·탐색", systemImage: "externaldrive") { showDrive = true }
                                Button("파일 앱의 Drive에서 가져오기", systemImage: "folder") { importMusic(.drive) }
                                Button("앱 정보", systemImage: "info.circle") { showAbout = true }
                            } label: { Image(systemName: "plus").frame(width: 28, height: 28) }
                            .accessibilityLabel("음악 가져오기 및 앱 정보")
                        }
                    }
                    .navigationDestination(for: MusicPlaylist.self) { PlaylistView(playlistID: $0.id) }
                    .navigationDestination(for: Track.self) { ArtistView(artist: $0) }
                }
                .id(page)
                .transition(.opacity)
                .tint(.white)
                if inspector {
                    NowPlayingPane(openFullPlayer: { fullPlayer = true })
                        .frame(width: min(342, geometry.size.width * 0.28))
                        .padding(.trailing, 16).padding(.top, 14)
                }
            }
            .background(MusicBackdrop(tint: Color(uiColor: engine.tint)))
            .safeAreaInset(edge: .bottom, spacing: 10) {
                VStack(spacing: 10) {
                    if let error = engine.error {
                        HStack(spacing: 12) {
                            Image(systemName: "exclamationmark.circle")
                            Text(error).font(.system(size: 12)).lineLimit(3).frame(maxWidth: .infinity, alignment: .leading)
                            if let url = engine.restrictedYouTubeURL {
                                Link("YouTube에서 열기", destination: url).font(.system(size: 12, weight: .semibold))
                            } else {
                                Button("다시 시도") { engine.clearError(); engine.resume() }.font(.system(size: 12, weight: .semibold))
                            }
                            Button { engine.clearError() } label: { Image(systemName: "xmark").frame(width: 30, height: 30) }.accessibilityLabel("안내 닫기")
                        }.padding(14).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18)).padding(.horizontal, 20)
                    }
                    if engine.current != nil { MiniPlayer(wide: sidebar, open: { fullPlayer = true }).padding(.horizontal, sidebar ? 20 : 12) }
                    if !sidebar { compactTabs.padding(.horizontal, 32) }
                }.padding(.bottom, 8)
            }
            .animation(reduceMotion ? nil : .smooth(duration: 0.3), value: sidebar)
            .animation(reduceMotion ? nil : .smooth(duration: 0.22), value: page)
            .overlay {
                if fullPlayer {
                    FullPlayerView(close: { fullPlayer = false })
                        .environmentObject(store).environmentObject(engine)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .zIndex(30)
                }
            }
            .animation(reduceMotion ? nil : .spring(response: 0.48, dampingFraction: 0.88), value: fullPlayer)
            .sheet(isPresented: $showAbout) { AboutView() }
            .sheet(isPresented: $showDrive) { DriveBrowser() }
            .fileImporter(isPresented: $importer, allowedContentTypes: [.audio, .mpeg4Movie], allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls): Task { await store.importFiles(urls, source: importSource) }
                case .failure(let error): store.notice = error.localizedDescription
                }
            }
            .overlay(alignment: .top) {
                if store.importing { Label("음악을 가져오는 중…", systemImage: "arrow.down.circle").padding().glassEffect().padding(.top, 10) }
            }
            .alert("Odify", isPresented: Binding(get: { store.notice != nil }, set: { if !$0 { store.notice = nil } })) {
                Button("확인") { store.notice = nil }
            } message: { Text(store.notice ?? "") }
            .task {
                if ProcessInfo.processInfo.arguments.contains("--odify-landscape") {
                    try? await Task.sleep(for: .milliseconds(200))
                    if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
                        scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeLeft))
                    }
                }
                await store.loadHome()
            }
        }
        .background(alignment: .topLeading) {
            if engine.usingYouTubeWebPlayer, let renderer = engine.youtubeWebHost {
                YouTubeWebSurface(player: renderer).frame(width: 240, height: 240)
                    .allowsHitTesting(false).accessibilityHidden(true)
            }
        }
    }
    private func importMusic(_ source: MusicSource) { importSource = source; importer = true }
    private var navigationSidebar: some View {
        VStack(alignment: .leading, spacing: 28) {
            HStack(spacing: 10) { Image(systemName: "waveform").foregroundStyle(.mint); Text("odify").font(.system(size: 29, weight: .bold, design: .rounded)) }
                .padding(.horizontal, 18).padding(.top, 28)
            VStack(spacing: 8) {
                ForEach(MainPage.allCases) { item in
                    Button { page = item } label: {
                        Label(item.rawValue, systemImage: item.symbol + (page == item && item != .search ? ".fill" : ""))
                            .font(.system(size: 15, weight: .semibold)).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, 13)
                            .background { if page == item { RoundedRectangle(cornerRadius: 16).fill(.white.opacity(0.09)) } }
                    }.buttonStyle(.plain).foregroundStyle(page == item ? .white : .white.opacity(0.55))
                }
            }.padding(.horizontal, 12)
            VStack(alignment: .leading, spacing: 16) {
                Text("YOUR SPACE").font(.system(size: 10, weight: .bold)).tracking(2).foregroundStyle(.secondary)
                Label("좋아요 \(store.liked.count)", systemImage: "heart")
                Label("플레이리스트 \(store.playlists.count)", systemImage: "music.note.list")
                Label("가져온 음악 \(store.tracks.filter { $0.localFile != nil }.count)", systemImage: "folder")
            }.font(.system(size: 12)).foregroundStyle(.secondary).padding(.horizontal, 26)
            Spacer()
            Button { showAbout = true } label: { Label("Made for your music", systemImage: "sparkles").font(.system(size: 11)) }
                .buttonStyle(.plain).foregroundStyle(.secondary).padding(22)
        }
        .frame(maxHeight: .infinity)
        .background(.white.opacity(0.025))
    }
    private var compactTabs: some View {
        HStack(spacing: 4) {
            ForEach(MainPage.allCases) { item in
                Button { page = item } label: {
                    VStack(spacing: 4) { Image(systemName: item.symbol).font(.system(size: 18)); Text(item.rawValue).font(.system(size: 9, weight: .medium)) }
                        .foregroundStyle(page == item ? .white : .secondary).frame(maxWidth: .infinity).padding(.vertical, 10)
                        .background { if page == item { Capsule().fill(.white.opacity(0.10)) } }
                }.buttonStyle(.plain).accessibilityLabel(item.rawValue).accessibilityAddTraits(page == item ? .isSelected : [])
            }
        }.padding(5).glassEffect(.regular, in: .capsule)
    }
}

struct AboutView: View {
    var body: some View {
        NavigationStack {
            List {
                Section("Odify · Native iOS") {
                    Text("iPad와 iPhone을 위한 SwiftUI 화면 · iOS 26 이상")
                    LabeledContent("버전", value: "\(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—") (\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "—"))")
                }
                Section("재생") {
                    Text("앱 내부 AVQueuePlayer가 Local·Drive 가져온 파일과 연결 가능한 YouTube 오디오를 재생합니다. YouTube 연결은 서비스 변경이나 네트워크 환경에 따라 실패할 수 있습니다.")
                    Text("Google 계정에 연결하면 Drive 폴더를 탐색하고 음원을 바로 스트리밍할 수 있습니다. 선택한 음원 구간만 읽으며 파일을 수정하지 않습니다. Android의 기존 보관함·플레이리스트 자동 이전은 아직 포함되지 않았습니다.")
                }
                Section("문제 확인") {
                    Text("재생 단계와 오류 코드만 기기 안에 최대 80개 기록합니다. 곡 제목·사진·계정·음원 주소·인증 토큰은 기록하지 않습니다.")
                    if let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
                        let file = root.appendingPathComponent("odify-playback-diagnostics.json")
                        if FileManager.default.fileExists(atPath: file.path) {
                            ShareLink("재생 오류 기록 내보내기", item: file)
                        }
                    }
                }
                Section("LiveContainer") {
                    Text("홈 화면 WidgetKit 위젯은 LiveContainer 게스트 앱에서 등록할 수 없습니다. 앱 안의 미니플레이어와 시스템 재생 정보는 별개이며, 실제 기기의 LiveContainer 설정에 따라 확인이 필요합니다.")
                    Text("YTKACE는 별도 앱입니다. Odify는 YTKACE의 대기열이나 재생 상태를 제어하지 않습니다.")
                }
                Section("오픈소스") {
                    Text("YouTubeKit · MIT · Alexander Eichhorn 외 기여자\n고정 소스: 89854c93207ad0d2b3d78d6f1aea24595ca66552")
                    if let url = Bundle.main.url(forResource: "YouTubeKit-LICENSE", withExtension: "txt"), let text = try? String(contentsOf: url, encoding: .utf8) { Text(text).font(.caption) }
                    if let url = Bundle.main.url(forResource: "ThirdPartyNotices", withExtension: "txt"), let text = try? String(contentsOf: url, encoding: .utf8) { Text(text).font(.caption) }
                }
            }.navigationTitle("Odify 정보")
        }
    }
}
