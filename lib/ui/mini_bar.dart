import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/format.dart';
import '../core/icons.dart';
import '../core/theme.dart';
import '../nav/nav.dart';
import '../state.dart';
import 'widgets.dart';

/// 迷你播放条：顶部是进度线（16px 热区，可拖动，拖动时时间气泡跟着手指），点击其余区域展开播放页。
class MiniBar extends ConsumerStatefulWidget {
  const MiniBar({super.key});
  @override
  ConsumerState<MiniBar> createState() => _MiniBarState();
}

class _MiniBarState extends ConsumerState<MiniBar> {
  double? _scrub;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final t = ref.watch(playerProvider.select((s) => s.current));
    if (t == null) return const SizedBox.shrink();
    final playing = ref.watch(playerProvider.select((s) => s.playing));
    final engine = ref.read(engineProvider);
    final nav = ref.read(navProvider.notifier);
    final dur = ref.watch(playerProvider.select((s) => s.durationMs));

    return LayoutBuilder(builder: (context, box) {
      final w = box.maxWidth;
      return SizedBox(
        height: 60,
        child: Stack(clipBehavior: Clip.none, children: [
          Positioned.fill(
            child: Container(
              decoration: BoxDecoration(color: c.bar, border: Border(top: BorderSide(color: c.l2))),
              padding: const EdgeInsets.only(left: 14, right: 6, top: 4),
              child: Tap(
                label: '展开播放页',
                onTap: () => nav.go(Pg.now),
                child: Row(children: [
                  Cover(t, size: 40),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(t.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(15, w: FontWeight.w700, color: c.k, height: 1.3)),
                      Text(t.artist, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(12, color: c.s, height: 1.3)),
                    ]),
                  ),
                  Tap(
                    label: playing ? '暂停' : '播放',
                    onTap: engine.toggle,
                    child: SizedBox(width: 48, height: 48, child: Center(child: NIcon(playing ? NIcons.pause : NIcons.play, size: 22, color: c.k))),
                  ),
                  Tap(
                    label: '下一首',
                    onTap: engine.next,
                    child: SizedBox(width: 44, height: 48, child: Center(child: NIcon(NIcons.next, size: 20, color: c.k))),
                  ),
                ]),
              ),
            ),
          ),
          // 进度线（压在顶边上）
          Positioned(left: 0, right: 0, top: -7, child: ScrubBar(onScrub: (f) => setState(() => _scrub = f))),
          if (_scrub != null)
            Positioned(
              left: (w * _scrub! - 26).clamp(4.0, w - 56),
              top: -38,
              child: Container(
                width: 52,
                height: 26,
                alignment: Alignment.center,
                color: c.k,
                child: Text(fmtMs((_scrub! * dur).round()), style: NText.mono(12, color: c.tagFg)),
              ),
            ),
        ]),
      );
    });
  }
}
