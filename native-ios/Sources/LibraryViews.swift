import PhotosUI
import SwiftUI

struct LibraryView: View {
    @EnvironmentObject private var store: LibraryStore
    var importAction: (MusicSource) -> Void
    @State private var newPlaylist = false
    @State private var name = ""
    @Namespace private var covers
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 26) {
                HStack {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Your collection").font(.system(size: 30, weight: .bold, design: .rounded))
                        Text("좋아하는 음악을 당신의 방식으로.").font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button { name = ""; newPlaylist = true } label: { Image(systemName: "plus").frame(width: 42, height: 42) }
                        .buttonStyle(.glass).accessibilityLabel("새 플레이리스트")
                }.padding(.top, 16)
                HStack(spacing: 12) {
                    Button("파일 가져오기", systemImage: "folder") { importAction(.local) }.buttonStyle(.glass)
                    Button("Drive", systemImage: "externaldrive") { importAction(.drive) }.buttonStyle(.glass)
                }
                NavigationLink { TrackCollectionView(title: "좋아요", subtitle: "마음에 담아둔 곡", tracks: store.liked) } label: {
                    HStack(spacing: 16) {
                        Image(systemName: "heart.fill").font(.system(size: 22)).foregroundStyle(.pink).frame(width: 54, height: 54).background(.pink.opacity(0.12), in: RoundedRectangle(cornerRadius: 15))
                        VStack(alignment: .leading, spacing: 5) { Text("좋아요").font(.headline); Text("\(store.liked.count)곡").font(.caption).foregroundStyle(.secondary) }
                        Spacer(); Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                    }.padding(16).background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 22))
                }.buttonStyle(.plain)
                sectionTitle("Playlists", subtitle: "커버와 이름을 자유롭게 바꿔보세요")
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 145), spacing: 20)], alignment: .leading, spacing: 24) {
                    ForEach(store.playlists) { playlist in
                        NavigationLink {
                            PlaylistView(playlistID: playlist.id).navigationTransition(.zoom(sourceID: playlist.id, in: covers))
                        } label: {
                            VStack(alignment: .leading, spacing: 10) {
                                PlaylistArtwork(playlist: playlist, size: 144).matchedTransitionSource(id: playlist.id, in: covers)
                                Text(playlist.name).font(.system(size: 14, weight: .medium)).lineLimit(2)
                                Text("\(playlist.trackIDs.count)곡").font(.system(size: 11)).foregroundStyle(.secondary)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain)
                        .contextMenu {
                            Button("이름 수정", systemImage: "pencil") { name = playlist.name; renameID = playlist.id }
                            Button("삭제", systemImage: "trash", role: .destructive) { deleteTarget = playlist }
                        }
                    }
                }
                if store.playlists.isEmpty { ContentUnavailableView("첫 플레이리스트를 만들어 보세요", systemImage: "music.note.list") }
                sectionTitle("All music", subtitle: "\(store.tracks.count)곡")
                ForEach(Array(store.tracks.enumerated()), id: \.element.id) { index, track in TrackRow(track: track) { store.play(store.tracks, at: index) } }
            }.padding(24)
        }.navigationTitle("Library").toolbarTitleDisplayMode(.inline)
            .alert("새 플레이리스트", isPresented: $newPlaylist) {
                TextField("이름", text: $name)
                Button("만들기") { store.createPlaylist(name) }; Button("취소", role: .cancel) { }
            }
            .alert("이름 수정", isPresented: Binding(get: { renameID != nil }, set: { if !$0 { renameID = nil } })) {
                TextField("이름", text: $name)
                Button("저장") { if let playlist = store.playlists.first(where: { $0.id == renameID }) { store.rename(playlist, to: name) }; renameID = nil }
                Button("취소", role: .cancel) { renameID = nil }
            }
            .confirmationDialog("플레이리스트를 삭제하시겠습니까?", isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }), titleVisibility: .visible) {
                Button("삭제", role: .destructive) { if let target = deleteTarget { store.delete(target) }; deleteTarget = nil }
            }
    }
    @State private var renameID: String?
    @State private var deleteTarget: MusicPlaylist?
}

struct PlaylistArtwork: View {
    var playlist: MusicPlaylist
    var size: CGFloat
    @EnvironmentObject private var store: LibraryStore
    var body: some View {
        Artwork(address: playlist.coverFile ?? store.tracks(in: playlist).first?.artwork, title: playlist.name, size: size, radius: size * 0.12)
    }
}

struct PlaylistView: View {
    let playlistID: String
    @EnvironmentObject private var store: LibraryStore
    @State private var rename = false
    @State private var name = ""
    @State private var photo: PhotosPickerItem?
    @State private var photoPicker = false
    private var playlist: MusicPlaylist? { store.playlists.first { $0.id == playlistID } }
    var body: some View {
        GeometryReader { geometry in
            if let playlist {
                let songs = store.tracks(in: playlist)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 20) {
                        Group {
                            if geometry.size.width >= 670 {
                                HStack(alignment: .bottom, spacing: 28) {
                                    cover(playlist, size: min(260, geometry.size.width * 0.38))
                                    heading(playlist, songs: songs)
                                }
                            } else {
                                VStack(alignment: .leading, spacing: 22) {
                                    cover(playlist, size: min(280, geometry.size.width - 64)).frame(maxWidth: .infinity)
                                    heading(playlist, songs: songs)
                                }
                            }
                        }.padding(.top, 20).padding(.bottom, 12)
                        ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                            TrackRow(track: song, number: index + 1) { store.play(songs, at: index) }
                                .contextMenu { Button("플레이리스트에서 제거", systemImage: "minus.circle", role: .destructive) { store.remove(song, from: playlist) } }
                        }
                        if songs.isEmpty { ContentUnavailableView("음악을 담아보세요", systemImage: "music.note", description: Text("검색이나 곡 메뉴에서 이 플레이리스트에 추가할 수 있습니다.")) }
                    }.padding(24)
                }
                .navigationTitle(playlist.name).toolbarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button("이름 수정", systemImage: "pencil") { name = playlist.name; rename = true }
                            Button("커버 바꾸기", systemImage: "photo") { photoPicker = true }
                        } label: { Image(systemName: "ellipsis").frame(width: 32, height: 32) }
                    }
                }
                .photosPicker(isPresented: $photoPicker, selection: $photo, matching: .images)
                .task(id: photo) {
                    guard let photo else { return }
                    do { if let data = try await photo.loadTransferable(type: Data.self) { try store.setCover(data, for: playlist) } }
                    catch { store.notice = "커버를 변경하지 못했습니다: \(error.localizedDescription)" }
                }
                .alert("플레이리스트 이름", isPresented: $rename) {
                    TextField("이름", text: $name)
                    Button("저장") { store.rename(playlist, to: name) }; Button("취소", role: .cancel) { }
                }
            } else { ContentUnavailableView("플레이리스트가 없습니다", systemImage: "music.note.list") }
        }
    }
    private func cover(_ playlist: MusicPlaylist, size: CGFloat) -> some View {
        Button { photoPicker = true } label: { PlaylistArtwork(playlist: playlist, size: size) }
            .buttonStyle(.plain).accessibilityLabel("플레이리스트 커버 변경")
    }
    private func heading(_ playlist: MusicPlaylist, songs: [Track]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("PLAYLIST").font(.system(size: 10, weight: .bold)).tracking(2).foregroundStyle(.mint)
            Text(playlist.name).font(.system(size: 34, weight: .bold, design: .rounded)).lineLimit(3).minimumScaleFactor(0.7)
            Text("\(songs.count)곡 · 나의 플레이리스트").font(.system(size: 12)).foregroundStyle(.secondary)
            HStack(spacing: 12) {
                Button("재생", systemImage: "play.fill") { store.play(songs) }.buttonStyle(.glassProminent).disabled(songs.isEmpty)
                Button { store.play(songs.shuffled()) } label: { Image(systemName: "shuffle").padding(5) }.buttonStyle(.glass).disabled(songs.isEmpty).accessibilityLabel("셔플 재생")
            }.padding(.top, 8)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
