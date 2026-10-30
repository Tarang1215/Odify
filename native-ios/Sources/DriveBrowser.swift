import SwiftUI

struct DriveBrowser: View {
    @StateObject private var account = DriveAccount.shared
    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var engine: PlaybackEngine
    @Environment(\.dismiss) private var dismiss
    @State private var syncRequest = UUID()
    @State private var syncing = false
    @State private var syncNotice: String?
    @State private var syncGeneration = UUID()
    var body: some View {
        NavigationStack {
            Group {
                if let hash = account.accountHash {
                    DriveFolderView(folderID: "root", title: "Google Drive", accountHash: hash)
                        .id(hash)
                } else {
                    VStack(spacing: 22) {
                        Image(systemName: "externaldrive.badge.icloud").font(.system(size: 56, weight: .ultraLight)).foregroundStyle(.mint)
                        Text("Drive의 음악을 바로.").font(.system(size: 27, weight: .semibold, design: .rounded))
                        Text("Google 계정에 연결해 폴더를 탐색하고,\n음악을 가져오지 않고 바로 재생하세요.")
                            .font(.system(size: 14)).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        Button { Task { await account.connect() } } label: {
                            HStack { if account.connecting { ProgressView() }; Text(account.connecting ? "연결하는 중…" : "Google 계정 연결") }.padding(.horizontal, 12).padding(.vertical, 6)
                        }.buttonStyle(.glassProminent).disabled(account.connecting)
                        Text("폴더와 음원을 읽는 권한만 요청합니다.\nDrive의 파일을 수정하거나 삭제하지 않습니다.")
                            .font(.system(size: 11)).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }.padding(30).frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }.background(MusicBackdrop())
                .navigationDestination(for: DriveFolderRoute.self) { route in
                    DriveFolderView(folderID: route.id, title: route.name, accountHash: route.account)
                }
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { Button("닫기") { dismiss() } }
                    ToolbarItem(placement: .topBarTrailing) {
                        if account.connected {
                            Menu {
                                Text(account.email ?? "Google 계정")
                                Button("음악 보관함 다시 연결", systemImage: "arrow.triangle.2.circlepath") { syncRequest = UUID() }
                                Button("계정 연결 해제", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) {
                                    if store.engine.current?.driveFileID != nil { store.engine.pause() }
                                    account.signOut()
                                }
                            } label: { Image(systemName: "person.crop.circle").frame(width: 32, height: 32) }
                        }
                    }
                }
                .alert("Google Drive", isPresented: Binding(get: { account.error != nil }, set: { if !$0 { account.error = nil } })) {
                    Button("확인") { account.error = nil }
                } message: { Text(account.error ?? "") }
                .task { await account.restore() }
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                if syncing {
                    HStack { ProgressView(); Text("Odify 음악 폴더의 곡을 보관함에 연결하는 중…").font(.system(size: 12)) }
                        .padding(14).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16)).padding(.horizontal, 16)
                } else if let syncNotice {
                    Text(syncNotice).font(.system(size: 12)).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.vertical, 10)
                }
                if let error = engine.error {
                VStack(alignment: .leading, spacing: 10) {
                    Label(error, systemImage: "exclamationmark.circle").font(.system(size: 12)).textSelection(.enabled)
                    HStack {
                        Button("다시 시도") { engine.clearError(); engine.resume() }
                        Spacer()
                        Button("닫기") { engine.clearError() }
                    }.font(.system(size: 12, weight: .semibold))
                }.padding(16).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18)).padding(16)
                }
            }
        }
        .task(id: (account.accountHash ?? "signed-out") + syncRequest.uuidString) {
            guard let hash = account.accountHash else { return }
            let request = UUID(); syncGeneration = request
            syncing = true; syncNotice = nil
            defer { if syncGeneration == request { syncing = false } }
            do {
                try await DriveClient.shared.confirmAccount(account: hash, expectedEmail: account.email)
                let tracks = try await DriveClient.shared.musicLibrary(account: hash)
                try Task.checkCancellation()
                guard account.accountHash == hash, syncGeneration == request else { return }
                store.mergeDriveLibrary(tracks)
                syncNotice = tracks.isEmpty ? "읽을 수 있는 음악이 0곡입니다. 오른쪽 위에서 연결 계정을 확인하거나, 계정을 다시 연결해 주세요." : "\(tracks.count)곡을 보관함에 연결했습니다."
            } catch is CancellationError { }
            catch { if !Task.isCancelled, account.accountHash == hash { syncNotice = error.localizedDescription } }
        }
        .preferredColorScheme(.dark).id(account.accountHash ?? "signed-out")
    }
}

struct DriveFolderRoute: Hashable { let id: String; let name: String; let account: String }

enum DriveLocation: String, CaseIterable, Identifiable {
    case allMusic = "전체 음악", music = "Odify 폴더", personal = "내 Drive", shared = "공유받음"
    var id: String { rawValue }
}

struct DriveFolderView: View {
    let folderID: String
    let title: String
    let accountHash: String
    @EnvironmentObject private var store: LibraryStore
    @State private var files: [DriveFile] = []
    @State private var pageToken: String?
    @State private var loading = false
    @State private var error: String?
    @State private var location: DriveLocation = .allMusic
    @State private var generation = UUID()
    private var audio: [Track] { files.filter(\.playable).map { $0.track(account: accountHash) } }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                if folderID == "root" {
                    Picker("Drive 위치", selection: $location) {
                        ForEach(DriveLocation.allCases) { Text($0.rawValue).tag($0) }
                    }.pickerStyle(.segmented).padding(.bottom, 10)
                    if location == .music {
                        Text("Android에서 사용하던 Odify 음악 폴더를 위치에 관계없이 찾습니다.").font(.system(size: 11)).foregroundStyle(.secondary)
                    }
                }
                HStack {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(title).font(.system(size: 28, weight: .bold, design: .rounded)).lineLimit(2)
                        Text("폴더를 열고, 곡을 누르면 바로 스트리밍합니다.").font(.system(size: 11)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if !audio.isEmpty {
                        Button { store.play(audio) } label: { Image(systemName: "play.fill").padding(12) }.buttonStyle(.glassProminent).accessibilityLabel("이 폴더의 음악 재생")
                    }
                }.padding(.bottom, 16)
                ForEach(files.filter(\.isFolder)) { folder in
                    NavigationLink(value: DriveFolderRoute(id: folder.id, name: folder.name, account: accountHash)) {
                        HStack(spacing: 14) {
                            Image(systemName: "folder.fill").font(.system(size: 25, weight: .light)).foregroundStyle(.mint.opacity(0.8)).frame(width: 46, height: 46)
                            Text(folder.name).font(.system(size: 15, weight: .medium)).lineLimit(2)
                            Spacer(); Image(systemName: "chevron.right").font(.system(size: 11)).foregroundStyle(.secondary)
                        }.padding(.vertical, 6).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
                ForEach(Array(audio.enumerated()), id: \.element.id) { index, track in
                    TrackRow(track: store.driveTrack(track)) {
                        let retained = audio.map(store.driveTrack)
                        store.play(retained, at: index)
                    }
                }
                if loading { ProgressView().frame(maxWidth: .infinity).padding(20) }
                if let error { RetryMessage(message: error) { Task { await load(reset: files.isEmpty) } } }
                if pageToken != nil && !loading { Button("더 불러오기") { Task { await load(reset: false) } }.buttonStyle(.glass).frame(maxWidth: .infinity).padding(16) }
                if files.isEmpty && !loading && error == nil {
                    ContentUnavailableView("이 위치에 음악이나 폴더가 없습니다", systemImage: "folder", description: Text(folderID == "root" && location == .music ? "‘내 Drive’ 또는 ‘공유받음’에서 음악 폴더를 직접 열어 보세요." : "다른 폴더나 Drive 위치를 선택해 주세요."))
                }
            }.padding(24)
        }.navigationTitle(title).toolbarTitleDisplayMode(.inline)
            .refreshable { await load(reset: true) }
            .task(id: location) { await load(reset: true) }
    }
    private func load(reset: Bool) async {
        guard reset || !loading else { return }
        let request = UUID(); generation = request
        loading = true; error = nil
        defer { if generation == request { loading = false } }
        if reset { files = []; pageToken = nil }
        do {
            let result = try await DriveClient.shared.list(parent: folderID, page: pageToken, account: accountHash,
                shared: folderID == "root" && location == .shared, musicRoots: folderID == "root" && location == .music,
                allMusic: folderID == "root" && location == .allMusic)
            try Task.checkCancellation()
            guard generation == request else { return }
            let oldIDs = Set(files.map(\.id))
            files += result.files.filter { !oldIDs.contains($0.id) && ($0.isFolder || $0.playable) }
            pageToken = result.nextPageToken
        } catch is CancellationError { }
        catch { self.error = error.localizedDescription }
    }
}
