import AVKit
import SwiftUI

struct MiniPlayer: View {
    var wide: Bool
    var open: () -> Void
    @EnvironmentObject private var engine: PlaybackEngine
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        if let track = engine.current {
            HStack(spacing: 14) {
                Button(action: open) {
                    HStack(spacing: 12) {
                        Artwork(address: track.artwork, title: track.album.isEmpty ? track.title : track.album, size: 48, radius: 12)
                            .scaleEffect(engine.isPlaying ? 1 : 0.92)
                            .animation(reduceMotion ? nil : .spring(response: 0.55, dampingFraction: 0.7), value: engine.isPlaying)
                        VStack(alignment: .leading, spacing: 5) {
                            Text(track.title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                            Text(track.artist).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "waveform").font(.system(size: 16)).foregroundStyle(Color(uiColor: engine.tint).opacity(0.9))
                            .symbolEffect(.variableColor.iterative, options: .repeating, isActive: engine.isPlaying && !reduceMotion)
                    }.contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityIdentifier("player.expand")
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
                .background(Color(uiColor: engine.tint).opacity(0.22), in: RoundedRectangle(cornerRadius: 24))
                .glassEffect(.regular.tint(Color(uiColor: engine.tint).opacity(0.16)), in: .rect(cornerRadius: 24))
                .shadow(color: Color(uiColor: engine.tint).opacity(0.2), radius: 22, y: 7)
                .animation(reduceMotion ? nil : .easeInOut(duration: 1.1), value: engine.tint)
                .accessibilityIdentifier("player.mini")
        }
    }
}

struct MiniProgress: View {
    @ObservedObject var clock: PlaybackClock
    var body: some View {
        GeometryReader { geometry in
            Capsule().fill(.white.opacity(0.10)).overlay(alignment: .leading) {
                Capsule().fill(.white.opacity(0.6)).frame(width: geometry.size.width * (clock.duration > 0 ? min(1, max(0, clock.position / clock.duration)) : 0))
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
    var close: () -> Void
    @EnvironmentObject private var engine: PlaybackEngine
    @EnvironmentObject private var store: LibraryStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var panel: PlayerPanel?
    @State private var expanded = false
    @State private var sourcePage = 0
    @State private var options = false
    @State private var editLyrics = false
    @State private var lyricText = ""
    @State private var lyricEditTrack: Track?
    @State private var lyricSearchTrack: Track?
    @State private var dragOffset: CGFloat = 0
    @Namespace private var glass
    var body: some View {
        GeometryReader { geometry in
            let wide = geometry.size.width >= 850 && geometry.size.width > geometry.size.height
            let coverSize = min(wide ? geometry.size.width * 0.42 : geometry.size.width - 64, geometry.size.height * (wide ? 0.63 : 0.38))
            ZStack {
                PlayerGlassBackdrop(tint: Color(uiColor: engine.tint))
                VStack(spacing: 18) {
                    HStack {
                        GlassCircle(symbol: "chevron.down", label: "플레이어 닫기", action: close)
                            .accessibilityIdentifier("player.collapse")
                        Spacer()
                        VStack(spacing: 4) { Text("NOW PLAYING").font(.system(size: 9, weight: .bold)).tracking(2.4); Text(engine.current?.album ?? "Odify").font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1) }
                        Spacer()
                        GlassCircle(symbol: "ellipsis", label: "현재 곡 메뉴", action: { options = true })
                            .popover(isPresented: $options, arrowEdge: .top) { trackOptions.presentationCompactAdaptation(.popover) }
                    }.padding(.top, max(12, geometry.safeAreaInsets.top + 8))
                        .contentShape(Rectangle()).simultaneousGesture(collapseGesture)
                        .zIndex(20)
                    ScrollView {
                      VStack(spacing: 24) {
                       if wide {
                        HStack(spacing: 54) {
                            cover(size: coverSize)
                            VStack(spacing: 24) { trackHeading; SeekBar(clock: engine.clock, seek: engine.seek).id(engine.queue.currentID); transport }.frame(maxWidth: 420)
                        }.frame(maxWidth: .infinity)
                       } else {
                        cover(size: coverSize)
                        trackHeading.padding(.top, 8)
                        SeekBar(clock: engine.clock, seek: engine.seek).id(engine.queue.currentID)
                        transport
                       }
                       if let error = engine.error {
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "exclamationmark.circle")
                            Text(error).font(.system(size: 11)).lineLimit(3)
                            if let url = engine.restrictedYouTubeURL {
                                Link("YouTube에서 열기", destination: url).font(.system(size: 11, weight: .semibold))
                            } else {
                                Button("다시 시도") { engine.resume() }.font(.system(size: 11, weight: .semibold))
                            }
                        }.foregroundStyle(.secondary).padding(.horizontal, 8)
                       }
                      }.frame(maxWidth: .infinity)
                        .frame(minHeight: max(0, geometry.size.height - 180 - geometry.safeAreaInsets.top - geometry.safeAreaInsets.bottom), alignment: .center)
                        .padding(.vertical, 12)
                    }.scrollIndicators(.hidden).scrollBounceBehavior(.basedOnSize)
                        .simultaneousGesture(collapseGesture)
                    bottomGlass(availableHeight: geometry.size.height)
                }.padding(.horizontal, wide ? 42 : 26).padding(.bottom, max(16, geometry.safeAreaInsets.bottom + 12))
                if panel != nil {
                    Color.black.opacity(0.14).ignoresSafeArea().allowsHitTesting(false)
                        .transition(.opacity).zIndex(5)
                    VStack {
                        Spacer(minLength: 90)
                        glassPanel(height: expanded ? max(240, geometry.size.height - 220) : min(420, geometry.size.height * 0.52))
                            .frame(maxWidth: 760).padding(.bottom, 94 + geometry.safeAreaInsets.bottom)
                    }.padding(.horizontal, wide ? 42 : 20).zIndex(10)
                        .transition(.move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.95, anchor: .bottom)))
                }
            }
            .animation(reduceMotion ? nil : .spring(response: 0.48, dampingFraction: 0.86), value: panel)
            .animation(reduceMotion ? nil : .spring(response: 0.48, dampingFraction: 0.86), value: expanded)
            .offset(y: dragOffset)
            .sheet(item: $lyricSearchTrack) { LyricsSearchView(track: $0) }
            .sheet(isPresented: $editLyrics) {
                NavigationStack {
                    TextEditor(text: $lyricText).font(.system(size: 17)).padding(16).scrollContentBackground(.hidden)
                        .navigationTitle("가사 수정")
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button("취소") { editLyrics = false } }
                            ToolbarItem(placement: .confirmationAction) { Button("저장") { if let track = lyricEditTrack { store.saveLyrics(lyricText, for: track) }; editLyrics = false } }
                        }
                }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            }
        }.ignoresSafeArea(.container).accessibilityIdentifier("player.full")
    }
    private var collapseGesture: some Gesture {
        DragGesture(minimumDistance: 18)
            .onChanged { value in
                guard panel == nil, value.translation.height > 0,
                      value.translation.height > abs(value.translation.width) * 1.3 else { return }
                dragOffset = min(300, value.translation.height * 0.75)
            }
            .onEnded { value in
                if panel != nil {
                    if PlayerDismissal.shouldClose(x: value.translation.width, y: value.translation.height, predictedY: value.predictedEndTranslation.height) { panel = nil; expanded = false }
                    return
                }
                if PlayerDismissal.shouldClose(x: value.translation.width, y: value.translation.height, predictedY: value.predictedEndTranslation.height) {
                    close()
                } else { withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.86)) { dragOffset = 0 } }
            }
    }
    private func cover(size: CGFloat) -> some View {
        Artwork(address: engine.current?.artwork, title: engine.current?.album ?? "Odify", size: max(120, size), radius: 28)
            .shadow(color: .black.opacity(0.25), radius: 24, y: 12)
            .scaleEffect(engine.isPlaying ? 1 : 0.95)
            .animation(reduceMotion ? nil : .spring(response: 0.65, dampingFraction: 0.78), value: engine.isPlaying)
    }
    private var trackHeading: some View {
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 9) {
                Text(engine.current?.title ?? "음악을 선택해 주세요").font(.system(size: 26, weight: .semibold, design: .rounded)).lineLimit(2).minimumScaleFactor(0.7).accessibilityIdentifier("player.title")
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
                .foregroundStyle(engine.queue.repeatMode == .off ? Color.secondary : Color.mint).accessibilityLabel("반복: \(engine.queue.repeatMode.rawValue)")
        }.buttonStyle(.plain).frame(maxWidth: .infinity).minimumScaleFactor(0.7)
    }
    private func bottomGlass(availableHeight: CGFloat) -> some View {
        GlassEffectContainer(spacing: 20) {
            VStack(spacing: 12) {
                HStack(spacing: 14) {
                    GlassCircle(symbol: "quote.bubble", label: "가사") { togglePanel(.lyrics) }.glassEffectID("lyrics", in: glass).accessibilityIdentifier("player.lyrics.button")
                    TabView(selection: $sourcePage) {
                        Text(engine.current?.source.rawValue ?? "Local").tag(0)
                        Text(engine.queue.upcoming.first?.track.title ?? "다음 곡 없음").lineLimit(1).padding(.horizontal, 14).tag(1)
                    }.font(.system(size: 12, weight: .medium))
                        .tabViewStyle(.page(indexDisplayMode: .never)).frame(height: 46)
                        .glassEffect(.regular, in: .capsule).glassEffectID("source", in: glass)
                        .accessibilityLabel(sourcePage == 0 ? "음원 출처. 옆으로 쓸어 다음 곡 확인" : "다음 곡")
                    GlassCircle(symbol: "text.line.first.and.arrowtriangle.forward", label: "다음 재생 목록") { togglePanel(.queue) }.glassEffectID("queue", in: glass).accessibilityIdentifier("player.queue.button")
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
                if panel == .lyrics {
                    Button { lyricSearchTrack = engine.current } label: { Image(systemName: "magnifyingglass").frame(width: 40, height: 40) }.accessibilityLabel("가사 검색")
                    Button("수정") { beginLyricsEdit() }.font(.system(size: 12))
                }
                Button { expanded = false; panel = nil } label: { Image(systemName: "xmark").font(.system(size: 12)).frame(width: 40, height: 40) }.accessibilityLabel("패널 닫기").accessibilityIdentifier("player.panel.close")
            }.padding(.horizontal, 20).padding(.bottom, 8)
            if panel == .queue { QueueList() }
            else if let lyrics = engine.current?.lyrics, !lyrics.isEmpty {
                LyricsBody(text: lyrics, clock: engine.clock, seek: engine.seek).id(engine.current?.id)
            } else {
                VStack(spacing: 16) {
                    Text("등록된 가사가 없습니다.").font(.system(size: 13)).foregroundStyle(.secondary)
                    Button("가사 검색", systemImage: "magnifyingglass") { lyricSearchTrack = engine.current }.buttonStyle(.glassProminent)
                    Button("직접 추가", systemImage: "square.and.pencil") { beginLyricsEdit() }.buttonStyle(.glass)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }.frame(height: height).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28))
            .glassEffect(.regular.tint(Color(uiColor: engine.tint).opacity(0.13)), in: .rect(cornerRadius: 28))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(panel == .lyrics ? "player.lyrics.panel" : "player.queue.panel")
    }
    private func beginLyricsEdit() { lyricEditTrack = engine.current; lyricText = lyricEditTrack?.lyrics ?? ""; editLyrics = true }
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
                Button { lyricSearchTrack = track; options = false } label: { Label("가사 검색", systemImage: "magnifyingglass").frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 10) }
                Button { engine.toggleAutoplay() } label: { Label(engine.autoplayEnabled ? "자동 추천 재생 켜짐" : "자동 추천 재생 꺼짐", systemImage: "infinity").frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 10) }
                if let url = engine.externalYouTubeURL {
                    Link(destination: url) { Label("YouTube에서 열기", systemImage: "arrow.up.right.square").frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 10) }
                }
            }
            AirPlayButton().frame(height: 40)
        }.font(.system(size: 14)).buttonStyle(.plain).padding(20).frame(width: 270)
    }
}

enum PlayerDismissal {
    static func shouldClose(x: CGFloat, y: CGFloat, predictedY: CGFloat) -> Bool {
        y > 35 && y > abs(x) * 1.3 && (y > 90 || predictedY > 180)
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.layoutDirection) private var layoutDirection
    @GestureState private var dragging = false
    @State private var target: Double?
    private var duration: Double { clock.duration.isFinite ? max(0, clock.duration) : 0 }
    private var position: Double {
        let value = dragging ? (target ?? clock.position) : clock.position
        return value.isFinite ? min(duration, max(0, value)) : 0
    }
    var body: some View {
        VStack(spacing: 0) {
            GeometryReader { geometry in
                Capsule().fill(.white.opacity(0.18))
                    .overlay(alignment: .leading) {
                        Capsule().fill(.white.opacity(dragging ? 0.95 : 0.65))
                            .frame(width: geometry.size.width * (duration > 0 ? position / duration : 0))
                    }
                    .frame(height: dragging ? 8 : 4)
                    .animation(reduceMotion ? nil : .smooth(duration: 0.18), value: dragging)
                    .frame(maxHeight: .infinity)
                    // The visible rail stays small; the complete 44pt region
                    // remains available for touch and pointer scrubbing.
                    .frame(maxWidth: .infinity).contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 0)
                        .updating($dragging) { _, active, _ in active = true }
                        .onChanged { change in target = value(at: change.location.x, width: geometry.size.width) }
                        .onEnded { change in
                            let destination = value(at: change.location.x, width: geometry.size.width)
                            target = nil
                            if duration > 0 { seek(destination) }
                        })
            }.frame(height: 44)
                .allowsHitTesting(duration > 0)
            HStack {
                Text(timeText(position))
                Spacer()
                Text("−" + timeText(max(0, duration - position)))
            }.font(.caption2).monospacedDigit().foregroundStyle(.secondary)
                .accessibilityHidden(true)
        }
        .opacity(duration > 0 ? 1 : 0.5)
        .onChange(of: dragging) { _, active in if !active { target = nil } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("재생 위치")
        .accessibilityValue("\(timeText(position)) / \(timeText(duration))")
        .accessibilityHint("위아래로 쓸어 5초씩 이동합니다")
        .accessibilityAdjustableAction { direction in
            guard duration > 0 else { return }
            switch direction {
            case .increment: seek(min(duration, position + 5))
            case .decrement: seek(max(0, position - 5))
            @unknown default: break
            }
        }
        .accessibilityIdentifier("player.seek")
    }
    private func value(at x: CGFloat, width: CGFloat) -> Double {
        guard width > 0, duration > 0 else { return 0 }
        let fraction = min(1, max(0, x / width))
        return duration * Double(layoutDirection == .rightToLeft ? 1 - fraction : fraction)
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
