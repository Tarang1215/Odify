import AVKit
import SwiftUI

struct MiniPlayer: View {
    var wide: Bool
    var open: () -> Void
    @EnvironmentObject private var engine: PlaybackEngine
    var body: some View {
        if let track = engine.current {
            HStack(spacing: 14) {
                Button(action: open) {
                    HStack(spacing: 12) {
                        Artwork(address: track.artwork, title: track.album.isEmpty ? track.title : track.album, size: 48, radius: 12)
                        VStack(alignment: .leading, spacing: 5) {
                            Text(track.title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                            Text(track.artist).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }.contentShape(Rectangle())
                }.buttonStyle(.plain)
                if wide { TimelineViewCompact(clock: engine.clock).frame(maxWidth: 280).padding(.horizontal, 20) }
                HStack(spacing: wide ? 22 : 12) {
                    if wide { Button(action: engine.previous) { Image(systemName: "backward.end.fill").frame(width: 40, height: 44) }.accessibilityLabel("이전 곡") }
                    Button(action: engine.toggle) {
                        Group { if engine.isBuffering { ProgressView() } else { Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 23, weight: .semibold)) } }.frame(width: 46, height: 46)
                    }.accessibilityLabel(engine.isPlaying ? "일시 정지" : "재생")
                    Button(action: engine.next) { Image(systemName: "forward.end.fill").frame(width: 40, height: 44) }.accessibilityLabel("다음 곡")
                }.buttonStyle(.plain)
                if wide { AirPlayButton().frame(width: 40, height: 40) }
            }.padding(10).padding(.horizontal, wide ? 10 : 0)
                .glassEffect(.regular, in: .rect(cornerRadius: 24))
                .overlay(alignment: .bottom) { MiniProgress(clock: engine.clock).padding(.horizontal, 22).padding(.bottom, 3) }
        }
    }
}

struct MiniProgress: View {
    @ObservedObject var clock: PlaybackClock
    var body: some View {
        GeometryReader { geometry in
            Capsule().fill(.white.opacity(0.10)).overlay(alignment: .leading) {
                Capsule().fill(.white.opacity(0.6)).frame(width: geometry.size.width * min(1, max(0, clock.position / max(1, clock.duration))))
            }
        }.frame(height: 2).accessibilityHidden(true)
    }
}

struct TimelineViewCompact: View {
    @ObservedObject var clock: PlaybackClock
    var body: some View {
        HStack(spacing: 10) {
            Text(timeText(clock.position))
            MiniProgress(clock: clock)
            Text(timeText(clock.duration))
        }.font(.system(size: 9, design: .monospaced)).foregroundStyle(.secondary)
    }
}

struct NowPlayingPane: View {
    var openFullPlayer: () -> Void
    @EnvironmentObject private var engine: PlaybackEngine
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                Text("NOW PLAYING").font(.system(size: 10, weight: .bold)).tracking(2).foregroundStyle(.secondary)
                Spacer()
                Button(action: openFullPlayer) { Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 12)).frame(width: 30, height: 30) }.buttonStyle(.plain).accessibilityLabel("전체 플레이어")
            }
            if let track = engine.current {
                GeometryReader { geometry in
                    Artwork(address: track.artwork, title: track.album.isEmpty ? track.title : track.album, size: geometry.size.width, radius: 24)
                }.aspectRatio(1, contentMode: .fit)
                VStack(alignment: .leading, spacing: 7) {
                    Text(track.title).font(.system(size: 23, weight: .semibold, design: .rounded)).lineLimit(2)
                    Text(track.artist).font(.system(size: 13)).foregroundStyle(.secondary).lineLimit(1)
                }
                HStack { Text(track.source.rawValue).font(.system(size: 10, weight: .medium)).padding(.horizontal, 10).padding(.vertical, 6).background(.white.opacity(0.07), in: .capsule); Spacer(); AirPlayButton().frame(width: 28, height: 28) }
                Divider().opacity(0.4)
                HStack { Text("Up next").font(.system(size: 15, weight: .semibold)); Spacer(); Text("\(engine.queue.upcoming.count)곡").font(.system(size: 10)).foregroundStyle(.secondary) }
                ScrollView {
                    LazyVStack(spacing: 16) {
                        ForEach(engine.queue.upcoming) { entry in
                            Button { engine.jump(entry.id) } label: {
                                HStack(spacing: 10) {
                                    Artwork(address: entry.track.artwork, title: entry.track.title, size: 38, radius: 9)
                                    VStack(alignment: .leading, spacing: 4) { Text(entry.track.title).font(.system(size: 12, weight: .medium)).lineLimit(1); Text(entry.track.artist).font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1) }
                                    Spacer(minLength: 0)
                                }
                            }.buttonStyle(.plain)
                        }
                    }
                }.scrollIndicators(.hidden)
            } else {
                ContentUnavailableView("음악을 선택해 주세요", systemImage: "waveform", description: Text("현재 곡과 다음 재생 목록을\n이곳에서 함께 볼 수 있습니다."))
                Spacer()
            }
        }.padding(22).background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 28)).padding(.bottom, 12)
    }
}

private enum PlayerPanel { case lyrics, queue }

struct FullPlayerView: View {
    @EnvironmentObject private var engine: PlaybackEngine
    @EnvironmentObject private var store: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var panel: PlayerPanel?
    @State private var expanded = false
    @State private var sourcePage = 0
    @State private var options = false
    @State private var editLyrics = false
    @State private var lyricText = ""
    @Namespace private var glass
    var body: some View {
        GeometryReader { geometry in
            let wide = geometry.size.width >= 850
            let coverSize = min(wide ? geometry.size.width * 0.42 : geometry.size.width - 64, geometry.size.height * (wide ? 0.63 : 0.38))
            ZStack {
                MusicBackdrop(tint: Color(uiColor: engine.tint))
                VStack(spacing: 18) {
                    HStack {
                        GlassCircle(symbol: "chevron.down", label: "플레이어 닫기", action: { dismiss() })
                        Spacer()
                        VStack(spacing: 4) { Text("NOW PLAYING").font(.system(size: 9, weight: .bold)).tracking(2.4); Text(engine.current?.album ?? "Odify").font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1) }
                        Spacer()
                        GlassCircle(symbol: "ellipsis", label: "현재 곡 메뉴", action: { options = true })
                            .popover(isPresented: $options, arrowEdge: .top) { trackOptions.presentationCompactAdaptation(.popover) }
                    }.padding(.top, 8)
                    ScrollView {
                      VStack(spacing: 24) {
                       if wide {
                        HStack(spacing: 54) {
                            cover(size: coverSize)
                            VStack(spacing: 24) { trackHeading; SeekBar(clock: engine.clock, seek: engine.seek); transport }.frame(maxWidth: 420)
                        }.frame(maxWidth: .infinity)
                       } else {
                        cover(size: coverSize)
                        trackHeading.padding(.top, 8)
                        SeekBar(clock: engine.clock, seek: engine.seek)
                        transport
                       }
                       if let error = engine.error {
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "exclamationmark.circle")
                            Text(error).font(.system(size: 11)).lineLimit(3)
                            Button("다시 시도") { engine.resume() }.font(.system(size: 11, weight: .semibold))
                        }.foregroundStyle(.secondary).padding(.horizontal, 8)
                       }
                      }.frame(maxWidth: .infinity).padding(.vertical, 12)
                    }.scrollIndicators(.hidden)
                    bottomGlass(availableHeight: geometry.size.height)
                }.padding(.horizontal, wide ? 42 : 26).padding(.bottom, 16)
                if expanded, panel != nil {
                    VStack {
                        Spacer(minLength: 70)
                        glassPanel(height: geometry.size.height - 160).padding(.bottom, 80)
                    }.padding(.horizontal, wide ? 42 : 20)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(reduceMotion ? nil : .smooth(duration: 0.4), value: panel)
            .animation(reduceMotion ? nil : .smooth(duration: 0.4), value: expanded)
            .sheet(isPresented: $editLyrics) {
                NavigationStack {
                    TextEditor(text: $lyricText).font(.system(size: 17)).padding(16).scrollContentBackground(.hidden)
                        .navigationTitle("가사 수정")
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button("취소") { editLyrics = false } }
                            ToolbarItem(placement: .confirmationAction) { Button("저장") { if let track = engine.current { store.saveLyrics(lyricText, for: track) }; editLyrics = false } }
                        }
                }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            }
        }
    }
    private func cover(size: CGFloat) -> some View {
        Artwork(address: engine.current?.artwork, title: engine.current?.album ?? "Odify", size: max(120, size), radius: 28)
            .shadow(color: .black.opacity(0.25), radius: 24, y: 12)
    }
    private var trackHeading: some View {
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 9) {
                Text(engine.current?.title ?? "음악을 선택해 주세요").font(.system(size: 26, weight: .semibold, design: .rounded)).lineLimit(2).minimumScaleFactor(0.7)
                Text(engine.current?.artist ?? "Odify").font(.system(size: 15)).foregroundStyle(.secondary).lineLimit(1)
            }.frame(maxWidth: .infinity, alignment: .leading)
            if let track = engine.current {
                Button { store.toggleLike(track) } label: { Image(systemName: store.snapshot.likedIDs.contains(track.id) ? "heart.fill" : "heart").font(.system(size: 21)).frame(width: 42, height: 44) }
                    .buttonStyle(.plain).foregroundStyle(store.snapshot.likedIDs.contains(track.id) ? .mint : .white).accessibilityLabel("좋아요")
            }
        }
    }
    private var transport: some View {
        HStack(spacing: 16) {
            Button(action: engine.shuffle) { Image(systemName: "shuffle").font(.system(size: 19)).frame(width: 40, height: 44) }.foregroundStyle(.secondary).accessibilityLabel("다음 곡 셔플")
            Button(action: engine.previous) { Image(systemName: "backward.end.fill").font(.system(size: 27)).frame(width: 46, height: 52) }.accessibilityLabel("이전 곡")
            Button(action: engine.toggle) {
                Group { if engine.isBuffering { ProgressView() } else { Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 32, weight: .medium)) } }.frame(width: 70, height: 70)
            }.glassEffect(.regular.interactive(), in: .circle).accessibilityLabel(engine.isPlaying ? "일시 정지" : "재생")
            Button(action: engine.next) { Image(systemName: "forward.end.fill").font(.system(size: 27)).frame(width: 46, height: 52) }.accessibilityLabel("다음 곡")
            Button(action: engine.cycleRepeat) { Image(systemName: engine.queue.repeatMode == .one ? "repeat.1" : "repeat").font(.system(size: 19)).frame(width: 40, height: 44) }
                .foregroundStyle(engine.queue.repeatMode == .off ? .secondary : .mint).accessibilityLabel("반복: \(engine.queue.repeatMode.rawValue)")
        }.buttonStyle(.plain).frame(maxWidth: .infinity).minimumScaleFactor(0.7)
    }
    private func bottomGlass(availableHeight: CGFloat) -> some View {
        GlassEffectContainer(spacing: 20) {
            VStack(spacing: 12) {
                if panel != nil && !expanded { glassPanel(height: min(290, availableHeight * 0.34)).glassEffectID("panel", in: glass) }
                HStack(spacing: 14) {
                    GlassCircle(symbol: "quote.bubble", label: "가사") { togglePanel(.lyrics) }.glassEffectID("lyrics", in: glass)
                    TabView(selection: $sourcePage) {
                        Text(engine.current?.source.rawValue ?? "Local").tag(0)
                        Text(engine.queue.upcoming.first?.track.title ?? "다음 곡 없음").lineLimit(1).padding(.horizontal, 14).tag(1)
                    }.font(.system(size: 12, weight: .medium))
                        .tabViewStyle(.page(indexDisplayMode: .never)).frame(height: 46)
                        .glassEffect(.regular, in: .capsule).glassEffectID("source", in: glass)
                        .accessibilityLabel(sourcePage == 0 ? "음원 출처. 옆으로 쓸어 다음 곡 확인" : "다음 곡")
                    GlassCircle(symbol: "text.line.first.and.arrowtriangle.forward", label: "다음 재생 목록") { togglePanel(.queue) }.glassEffectID("queue", in: glass)
                }
            }
        }.frame(maxWidth: 760)
    }
    private func togglePanel(_ value: PlayerPanel) {
        if panel == value { panel = nil; expanded = false } else { panel = value }
    }
    private func glassPanel(height: CGFloat) -> some View {
        VStack(spacing: 0) {
            Capsule().fill(.white.opacity(0.35)).frame(width: 34, height: 4).padding(.top, 10).padding(.bottom, 14)
                .frame(maxWidth: .infinity).contentShape(Rectangle())
                .gesture(DragGesture().onEnded { value in
                    if value.translation.height < -35 { expanded = true }
                    else if value.translation.height > 35 { if expanded { expanded = false } else { panel = nil } }
                })
                .accessibilityLabel(expanded ? "접기" : "패널 확장").accessibilityAddTraits(.isButton)
                .onTapGesture { expanded.toggle() }
            HStack {
                Text(panel == .lyrics ? "가사" : "다음 재생").font(.system(size: 17, weight: .semibold))
                Spacer()
                if panel == .lyrics { Button("수정") { beginLyricsEdit() }.font(.system(size: 12)) }
                Button { expanded = false; panel = nil } label: { Image(systemName: "xmark").font(.system(size: 12)).frame(width: 32, height: 30) }.accessibilityLabel("패널 닫기")
            }.padding(.horizontal, 20).padding(.bottom, 8)
            if panel == .queue { QueueList() }
            else if let lyrics = engine.current?.lyrics, !lyrics.isEmpty {
                ScrollView { Text(lyrics).font(.system(size: 19, weight: .medium)).lineSpacing(12).frame(maxWidth: .infinity, alignment: .leading).padding(22) }
            } else {
                VStack(spacing: 16) { Text("등록된 가사가 없습니다.").font(.system(size: 13)).foregroundStyle(.secondary); Button("가사 추가", systemImage: "square.and.pencil") { beginLyricsEdit() }.buttonStyle(.glass) }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }.frame(height: height).glassEffect(.regular.tint(.black.opacity(0.35)), in: .rect(cornerRadius: 28))
    }
    private func beginLyricsEdit() { lyricText = engine.current?.lyrics ?? ""; editLyrics = true }
    private var trackOptions: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(engine.current?.title ?? "현재 곡").font(.system(size: 15, weight: .semibold)).lineLimit(2).padding(.bottom, 8)
            if let track = engine.current {
                Button { store.toggleLike(track); options = false } label: { Label(store.snapshot.likedIDs.contains(track.id) ? "좋아요 취소" : "좋아요", systemImage: "heart").frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 10) }
                Menu("플레이리스트에 추가", systemImage: "text.badge.plus") {
                    ForEach(store.playlists) { playlist in Button(playlist.name) { store.add(track, to: playlist); options = false } }
                    Button("새 플레이리스트") { let playlist = store.createPlaylist("새 플레이리스트"); store.add(track, to: playlist); options = false }
                }.padding(.vertical, 10)
                Button { beginLyricsEdit(); options = false } label: { Label("가사 수정", systemImage: "square.and.pencil").frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 10) }
            }
            AirPlayButton().frame(height: 40)
        }.font(.system(size: 14)).buttonStyle(.plain).padding(20).frame(width: 270)
    }
}

struct QueueList: View {
    @EnvironmentObject private var engine: PlaybackEngine
    var body: some View {
        List {
            ForEach(engine.queue.entries) { entry in
                Button { engine.jump(entry.id) } label: {
                    HStack(spacing: 12) {
                        Artwork(address: entry.track.artwork, title: entry.track.title, size: 38, radius: 9)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(entry.track.title).font(.system(size: 13, weight: .medium)).lineLimit(1)
                            Text(entry.track.artist).font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        if engine.queue.currentID == entry.id { Image(systemName: "waveform").font(.system(size: 13)).foregroundStyle(.mint) }
                    }.foregroundStyle(engine.queue.currentID == entry.id ? .mint : .white)
                }.buttonStyle(.plain).listRowBackground(Color.clear).listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 7, leading: 16, bottom: 7, trailing: 12))
            }.onMove(perform: engine.move)
                .onDelete { offsets in for index in offsets.sorted(by: >) { if engine.queue.entries.indices.contains(index) { engine.remove(engine.queue.entries[index].id) } } }
        }.listStyle(.plain).scrollContentBackground(.hidden).environment(\.editMode, .constant(.active))
    }
}

struct SeekBar: View {
    @ObservedObject var clock: PlaybackClock
    var seek: (Double) -> Void
    @State private var dragging = false
    @State private var target: Double = 0
    var body: some View {
        VStack(spacing: 4) {
            Slider(value: Binding(get: { dragging ? target : min(clock.position, max(1, clock.duration)) }, set: { target = $0 }), in: 0...max(1, clock.duration)) { editing in
                dragging = editing
                if !editing { seek(target) }
            }.tint(.white.opacity(0.85)).accessibilityLabel("재생 위치")
            HStack { Text(timeText(dragging ? target : clock.position)); Spacer(); Text("−" + timeText(max(0, clock.duration - (dragging ? target : clock.position)))) }
                .font(.system(size: 10, design: .monospaced)).foregroundStyle(.secondary)
        }
    }
}

func timeText(_ value: Double) -> String {
    let seconds = value.isFinite ? Int(max(0, value)) : 0
    return String(format: "%d:%02d", seconds / 60, seconds % 60)
}

struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView(); view.tintColor = .white; view.activeTintColor = .systemMint
        view.prioritizesVideoDevices = false
        return view
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) { }
}
