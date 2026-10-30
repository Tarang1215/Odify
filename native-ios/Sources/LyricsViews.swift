import SwiftUI

struct LyricsSearchView: View {
    let track: Track
    @EnvironmentObject private var store: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var artist: String
    @State private var results: [LyricCandidate] = []
    @State private var loading = false
    @State private var error: String?
    @State private var searchID = UUID()
    init(track: Track) { self.track = track; _title = State(initialValue: track.title); _artist = State(initialValue: track.artist) }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("곡명", text: $title).accessibilityIdentifier("lyrics.title")
                    TextField("가수", text: $artist)
                    Button { searchID = UUID() } label: { Label("가사 검색", systemImage: "magnifyingglass") }
                        .disabled(loading || title.trimmingCharacters(in: .whitespaces).isEmpty)
                } footer: { Text("곡명과 가수로 검색합니다. 앨범·길이·가사를 확인한 뒤 원하는 버전을 선택해 주세요.") }
                if loading { ProgressView("가사를 찾는 중…") }
                if let error { Text(error).foregroundStyle(.secondary) }
                Section("검색 결과 · LRCLIB") {
                    ForEach(results) { candidate in
                        NavigationLink {
                            ScrollView {
                                VStack(alignment: .leading, spacing: 20) {
                                    Text(candidate.title).font(.title2.bold())
                                    Text(candidate.artist + " · " + candidate.album).foregroundStyle(.secondary)
                                    Button("이 가사 사용", systemImage: "checkmark.circle") {
                                        store.saveLyrics(candidate.text, for: track); dismiss()
                                    }.buttonStyle(.glassProminent).accessibilityIdentifier("lyrics.apply")
                                    Text(candidate.plain.isEmpty ? LRCParser.lines(candidate.synced).map(\.text).joined(separator: "\n") : candidate.plain)
                                        .font(.body).lineSpacing(9).textSelection(.enabled)
                                }.padding(24).frame(maxWidth: .infinity, alignment: .leading)
                            }.navigationTitle("가사 미리보기")
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(candidate.title).font(.headline)
                                Text(candidate.artist + (candidate.album.isEmpty ? "" : " · " + candidate.album)).font(.caption).foregroundStyle(.secondary)
                                Text((candidate.synced.isEmpty ? "일반 가사" : "시간 맞춤 가사") + " · " + timeText(candidate.duration)).font(.caption2).foregroundStyle(.secondary)
                            }.padding(.vertical, 4)
                        }
                    }
                    if !loading, results.isEmpty, error == nil { Text("검색 결과가 없습니다. 곡명이나 가수 표기를 바꿔 보세요.").foregroundStyle(.secondary) }
                }
            }.navigationTitle("가사 검색")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기") { dismiss() } } }
                .task(id: searchID) {
                    loading = true; error = nil
                    defer { if !Task.isCancelled { loading = false } }
                    do {
                        let found = try await LyricsCatalog.shared.search(title: title, artist: artist)
                        try Task.checkCancellation(); results = found
                    } catch { if !Task.isCancelled { self.error = error.localizedDescription; results = [] } }
                }
        }.presentationDetents([.large]).presentationDragIndicator(.visible)
    }
}

struct LyricsBody: View {
    let text: String
    @ObservedObject var clock: PlaybackClock
    let seek: (Double) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let lines: [TimedLyric]
    init(text: String, clock: PlaybackClock, seek: @escaping (Double) -> Void) {
        self.text = text; self._clock = ObservedObject(wrappedValue: clock); self.seek = seek
        self.lines = LRCParser.lines(text)
    }
    var body: some View {
        let timed = lines
        if timed.isEmpty {
            ScrollView { Text(text).font(.system(size: 21, weight: .medium)).lineSpacing(12).frame(maxWidth: .infinity, alignment: .leading).padding(24) }
        } else {
            let active = timed.last(where: { $0.seconds <= clock.position })?.id
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 22) {
                        ForEach(timed) { line in
                            Button { seek(line.seconds) } label: {
                                Text(line.text.isEmpty ? "♪" : line.text).font(.system(size: 23, weight: .semibold))
                                    .foregroundStyle(active == line.id ? .white : .white.opacity(0.38))
                                    .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 3)
                            }.buttonStyle(.plain).id(line.id)
                        }
                    }.padding(24).padding(.bottom, 90)
                }.onChange(of: active, initial: true) { _, value in
                    if let value { withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.35)) { proxy.scrollTo(value, anchor: .center) } }
                }
            }
        }
    }
}
