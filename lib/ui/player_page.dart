import 'dart:async';
import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../plugins/plugin_manager.dart';
import 'actions.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../audio/engine.dart';
import '../core/icons.dart';
import '../core/theme.dart';
import '../data/library.dart';
import '../data/lyrics.dart';
import '../data/track.dart';
import '../nav/nav.dart';
import '../state.dart';
import 'widgets.dart';

const double _kCtrlH = 156;

String modeIcon(PlayMode m) => switch (m) {
      PlayMode.sequence => NIcons.modeSeq,
      PlayMode.shuffle => NIcons.modeShuffle,
      PlayMode.repeatOne => NIcons.modeOne,
    };

/// 播放页：从底部整体升起，盖住内容和底部导航，但不盖品牌栏。
class PlayerLayer extends ConsumerStatefulWidget {
  const PlayerLayer({super.key});
  @override
  ConsumerState<PlayerLayer> createState() => _PlayerLayerState();
}

class _PlayerLayerState extends ConsumerState<PlayerLayer> with TickerProviderStateMixin {
  late final AnimationController _slide = AnimationController(vsync: this, duration: const Duration(milliseconds: 400));
  late final AnimationController _ly = AnimationController(vsync: this, duration: const Duration(milliseconds: 500));
  bool _showTr = true;
  double? _scrub;

  @override
  void dispose() {
    _slide.dispose();
    _ly.dispose();
    super.dispose();
  }

  void _sync(NavState n) {
    if (n.playerOpen) {
      _ly.springTo(n.page == Pg.lyrics ? 1 : 0);
      _slide.springTo(1);
    } else {
      _slide.springTo(0);
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _sync(ref.read(navProvider));
    });
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(navProvider.select((n) => (n.page, n.playerOpen)), (_, _) => _sync(ref.read(navProvider)));
    final c = context.c;
    return AnimatedBuilder(
      animation: _slide,
      builder: (context, _) {
        if (_slide.value == 0) return const SizedBox.shrink();
        return LayoutBuilder(builder: (context, box) {
          final dy = (1 - _slide.value) * box.maxHeight;
          return Transform.translate(
            offset: Offset(0, dy),
            child: IgnorePointer(
              ignoring: !ref.read(navProvider).playerOpen,
              child: Container(
                width: double.infinity,
                height: double.infinity,
                color: c.dark ? c.bar : const Color(0xFFD3E5EA),
                child: _body(context, box.biggest),
              ),
            ),
          );
        });
      },
    );
  }

  Widget _body(BuildContext context, Size size) {
    final c = context.c;
    final bottomPad = MediaQuery.paddingOf(context).bottom;
    final track = ref.watch(playerProvider.select((s) => s.current));
    final nav = ref.read(navProvider.notifier);
    final w = size.width;
    final bh = size.height;

    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      // ---- 顶栏（按住下拉可跟手收起播放页）
      GestureDetector(
        behavior: HitTestBehavior.opaque,
        onVerticalDragStart: (_) => _slide.stop(),
        onVerticalDragUpdate: (d) => _slide.value = (_slide.value - d.delta.dy / bh).clamp(0.0, 1.0),
        onVerticalDragEnd: (d) {
          final v = d.primaryVelocity ?? 0;
          if (v > 700 || (v > -700 && _slide.value < .8)) {
            HapticFeedback.selectionClick();
            nav.back();
          } else {
            _slide.springTo(1);
          }
        },
        onVerticalDragCancel: () => _slide.springTo(1),
        child: Container(
        height: 48,
        decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.l2))),
        padding: const EdgeInsets.symmetric(horizontal: 6),
        child: Row(children: [
          Tap(
            label: '收起播放页',
            onTap: nav.back,
            child: SizedBox(width: 44, height: 44, child: Center(child: NIcon(NIcons.chevronDown, size: 20, color: c.k))),
          ),
          Expanded(
            child: AnimatedBuilder(
              animation: _ly,
              builder: (_, _) => Center(child: Text(_ly.value > .5 ? 'LYRICS · 歌词' : 'NOW PLAYING · 正在播放', style: NText.mono(12, color: c.s, ls: .1))),
            ),
          ),
          AnimatedBuilder(
            animation: _ly,
            builder: (_, _) => Opacity(
              opacity: _ly.value,
              child: IgnorePointer(
                ignoring: _ly.value < .9,
                child: Tap(
                  label: _showTr ? '关闭译文' : '显示译文',
                  onTap: () => setState(() => _showTr = !_showTr),
                  child: SizedBox(
                    width: 44,
                    height: 44,
                    child: Center(
                      child: Container(
                        width: 28,
                        height: 28,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(color: _showTr ? c.k : null, border: Border.all(color: c.k)),
                        child: Text('译', style: NText.sans(13, w: FontWeight.w700, color: _showTr ? c.tagFg : c.k)),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ]),
      ),
      ),
      Expanded(
        child: track == null
            ? Center(child: Text('NO TRACK · 还没有在播放的歌曲', style: NText.mono(12, color: c.s, ls: .08)))
            : AnimatedBuilder(
                animation: _ly,
                builder: (context, _) => _stage(context, track, w, bh - 48, bottomPad),
              ),
      ),
    ]);
  }

  Widget _stage(BuildContext context, Track t, double w, double h, double bottomPad) {
    final c = context.c;
    final nav = ref.read(navProvider.notifier);
    final lt = _ly.value;
    final ctrlH = _kCtrlH + bottomPad;
    final free = h - ctrlH;
    final coverNow = (free - 96).clamp(120.0, w - 56);
    final nowTop = ((free - coverNow - 84) / 2).clamp(8.0, 40.0);
    double lerp(double a, double b) => a + (b - a) * lt;

    final cs = lerp(coverNow, 48);
    final cl = lerp((w - coverNow) / 2, 18);
    final ct = lerp(nowTop, 8);


    return Stack(clipBehavior: Clip.hardEdge, children: [
      // 歌词列
      Positioned(
        left: 0,
        right: 0,
        top: 68,
        height: (free - 68).clamp(0.0, double.infinity),
        child: IgnorePointer(
          ignoring: lt < .9,
          child: Opacity(opacity: Curves.easeIn.transform(((lt - .7) / .3).clamp(0.0, 1.0)), child: _LyricsColumn(track: t, showTr: _showTr)),
        ),
      ),
      // 封面（飞入左上角）
      Positioned(
        left: cl,
        top: ct,
        width: cs,
        height: cs,
        child: Semantics(
          button: true,
          label: lt > .5 ? '返回封面' : '查看歌词',
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => nav.go(lt > .5 ? Pg.now : Pg.lyrics),
            // 始终按大封面的尺寸解码，缩放只是变换，切换时不重新加载、不闪
            child: FittedBox(fit: BoxFit.fill, child: Cover(t, size: coverNow)),
          ),
        ),
      ),
      // 标题 / 歌手：两套各留在自己的位置，交叉淡入淡出，不在歌词上方穿行
      if (lt < .45)
        Positioned(
          left: 28,
          top: nowTop + coverNow + 18 - 12 * lt,
          width: w - 56,
          child: Opacity(
            opacity: (1 - lt / .45).clamp(0.0, 1.0),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              Text(t.title, maxLines: 1, overflow: TextOverflow.ellipsis, textAlign: TextAlign.center,
                  style: NText.sans(24, w: FontWeight.w800, color: c.k, height: 1.25)),
              const SizedBox(height: 6),
              Text(t.artist, maxLines: 1, overflow: TextOverflow.ellipsis, textAlign: TextAlign.center,
                  style: NText.sans(15, color: c.s, height: 1.3)),
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text('${t.qualityLine1}  ${t.qualityLine2}'.trim(), maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.mono(11, color: c.s, ls: .06)),
              ),
            ]),
          ),
        ),
      if (lt > .75)
        Positioned(
          left: cl + cs + 12,
          top: ct + (cs - 38) / 2,
          width: (w - cl - cs - 30).clamp(40.0, w),
          child: Opacity(
            opacity: ((lt - .75) / .25).clamp(0.0, 1.0),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
              Text(t.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(16, w: FontWeight.w800, color: c.k, height: 1.25)),
              Text(t.artist, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(13, color: c.s, height: 1.3)),
            ]),
          ),
        ),
      // 控制组（两页 y 完全一致）
      Positioned(left: 0, right: 0, bottom: 0, height: ctrlH, child: _controls(context, t, bottomPad)),
    ]);
  }

  Widget _controls(BuildContext context, Track t, double bottomPad) {
    final c = context.c;
    final engine = ref.read(engineProvider);
    final nav = ref.read(navProvider.notifier);
    final liked = ref.watch(libraryProvider.select((l) => l.likes.contains(t.id))) || ref.watch(cloudLikedIdsProvider).contains(t.id);
    final playing = ref.watch(playerProvider.select((s) => s.playing));
    final loading = ref.watch(playerProvider.select((s) => s.loading));
    final mode = ref.watch(playerProvider.select((s) => s.mode));
    return Padding(
      padding: EdgeInsets.fromLTRB(22, 0, 22, bottomPad),
      child: Column(children: [
        ScrubBar(onScrub: (f) => setState(() => _scrub = f)),
        const SizedBox(height: 2),
        TimeRow(scrubFrac: _scrub),
        const SizedBox(height: 10),
        SizedBox(
          height: 48,
          child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            SquareBtn(icon: NIcons.prev, label: '上一首', onTap: engine.previous),
            const SizedBox(width: 26),
            Tap(
              label: playing ? '暂停' : '播放',
              onTap: engine.toggle,
              child: Container(
                width: 48,
                height: 48,
                alignment: Alignment.center,
                color: c.p,
                child: _DelayedSpinner(
                  loading: loading,
                  child: NIcon(playing ? NIcons.pause : NIcons.play, size: 22, color: Colors.white),
                ),
              ),
            ),
            const SizedBox(width: 26),
            SquareBtn(icon: NIcons.next, label: '下一首', onTap: engine.next),
          ]),
        ),
        const SizedBox(height: 10),
        SizedBox(
          height: 44,
          child: Row(children: [
            Tap(
              label: '播放模式：${modeLabel(mode)}，点击切换',
              onTap: () {
                engine.cycleMode();
                ref.read(toastProvider.notifier).show(modeLabel(PlayMode.values[(mode.index + 1) % 3]));
              },
              child: SizedBox(width: 44, height: 44, child: Center(child: NIcon(modeIcon(mode), size: 22, color: c.k))),
            ),
            Expanded(
              child: Center(
                child: t.demo
                    ? const SizedBox.shrink()
                    : Tap(
                        label: liked ? '取消喜欢' : '喜欢',
                        onTap: () => toggleLikeSync(ref, t),
                        child: SizedBox(width: 44, height: 44, child: Center(child: NIcon(liked ? NIcons.heart : NIcons.heartLine, size: 22, color: liked ? c.p : c.k))),
                      ),
              ),
            ),
            Tap(
              label: '播放队列',
              onTap: nav.openQueue,
              child: SizedBox(width: 44, height: 44, child: Center(child: NIcon(NIcons.queue, size: 22, color: c.k))),
            ),
          ]),
        ),
      ]),
    );
  }
}

// ================================================================ 歌词列
class _LyricsColumn extends ConsumerWidget {
  const _LyricsColumn({required this.track, required this.showTr});
  final Track track;
  final bool showTr;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = context.c;
    final async = ref.watch(lyricsProvider(track.id));
    return async.when(
      loading: () => Center(child: Text('LOADING · 正在加载歌词', style: NText.mono(12, color: c.s, ls: .08))),
      error: (_, _) => Center(child: Text('NO LYRICS · 暂无歌词', style: NText.mono(12, color: c.s, ls: .08))),
      data: (lines) {
        if (lines.isEmpty) {
          return Center(child: Text('NO LYRICS · 暂无歌词', style: NText.mono(12, color: c.s, ls: .08)));
        }
        return _LyricsList(lines: lines, showTr: showTr);
      },
    );
  }
}

class _LyricsList extends ConsumerStatefulWidget {
  const _LyricsList({required this.lines, required this.showTr});
  final List<LyricLine> lines;
  final bool showTr;
  @override
  ConsumerState<_LyricsList> createState() => _LyricsListState();
}

class _LyricsListState extends ConsumerState<_LyricsList> {
  List<double> _h = const [];
  double _w = 0;
  bool _tr = true;
  int _cur = 0;
  bool _first = true;

  static const double _gap = 22;

  TextStyle _origStyle(Color col) => NText.sans(20, w: FontWeight.w700, color: col, height: 1.45);
  TextStyle _trStyle(Color col) => NText.sans(14, color: col, height: 1.5);

  void _measure(double w) {
    if (w == _w && _tr == widget.showTr && _h.length == widget.lines.length) return;
    _w = w;
    _tr = widget.showTr;
    _h = [
      for (final l in widget.lines)
        () {
          if (l.orig.trim().isEmpty) return 18.0; // 间奏：只占一小行
          final tp = TextPainter(text: TextSpan(text: l.orig.isEmpty ? ' ' : l.orig, style: _origStyle(Colors.black)), textDirection: TextDirection.ltr)
            ..layout(maxWidth: w);
          var h = tp.height;
          if (_tr && l.tr != null && l.tr!.isNotEmpty) {
            final tt = TextPainter(text: TextSpan(text: l.tr, style: _trStyle(Colors.black)), textDirection: TextDirection.ltr)..layout(maxWidth: w);
            h += 2 + tt.height;
          }
          return h;
        }(),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final lines = widget.lines;
    return LayoutBuilder(builder: (context, box) {
      const pad = 22.0;
      final w = box.maxWidth - pad * 2;
      _measure(w);
      return PosBuilder(
        granularityMs: 40,
        builder: (context, pos, s) {
          final cur = currentLine(lines, pos);
          // 当前行在第 2 个位置：目标偏移 = 当前行之前所有行高度之和 - 上一行的高度
          var top = 0.0;
          final tops = <double>[];
          for (var i = 0; i < lines.length; i++) {
            tops.add(top);
            top += _h[i] + _gap;
          }
          final anchor = cur > 0 ? tops[cur - 1] : 0.0;
          final target = -anchor;
          if (cur != _cur) _cur = cur;
          final dur = _first ? Duration.zero : const Duration(milliseconds: 600);
          _first = false;

          final next = cur + 1 < lines.length ? lines[cur + 1].t : lines[cur].t + 5000;
          final span = (next - lines[cur].t).clamp(300, 20000);
          final prog = ((pos - lines[cur].t) / span).clamp(0.0, 1.0);

          return ClipRect(
            child: TweenAnimationBuilder<double>(
              tween: Tween(end: target),
              duration: dur,
              curve: kEase,
              builder: (context, off, child) => Stack(clipBehavior: Clip.none, children: [
                for (var i = 0; i < lines.length; i++)
                  if (tops[i] + off > -_h[i] - 80 && tops[i] + off < box.maxHeight + 80)
                    Positioned(
                      left: pad,
                      width: w,
                      top: tops[i] + off,
                      child: _line(context, c, lines[i], i, cur, prog),
                    ),
              ]),
            ),
          );
        },
      );
    });
  }

  Widget _line(BuildContext context, NekoColors c, LyricLine l, int i, int cur, double prog) {
    final isCur = i == cur;
    final dist = (i - cur).abs();
    final base = c.dark ? c.k : c.lyFg2;
    final hasTr = widget.showTr && l.tr != null && l.tr!.isNotEmpty;
    Widget text(Color oc, Color tc) => Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
          Text(l.orig.isEmpty ? ' ' : l.orig, style: _origStyle(oc)),
          if (hasTr) Padding(padding: const EdgeInsets.only(top: 2), child: Text(l.tr!, style: _trStyle(tc))),
        ]);

    Widget w;
    if (l.orig.trim().isEmpty) {
      // 间奏：三个小圆点，当前时随进度依次点亮
      Widget dot(int k) {
        final on = isCur && prog > k / 3;
        return Container(
          width: 6,
          height: 6,
          margin: const EdgeInsets.only(right: 6),
          decoration: BoxDecoration(shape: BoxShape.circle, color: on ? c.lyFg : base.withValues(alpha: isCur ? .5 : .3)),
        );
      }
      return SizedBox(height: 18, child: Row(children: [dot(0), dot(1), dot(2)]));
    } else if (isCur) {
      w = Stack(children: [
        text(base, c.s),
        Positioned.fill(
          child: ClipRect(
            clipper: _FracClipper(prog),
            child: Container(color: c.lyBg, child: Align(alignment: Alignment.topLeft, child: text(c.lyFg, c.lyFg))),
          ),
        ),
      ]);
    } else {
      w = Opacity(opacity: dist == 1 ? .85 : .6, child: text(c.dark ? c.s : base, c.s));
      if (c.dark) {
        final sigma = dist == 1 ? 1.2 : (dist == 2 ? 2.0 : 2.6);
        w = ImageFiltered(imageFilter: ImageFilter.blur(sigmaX: sigma, sigmaY: sigma, tileMode: TileMode.decal), child: w);
      }
    }
    return Tap(
      label: l.orig,
      onTap: () => ref.read(engineProvider).seek(l.t),
      child: w,
    );
  }
}

class _FracClipper extends CustomClipper<Rect> {
  _FracClipper(this.f);
  final double f;
  @override
  Rect getClip(Size s) => Rect.fromLTWH(0, 0, s.width * f, s.height);
  @override
  bool shouldReclip(_FracClipper o) => o.f != f;
}

// ================================================================ 播放队列（3/4 屏高）
class QueueSheet extends ConsumerStatefulWidget {
  const QueueSheet({super.key});
  @override
  ConsumerState<QueueSheet> createState() => _QueueSheetState();
}

class _QueueSheetState extends ConsumerState<QueueSheet> with SingleTickerProviderStateMixin {
  late final AnimationController _a = AnimationController(vsync: this, duration: const Duration(milliseconds: 360));
  final _scroll = ScrollController();
  double _target = 0;

  @override
  void dispose() {
    _a.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final open = ref.watch(navProvider.select((n) => n.queueOpen));
    // 按「目标」判断：弹簧动画的 status 不分方向，旧写法关一次后就再也打不开
    final target = open ? 1.0 : 0.0;
    if (target != _target && open) {
      _target = target;
      _a.springTo(1);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        final s = ref.read(playerProvider);
        if (_scroll.hasClients && s.index > 2) _scroll.jumpTo((s.index - 2) * 64.0);
      });
    } else if (target != _target) {
      _target = target;
      _a.springTo(0);
    }
    final c = context.c;
    final nav = ref.read(navProvider.notifier);
    final screenH = MediaQuery.sizeOf(context).height;
    final bottomPad = MediaQuery.paddingOf(context).bottom;
    return AnimatedBuilder(
      animation: _a,
      builder: (context, _) {
        if (_a.value == 0) return const SizedBox.shrink();
        final h = screenH * .75;
        return Stack(children: [
          Positioned.fill(
            child: GestureDetector(
              behavior: _target == 1 ? HitTestBehavior.opaque : HitTestBehavior.translucent,
              onTap: nav.closeQueue,
              child: ColoredBox(color: Color.fromRGBO(0, 0, 0, .5 * _a.value)),
            ),
          ),
          Positioned(
            left: 0,
            right: 0,
            bottom: -h * (1 - _a.value),
            height: h,
            child: Container(
              decoration: BoxDecoration(color: c.dark ? c.bar : const Color(0xFFE2EDEE), border: Border(top: BorderSide(color: c.k, width: 3))),
              child: Consumer(builder: (context, ref, _) {
                final s = ref.watch(playerProvider);
                final engine = ref.read(engineProvider);
                return Column(children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(18, 14, 8, 6),
                    child: Row(children: [
                      Expanded(
                        child: TagRow('QUEUE · 播放队列 ${s.queue.length} 首', lineEnd: 8),
                      ),
                      LinkBtn('清空待播', size: 14, minHeight: 44, onTap: s.queue.length > 1 ? engine.clearUpcoming : null),
                      Tap(label: '关闭队列', onTap: nav.closeQueue, child: SizedBox(width: 44, height: 44, child: Center(child: NIcon(NIcons.close, size: 14, color: c.k)))),
                    ]),
                  ),
                  Container(height: 1, color: c.l2),
                  Expanded(
                    child: s.queue.isEmpty
                        ? Center(child: Text('EMPTY · 队列是空的', style: NText.mono(12, color: c.s, ls: .08)))
                        : ListView.builder(
                            controller: _scroll,
                            padding: EdgeInsets.fromLTRB(18, 0, 6, bottomPad + 12),
                            itemExtent: 64,
                            itemCount: s.queue.length,
                            itemBuilder: (context, i) {
                              final t = s.queue[i];
                              final cur = i == s.index;
                              return Row(children: [
                                Expanded(
                                  child: TrackRow(
                                    track: t,
                                    current: cur,
                                    onTap: () => engine.playIndex(i),
                                  ),
                                ),
                                Tap(
                                  label: '从队列移除 ${t.title}',
                                  onTap: () => engine.removeAt(i),
                                  child: SizedBox(width: 44, height: 44, child: Center(child: NIcon(NIcons.close, size: 12, color: c.s))),
                                ),
                              ]);
                            },
                          ),
                  ),
                ]);
              }),
            ),
          ),
        ]);
      },
    );
  }
}


/// 缓冲超过 0.4 秒才显示转圈，拖动进度条这种短暂缓冲不打断按钮图标。
class _DelayedSpinner extends StatefulWidget {
  const _DelayedSpinner({required this.loading, required this.child});
  final bool loading;
  final Widget child;
  @override
  State<_DelayedSpinner> createState() => _DelayedSpinnerState();
}

class _DelayedSpinnerState extends State<_DelayedSpinner> {
  Timer? _t;
  bool _show = false;

  @override
  void initState() {
    super.initState();
    _sync();
  }

  @override
  void didUpdateWidget(_DelayedSpinner o) {
    super.didUpdateWidget(o);
    if (o.loading != widget.loading) _sync();
  }

  void _sync() {
    _t?.cancel();
    if (!widget.loading) {
      if (_show) setState(() => _show = false);
      return;
    }
    _t = Timer(const Duration(milliseconds: 400), () {
      if (mounted) setState(() => _show = true);
    });
  }

  @override
  void dispose() {
    _t?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedSwitcher(
        duration: const Duration(milliseconds: 160),
        child: _show
            ? const SizedBox(key: ValueKey(1), width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
            : KeyedSubtree(key: const ValueKey(0), child: widget.child),
      );
}
