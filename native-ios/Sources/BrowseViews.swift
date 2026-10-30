import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var store: LibraryStore
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 34) {
                VStack(alignment: .leading, spacing: 14) {
                    Text("YOUR DAILY SOUNDTRACK").font(.system(size: 10, weight: .bold)).tracking(2.6).foregroundStyle(.mint.opacity(0.8))
                    Text("Keep your\nown rhythm.").font(.system(size: 42, weight: .semibold, design: .rounded)).tracking(-1.8)
                    Text("당신의 음악, 새로운 발견.").font(.system(size: 13)).foregroundStyle(.white.opacity(0.6))
                }.frame(maxWidth: .infinity, alignment: .leading).padding(28)
                    .background(alignment: .trailing) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 26).fill(LinearGradient(colors: [.mint.opacity(0.15), .white.opacity(0.025)], startPoint: .topLeading, endPoint: .bottomTrailing))
                            Image(systemName: "waveform").font(.system(size: 100, weight: .ultraLight)).foregroundStyle(.mint.opacity(0.09)).offset(x: 90)
                        }.clipped()
                    }
                VStack(alignment: .leading, spacing: 20) {
                    sectionTitle("More from your artists", subtitle: "저장소 밖의 음악까지 함께 만나보세요")
                    ScrollView(.horizontal) {
                        LazyHStack(alignment: .top, spacing: 22) {
                            ForEach(Array(store.artists.prefix(14))) { artist in
                                NavigationLink(value: artist) {
                                    VStack(spacing: 10) {
                                        ArtistPortrait(track: artist)
                                        Text(artist.artist).font(.system(size: 12, weight: .medium)).lineLimit(1).frame(width: 100)
                                    }
                                }.buttonStyle(.plain)
                            }
                        }.padding(.bottom, 6)
                    }.scrollIndicators(.hidden)
                    if store.homeLoading { ProgressView().frame(maxWidth: .infinity) }
                    if let error = store.homeError {
                        HStack(alignment: .center, spacing: 12) {
                            Text(error).font(.system(size: 12)).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
                            Button("다시 불러오기") { Task { await store.loadHome(refresh: true) } }
                                .font(.system(size: 12, weight: .medium)).buttonStyle(.glass)
                        }.padding(14).background(.white.opacity(0.04), in: RoundedRectangle(cornerRadius: 16))
                    }
                }
                VStack(alignment: .leading, spacing: 18) {
                    sectionTitle("Made for you", subtitle: "모아둔 음악과 새로 만나는 음악")
                    ScrollView(.horizontal) {
                        HStack(spacing: 16) {
                            MixCard(title: "Your collection", caption: "최근 추가한 음악", tracks: Array(store.tracks.reversed()), color: .teal)
                            MixCard(title: "Favorites", caption: "마음에 담아둔 곡", tracks: store.liked, color: .pink)
                            MixCard(title: "Fresh finds", caption: "오늘의 인기곡에서 발견", tracks: store.chart, color: .indigo)
                        }
                    }.scrollIndicators(.hidden)
                }
                VStack(alignment: .leading, spacing: 18) {
                    sectionTitle("Find your mood", subtitle: "지금의 기분에 어울리는 음악을 찾아보세요")
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), spacing: 12)], spacing: 12) {
                        MoodCard(title: "Focus", subtitle: "조용히 집중할 때", query: "instrumental piano", color: .indigo, symbol: "moon.stars")
                        MoodCard(title: "Unwind", subtitle: "긴 하루의 끝", query: "acoustic chill", color: .teal, symbol: "leaf")
                        MoodCard(title: "Energy", subtitle: "기분을 끌어올려요", query: "dance pop", color: .orange, symbol: "bolt")
                        MoodCard(title: "Night drive", subtitle: "밤의 리듬", query: "rnb night", color: .purple, symbol: "sparkles")
                    }
                }
                if !store.chart.isEmpty {
                    VStack(alignment: .leading, spacing: 16) {
                        sectionTitle("A little discovery", subtitle: "Apple Music 한국 인기곡 · YouTube 오디오 연결")
                        ForEach(Array(store.chart.prefix(6).enumerated()), id: \.element.id) { index, track in
                            TrackRow(track: track) { store.play(store.chart, at: index) }
                        }
                    }
                }
            }.padding(24).padding(.bottom, 20)
        }.navigationTitle("Home").toolbarTitleDisplayMode(.inline)
            .refreshable { await store.loadHome(refresh: true) }
    }
}

@ViewBuilder func sectionTitle(_ title: String, subtitle: String? = nil) -> some View {
    VStack(alignment: .leading, spacing: 6) {
        Text(title).font(.system(size: 21, weight: .semibold, design: .rounded)).tracking(-0.5)
        if let subtitle { Text(subtitle).font(.system(size: 11)).foregroundStyle(.secondary) }
    }
}

struct MixCard: View {
    let title: String
    let caption: String
    let tracks: [Track]
    let color: Color
    var body: some View {
        NavigationLink {
            TrackCollectionView(title: title, subtitle: caption, tracks: tracks)
        } label: {
            VStack(alignment: .leading, spacing: 10) {
                ZStack(alignment: .bottomLeading) {
                    RoundedRectangle(cornerRadius: 20).fill(LinearGradient(colors: [color.opacity(0.7), color.opacity(0.12)], startPoint: .topLeading, endPoint: .bottomTrailing))
                    Image(systemName: title == "Favorites" ? "heart.fill" : "waveform").font(.system(size: 52, weight: .thin)).foregroundStyle(.white.opacity(0.2)).frame(maxWidth: .infinity, maxHeight: .infinity)
                    Text(title).font(.system(size: 23, weight: .bold, design: .rounded)).padding(16)
                }.frame(width: 166, height: 164)
                Text(caption).font(.system(size: 11)).foregroundStyle(.secondary)
            }.frame(width: 166, alignment: .leading)
        }.buttonStyle(.plain)
    }
}

struct MoodCard: View {
    let title: String
    let subtitle: String
    let query: String
    let color: Color
    let symbol: String
    var body: some View {
        NavigationLink { DiscoveryView(title: title, query: query) } label: {
            VStack(alignment: .leading, spacing: 12) {
                Image(systemName: symbol).font(.system(size: 20, weight: .light)).foregroundStyle(color.opacity(0.85))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.system(size: 16, weight: .semibold))
                    Text(subtitle).font(.system(size: 10)).foregroundStyle(.secondary)
                }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(18)
                .background(color.opacity(0.1), in: RoundedRectangle(cornerRadius: 20))
        }.buttonStyle(.plain)
    }
}

struct TrackRow: View {
    var track: Track
    var number: Int? = nil
    var action: () -> Void
    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var engine: PlaybackEngine
    var body: some View {
        HStack(spacing: 12) {
            Button(action: action) {
                HStack(spacing: 12) {
                    if let number { Text(String(number)).font(.system(size: 12, design: .monospaced)).foregroundStyle(.secondary).frame(width: 20) }
                    Artwork(address: track.artwork, title: track.album.isEmpty ? track.title : track.album, size: 46, radius: 10)
                    VStack(alignment: .leading, spacing: 5) {
                        Text(track.title).font(.system(size: 14, weight: .medium)).foregroundStyle(engine.current?.recordingKey == track.recordingKey ? .mint : .white).lineLimit(1)
                        Text(track.artist).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            Menu {
                Button(store.snapshot.likedIDs.contains(track.id) ? "좋아요 취소" : "좋아요", systemImage: "heart") { store.toggleLike(track) }
                Button("다음 재생에 추가", systemImage: "text.line.last.and.arrowtriangle.forward") { engine.enqueue(track); store.remember(track) }
                Menu("플레이리스트에 추가", systemImage: "text.badge.plus") {
                    ForEach(store.playlists) { playlist in Button(playlist.name) { store.add(track, to: playlist) } }
                    Button("새 플레이리스트") { let playlist = store.createPlaylist("새 플레이리스트"); store.add(track, to: playlist) }
                }
            } label: { Image(systemName: "ellipsis").font(.system(size: 15)).foregroundStyle(.secondary).frame(width: 40, height: 44) }
                .accessibilityLabel("\(track.title) 메뉴")
        }.padding(.vertical, 5)
    }
}

struct TrackCollectionView: View {
    var title: String
    var subtitle: String = ""
    var tracks: [Track]
    @EnvironmentObject private var store: LibraryStore
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                Text(title).font(.system(size: 34, weight: .bold, design: .rounded)).padding(.top, 20)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
                Button("전체 재생", systemImage: "play.fill") { store.play(tracks) }.buttonStyle(.glassProminent).disabled(tracks.isEmpty).padding(.vertical, 16)
                ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in TrackRow(track: track, number: index + 1) { store.play(tracks, at: index) } }
                if tracks.isEmpty { ContentUnavailableView("아직 음악이 없습니다", systemImage: "music.note", description: Text("음악을 가져오거나 검색에서 추가해 주세요.")) }
            }.padding(24)
        }.navigationTitle(title).toolbarTitleDisplayMode(.inline)
    }
}

struct ArtistView: View {
    let artist: Track
    @EnvironmentObject private var store: LibraryStore
    @State private var songs: [Track] = []
    @State private var loading = true
    @State private var error: String?
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 24) {
                    ArtistPortrait(track: artist, size: 118)
                    VStack(alignment: .leading, spacing: 10) {
                        Text("ARTIST").font(.system(size: 10, weight: .bold)).tracking(2).foregroundStyle(.mint)
                        Text(artist.artist).font(.system(size: 30, weight: .semibold, design: .rounded)).lineLimit(2).minimumScaleFactor(0.7)
                        Text("내 음악 + 공식 카탈로그").font(.system(size: 11)).foregroundStyle(.secondary)
                    }
                }.padding(.vertical, 20)
                if loading { ProgressView().frame(maxWidth: .infinity) }
                if let error { RetryMessage(message: error) { Task { await load() } } }
                HStack {
                    Text("Songs").font(.system(size: 21, weight: .semibold))
                    Spacer()
                    Button { store.play(songs) } label: { Image(systemName: "play.fill").padding(12) }.buttonStyle(.glassProminent).disabled(songs.isEmpty)
                }.padding(.top, 10)
                ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in TrackRow(track: song, number: index + 1) { store.play(songs, at: index) } }
            }.padding(24)
        }.navigationTitle(artist.artist).toolbarTitleDisplayMode(.inline).task { await load() }
    }
    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        let local = store.tracks.filter { Track.normalized($0.artist) == Track.normalized(artist.artist) }
        songs = local
        do {
            let catalog = try await MusicCatalog.shared.artist(artist)
            songs = Track.unique(local + catalog)
            if catalog.isEmpty {
                let videos = try await YouTubeCatalog.shared.search(artist.artist + " music")
                var known = Set(songs.map { Track.normalized($0.title) })
                let additional = videos.map { $0.track() }.filter { known.insert(Track.normalized($0.title)).inserted }
                songs += additional
            }
        }
        catch is CancellationError { }
        catch { self.error = error.localizedDescription }
    }
}

struct DiscoveryView: View {
    let title: String
    let query: String
    @State private var tracks: [Track] = []
    @State private var error: String?
    @State private var loading = true
    var body: some View {
        Group {
            if loading { ProgressView("음악을 찾는 중…") }
            else if let error { RetryMessage(message: error) { Task { await load() } } }
            else { TrackCollectionView(title: title, subtitle: "카탈로그에서 새롭게 만나는 음악", tracks: tracks) }
        }.navigationTitle(title).task { await load() }
    }
    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do { tracks = try await MusicCatalog.shared.search(query) }
        catch is CancellationError { }
        catch { self.error = error.localizedDescription }
    }
}

struct ExploreView: View {
    @State private var country = MusicCountry.korea
    var body: some View {
        VStack(spacing: 14) {
            Picker("국가", selection: $country) { ForEach(MusicCountry.allCases) { Text($0.title).tag($0) } }
                .pickerStyle(.segmented).padding(.horizontal, 24).padding(.top, 12)
            TabView(selection: $country) {
                ForEach(MusicCountry.allCases) { item in CountryChart(country: item).tag(item) }
            }.tabViewStyle(.page(indexDisplayMode: .never))
        }.navigationTitle("Explore").toolbarTitleDisplayMode(.inline)
    }
}

struct CountryChart: View {
    let country: MusicCountry
    @EnvironmentObject private var store: LibraryStore
    @State private var songs: [Track] = []
    @State private var error: String?
    @State private var loading = true
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                Text(country.title + " · Top songs").font(.system(size: 28, weight: .bold, design: .rounded)).padding(.top, 16)
                Text("Apple 국가별 인기곡 · 최대 100곡").font(.system(size: 12)).foregroundStyle(.secondary).padding(.bottom, 12)
                if loading { ProgressView().frame(maxWidth: .infinity) }
                if let error { RetryMessage(message: error) { Task { await load() } } }
                ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in TrackRow(track: song, number: index + 1) { store.play(songs, at: index) } }
            }.padding(.horizontal, 24).padding(.bottom, 24)
        }.task { await load() }
    }
    private func load() async {
        guard songs.isEmpty else { return }
        loading = true; error = nil
        defer { loading = false }
        do { songs = try await MusicCatalog.shared.chart(country) }
        catch is CancellationError { }
        catch { self.error = error.localizedDescription }
    }
}

struct SearchView: View {
    @EnvironmentObject private var store: LibraryStore
    @State private var query = ""
    @State private var scope = 0
    @State private var songs: [Track] = []
    @State private var videos: [VideoResult] = []
    @State private var loading = false
    @State private var error: String?
    private var local: [Track] { store.tracks.filter { ($0.title + " " + $0.artist).localizedCaseInsensitiveContains(query) } }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                Picker("검색 범위", selection: $scope) { Text("음악").tag(0); Text("YouTube").tag(1); Text("내 음악").tag(2) }.pickerStyle(.segmented)
                if query.trimmingCharacters(in: .whitespaces).count < 2 {
                    ContentUnavailableView("무엇을 듣고 싶으세요?", systemImage: "magnifyingglass", description: Text("노래나 아티스트를 검색해 주세요.\n공식 곡 정보와 YouTube 영상을 함께 찾습니다."))
                } else {
                    if loading { ProgressView().frame(maxWidth: .infinity) }
                    if let error { Text(error).font(.caption).foregroundStyle(.secondary) }
                    if scope == 0 {
                        ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in TrackRow(track: song) { store.play(songs, at: index) } }
                    } else if scope == 1 {
                        ForEach(Array(videos.enumerated()), id: \.element.id) { index, video in
                            TrackRow(track: video.track()) { store.play(videos.map { $0.track() }, at: index) }
                        }
                    } else {
                        ForEach(Array(local.enumerated()), id: \.element.id) { index, song in TrackRow(track: song) { store.play(local, at: index) } }
                    }
                }
            }.padding(24)
        }.navigationTitle("Search").toolbarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "노래, 아티스트, YouTube")
            .task(id: query) {
                songs = []; videos = []; error = nil; loading = false
                guard query.trimmingCharacters(in: .whitespaces).count >= 2 else { return }
                do { try await Task.sleep(for: .milliseconds(450)) } catch { return }
                loading = true
                async let official = try? MusicCatalog.shared.search(query)
                async let youtube = try? YouTubeCatalog.shared.search(query)
                let result = await (official, youtube)
                guard !Task.isCancelled else { return }
                songs = result.0 ?? []; videos = result.1 ?? []; loading = false
                if result.0 == nil || result.1 == nil { error = "일부 검색을 불러오지 못했습니다. 연결을 확인하거나 다른 검색 범위를 선택해 주세요." }
            }
    }
}

struct RetryMessage: View {
    let message: String
    var action: () -> Void
    var body: some View {
        VStack(spacing: 12) {
            Text(message).font(.system(size: 13)).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button("다시 시도", action: action).buttonStyle(.glass)
        }.frame(maxWidth: .infinity).padding(24)
    }
}
