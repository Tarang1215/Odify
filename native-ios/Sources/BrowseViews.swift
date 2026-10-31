import SwiftUI

struct HomeView: View {
    var onSearch: () -> Void = {}
    var onProfile: () -> Void = {}
    var bottomClearance: CGFloat = 112
    @EnvironmentObject private var store: LibraryStore
    @ObservedObject private var account = DriveAccount.shared
    @State private var category = "daily"
    var body: some View {
        let mixes = store.mixes
        let hero = mixes.first(where: { $0.id == category }) ?? mixes.first
        let recent = store.recentTracks.isEmpty ? Array(store.personalTracks.prefix(10)) : Array(store.recentTracks.prefix(10))
        let owned = Set(store.personalTracks.map(\.recordingKey))
        let discoveries = Track.unique(store.chart.filter { !owned.contains($0.recordingKey) })
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                HStack {
                    Button(action: onSearch) { Image(systemName: "magnifyingglass").font(.system(size: 20)).frame(width: 44, height: 44) }.accessibilityLabel("음악 검색")
                    Spacer()
                    HStack(spacing: 9) { OdifyMark().fill(OdifyPalette.text).frame(width: 23, height: 28); Text("Odify").font(.system(size: 25, weight: .bold, design: .rounded)) }
                    Spacer()
                    Button(action: onProfile) { Image(systemName: "person.crop.circle").font(.system(size: 25)).frame(width: 44, height: 44) }.accessibilityLabel("프로필 및 음악 연결")
                }.buttonStyle(.plain)
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(["daily", "repeat", "unheard", "offline"], id: \.self) { key in
                            if key == "daily" || mixes.contains(where: { $0.id == key }) {
                                Button { category = key } label: {
                                    Text(["daily": "For you", "repeat": "On repeat", "unheard": "Discover", "offline": "Offline"][key] ?? key).font(.system(size: 12, weight: .medium)).padding(.horizontal, 17).padding(.vertical, 11)
                                        .foregroundStyle(category == key ? OdifyPalette.background : OdifyPalette.text)
                                        .background(category == key ? OdifyPalette.text : OdifyPalette.surface, in: Capsule())
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                }.scrollIndicators(.hidden)
                if let hero {
                    ZStack(alignment: .bottomTrailing) {
                        NavigationLink { TrackCollectionView(title: hero.name, subtitle: hero.reason, tracks: hero.tracks) } label: {
                            GeometryReader { geometry in
                                ZStack(alignment: .bottomLeading) {
                                    Artwork(address: hero.tracks.first?.artwork, title: hero.tracks.first?.album ?? hero.name, size: geometry.size.width, radius: 25)
                                        .frame(width: geometry.size.width, height: 206).clipped()
                                    LinearGradient(colors: [.black.opacity(0.04), .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text("YOUR DAILY SOUNDTRACK").font(.system(size: 9, weight: .medium)).tracking(1.6).foregroundStyle(.white.opacity(0.75))
                                        Text(hero.name).font(.system(size: 28, weight: .bold, design: .rounded)).foregroundStyle(.white)
                                        Text(hero.reason).font(.system(size: 12)).foregroundStyle(.white.opacity(0.8)).lineLimit(2).padding(.trailing, 58)
                                    }.padding(22)
                                }
                            }
                        }.buttonStyle(.plain)
                        Button { store.play(hero.tracks) } label: {
                            Image(systemName: "play.fill").font(.system(size: 18)).foregroundStyle(OdifyPalette.background).frame(width: 46, height: 46).background(OdifyPalette.text, in: Circle())
                        }.buttonStyle(.plain).padding(18).accessibilityLabel(hero.name + " 재생")
                    }.frame(height: 206).clipShape(RoundedRectangle(cornerRadius: 25))
                } else {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("음악을 연결하고, 나만의 리듬을 찾아요.").font(.headline)
                        Button("음악 소스 연결", action: onProfile).buttonStyle(.glass)
                    }.padding(24).frame(maxWidth: .infinity, alignment: .leading).background(OdifyPalette.surface, in: RoundedRectangle(cornerRadius: 24))
                }
                if !recent.isEmpty {
                    VStack(alignment: .leading, spacing: 14) {
                        sectionTitle(store.recentTracks.isEmpty ? "Ready when you are" : "Your recent moments", subtitle: store.recentTracks.isEmpty ? "내 음악에서 골라 들어요" : "최근 함께한 음악")
                        ScrollView(.horizontal) {
                            HStack(alignment: .top, spacing: 14) {
                                ForEach(recent) { track in
                                    Button { store.play(recent, at: recent.firstIndex(where: { $0.id == track.id }) ?? 0) } label: {
                                        VStack(alignment: .leading, spacing: 9) {
                                            Artwork(address: track.artwork, title: track.album.isEmpty ? track.title : track.album, size: 110, radius: 16)
                                            Text(track.title).font(.system(size: 12, weight: .semibold)).lineLimit(1).frame(width: 110, alignment: .leading)
                                        }
                                    }.buttonStyle(.plain)
                                }
                            }
                        }.scrollIndicators(.hidden)
                    }
                }
                if !store.artists.isEmpty {
                    VStack(alignment: .leading, spacing: 16) {
                        sectionTitle(store.personalTracks.isEmpty ? "Artists to explore" : "More from your artists", subtitle: "내 음악과 이 아티스트의 더 많은 곡")
                        ScrollView(.horizontal) {
                            HStack(alignment: .top, spacing: 20) {
                                ForEach(Array(store.artists.prefix(14)), id: \.artist) { artist in
                                    NavigationLink(value: artist) {
                                        VStack(spacing: 10) { ArtistPortrait(track: artist, size: 82); Text(artist.artist).font(.system(size: 11, weight: .medium)).lineLimit(1).frame(width: 90) }
                                    }.buttonStyle(.plain).accessibilityIdentifier("home.artist." + artist.id)
                                }
                            }
                        }.scrollIndicators(.hidden)
                    }
                }
                if !mixes.isEmpty {
                    VStack(alignment: .leading, spacing: 16) {
                        sectionTitle("Made for you", subtitle: store.listening.isEmpty ? "좋아요와 내 음악에서 시작해요 · 들을수록 취향이 쌓여요" : "들을수록 가까워지는 취향")
                        ScrollView(.horizontal) {
                            HStack(alignment: .top, spacing: 14) {
                                ForEach(mixes) { mix in MixCard(title: mix.name, caption: "\(mix.tracks.count)곡 · 나만의 믹스", tracks: mix.tracks) }
                            }
                        }.scrollIndicators(.hidden)
                    }
                }
                VStack(alignment: .leading, spacing: 16) {
                    sectionTitle("Find your mood", subtitle: "장르로 고르는 지금의 기분")
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                        MoodCard(title: "Focus", subtitle: "조용히 집중할 때", query: "instrumental piano", color: .gray, symbol: "headphones")
                        MoodCard(title: "Unwind", subtitle: "긴 하루의 끝", query: "acoustic chill", color: .gray, symbol: "leaf")
                        MoodCard(title: "Energy", subtitle: "기분을 끌어올려요", query: "dance pop", color: .gray, symbol: "bolt")
                        MoodCard(title: "Night drive", subtitle: "밤의 리듬", query: "rnb night", color: .gray, symbol: "moon")
                    }
                }
                if store.homeLoading { ProgressView().frame(maxWidth: .infinity) }
                if let error = store.homeError {
                    HStack {
                        Text(error).font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button("다시 불러오기") { Task { await store.loadHome(refresh: true) } }.font(.caption).buttonStyle(.glass)
                    }
                }
                if !discoveries.isEmpty {
                    VStack(alignment: .leading, spacing: 16) {
                        sectionTitle("Beyond your collection", subtitle: "인기곡에서 찾은 새로운 음악")
                        ForEach(Array(discoveries.prefix(6).enumerated()), id: \.element.id) { index, track in TrackRow(track: track) { store.play(discoveries, at: index) } }
                    }
                }
                Text("Your music. Your rhythm.").font(.caption).foregroundStyle(OdifyPalette.muted).padding(.vertical, 14).accessibilityIdentifier("home.end")
            }.padding(24)
        }.contentMargins(.bottom, bottomClearance, for: .scrollContent)
            .background(OdifyPalette.background).navigationTitle("").toolbarTitleDisplayMode(.inline)
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
    var body: some View {
        NavigationLink { TrackCollectionView(title: title, subtitle: caption, tracks: tracks) } label: {
            VStack(alignment: .leading, spacing: 9) {
                ZStack(alignment: .bottomLeading) {
                    Artwork(address: tracks.first?.artwork, title: tracks.first?.album ?? title, size: 144, radius: 20)
                    LinearGradient(colors: [.clear, .black.opacity(0.68)], startPoint: .center, endPoint: .bottom)
                    Text(title).font(.system(size: 18, weight: .semibold, design: .rounded)).foregroundStyle(.white).lineLimit(2).padding(13)
                }.frame(width: 144, height: 144).clipShape(RoundedRectangle(cornerRadius: 20))
                Text(caption).font(.system(size: 11)).foregroundStyle(OdifyPalette.muted)
            }.frame(width: 144, alignment: .leading)
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
                Image(systemName: symbol).font(.system(size: 20, weight: .light)).foregroundStyle(OdifyPalette.muted)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.system(size: 16, weight: .semibold))
                    Text(subtitle).font(.system(size: 10)).foregroundStyle(.secondary)
                }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(18)
                .background(OdifyPalette.surface, in: RoundedRectangle(cornerRadius: 20))
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
            }.buttonStyle(.plain).accessibilityIdentifier("track.play." + track.id)
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
    var bottomClearance: CGFloat = 112
    @EnvironmentObject private var store: LibraryStore
    @State private var songs: [Track] = []
    @State private var loading = true
    @State private var error: String?
    var body: some View {
        List {
            HStack(spacing: 22) {
                ArtistPortrait(track: artist, size: 108)
                VStack(alignment: .leading, spacing: 10) {
                    Text("ARTIST").font(.system(size: 10, weight: .bold)).tracking(2).foregroundStyle(OdifyPalette.muted)
                    Text(artist.artist).font(.system(size: 30, weight: .semibold, design: .rounded)).lineLimit(2).minimumScaleFactor(0.7)
                    Text("내 음악 + 공식 카탈로그").font(.system(size: 11)).foregroundStyle(.secondary)
                }
            }.padding(.vertical, 20).listRowBackground(Color.clear).listRowSeparator(.hidden)
            if loading { ProgressView("곡을 불러오는 중…").listRowBackground(Color.clear).listRowSeparator(.hidden) }
            if let error { RetryMessage(message: error) { Task { await load() } }.listRowBackground(Color.clear).listRowSeparator(.hidden) }
            HStack {
                Text("Songs · \(songs.count)").font(.system(size: 21, weight: .semibold))
                Spacer()
                Button { store.play(songs) } label: {
                    Image(systemName: "play.fill").font(.system(size: 17)).foregroundStyle(OdifyPalette.background)
                        .frame(width: 46, height: 46).background(OdifyPalette.text, in: Circle())
                }.buttonStyle(.plain).disabled(songs.isEmpty).accessibilityLabel("아티스트 곡 전체 재생").accessibilityIdentifier("artist.play")
            }.padding(.vertical, 8).listRowBackground(Color.clear).listRowSeparator(.hidden)
            ForEach(songs) { song in
                TrackRow(track: song) { store.play(songs, at: songs.firstIndex(where: { $0.id == song.id }) ?? 0) }
                    .frame(minHeight: 58).listRowBackground(Color.clear).listRowSeparator(.hidden)
            }
        }.listStyle(.plain).scrollContentBackground(.hidden).background(OdifyPalette.background)
            .contentMargins(.bottom, bottomClearance, for: .scrollContent)
            .navigationTitle(artist.artist).toolbarTitleDisplayMode(.inline).task(id: artist.artist) { await load() }
    }
    private func ordered(_ tracks: [Track]) -> [Track] {
        Track.unique(tracks).sorted {
            let order = $0.title.localizedStandardCompare($1.title)
            return order == .orderedSame ? $0.id < $1.id : order == .orderedAscending
        }
    }
    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        let local = store.tracks.filter { Track.normalized($0.artist) == Track.normalized(artist.artist) }
        songs = ordered(local)
        if store.isPreview {
            let fixtures = (1...45).map { Track(id: "preview:artist:\($0)", title: String(format: "Studio take %02d", $0), artist: artist.artist, source: .local, localFile: "OdifyPreview/silence.wav", duration: 90) }
            songs = ordered(local + fixtures); return
        }
        do {
            let catalog = try await MusicCatalog.shared.artist(artist)
            try Task.checkCancellation()
            songs = ordered(local + catalog)
            if catalog.isEmpty {
                let videos = try await YouTubeCatalog.shared.search(artist.artist + " music")
                try Task.checkCancellation()
                songs = ordered(local + videos.filter { !$0.isVariant }.map { $0.track() })
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
    @FocusState private var searchFocused: Bool
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
            .searchFocused($searchFocused)
            .onAppear { searchFocused = true }
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
