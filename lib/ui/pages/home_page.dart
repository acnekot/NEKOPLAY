import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/format.dart';
import '../../core/icons.dart';
import '../../core/theme.dart';
import '../../data/derived.dart';
import '../../data/library.dart';
import '../../data/track.dart';
import '../../nav/nav.dart';
import '../../plugins/plugin_manager.dart';
import '../../state.dart';
import '../actions.dart';
import '../widgets.dart';

class HomePage extends ConsumerWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = context.c;
    final lib = ref.watch(libraryProvider);
    final player = ref.watch(playerProvider);
    final nav = ref.read(navProvider.notifier);
    final engine = ref.read(engineProvider);
    final all = lib.all;
    final rec = ref.watch(recommendProvider).value ?? const <Track>[];

    // 每日推荐：按日期固定的随机 3 首
    final now = DateTime.now();
    final rng = Random(now.year * 10000 + now.month * 100 + now.day);
    final mix = rec.isNotEmpty ? rec.take(10).toList() : ([...all]..shuffle(rng)).take(3).toList();
    final base = all.isNotEmpty ? all : mix;
    final cur = player.current ?? (base.isEmpty ? null : base.first);

    final albums = albumsOf(lib);
    final artists = {for (final t in all) t.albumArtist}.length;
    final lossless = all.isEmpty ? 0 : (all.where((t) => t.lossless).length * 100 / all.length).round();

    final le = ref.watch(likedEntryProvider);
    final cards = <_Card>[
      if (le == null)
        _Card(const ListRef(ListKind.liked), '我喜欢', listMeta(lib.resolve(lib.likes).length, totalMs(lib.resolve(lib.likes))), icon: NIcons.heart, filled: true)
      else
        _Card(le.ref, '我喜欢', le.count == null ? '网易云' : listMeta(le.count!, le.ms), icon: NIcons.heart, filled: true),
      _Card(const ListRef(ListKind.local), '本地音乐', listMeta(all.length, totalMs(all)), icon: NIcons.folder),
      _Card(const ListRef(ListKind.recent), '最近播放', listMeta(lib.resolve(lib.recents).length, totalMs(lib.resolve(lib.recents))), icon: NIcons.clock),
      for (final p in lib.playlists)
        _Card(ListRef(ListKind.playlist, p.id), p.name, listMeta(lib.resolve(p.trackIds).length, totalMs(lib.resolve(p.trackIds))),
            cover: lib.resolve(p.trackIds).firstOrNull),
    ];

    return ListView(
      key: const PageStorageKey('home'),
      padding: const EdgeInsets.fromLTRB(18, 20, 18, 24),
      children: [
        // ---- CONTINUE LISTENING
        const Align(alignment: Alignment.centerLeft, child: Tag('CONTINUE LISTENING')),
        const SizedBox(height: 12),
        Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
          Tap(
            label: '打开正在播放',
            onTap: () => player.current != null ? nav.go(Pg.now) : (cur != null ? playList(ref, base, 0) : null),
            child: Cover(cur, size: 128),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(cur?.title ?? '还没有曲目', maxLines: 3, overflow: TextOverflow.ellipsis, style: NText.sans(26, w: FontWeight.w800, height: 1.1, color: c.k)),
              const SizedBox(height: 4),
              Text(cur?.artist ?? '导入音乐或启用音源后开始播放', maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(14, color: c.s2)),
              const SizedBox(height: 16),
              if (player.current != null) ...[
                const TimeRow(),
                const SizedBox(height: 8),
                const ScrubBar(interactive: false, hitHeight: 4),
              ] else ...[
                Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                  Text('00:00', style: NText.mono(12, color: c.p)),
                  Text(cur?.durStr ?? '00:00', style: NText.mono(12, color: c.s)),
                ]),
                const SizedBox(height: 8),
                Container(height: 4, color: c.l3),
              ],
            ]),
          ),
        ]),
        const SizedBox(height: 16),
        Row(children: [
          Expanded(
            child: PrimaryBar(
              label: player.playing ? '暂停' : (player.current != null ? '继续播放' : '播放'),
              icon: player.playing ? NIcons.pause : NIcons.play,
              onTap: () => player.current != null ? engine.toggle() : (base.isEmpty ? null : playList(ref, base, 0)),
            ),
          ),
          const SizedBox(width: 8),
          OutlineBtn(label: '打开队列', onTap: () {
            if (player.current == null && base.isNotEmpty) playList(ref, base, 0);
            nav.openQueue();
          }),
        ]),

        // ---- DAILY MIX
        const SizedBox(height: 32),
        const Align(alignment: Alignment.centerLeft, child: Tag('DAILY MIX')),
        Container(
          margin: const EdgeInsets.only(top: 10),
          padding: const EdgeInsets.only(bottom: 14),
          decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.k, width: 3))),
          child: Row(children: [
            Expanded(
              child: Tap(
                label: '打开每日推荐歌单',
                onTap: () => nav.openList(const ListRef(ListKind.daily)),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Text('每日推荐', style: NText.sans(26, w: FontWeight.w800, height: 1.15, ls: .08, color: c.k)),
                    const SizedBox(width: 6),
                    NIcon(NIcons.chevronRight, size: 20, color: c.s),
                  ]),
                  const SizedBox(height: 4),
                  Text(rec.isNotEmpty ? '${rec.length} 首 · 点开查看全部' : 'DAILY RECOMMENDATION', style: NText.mono(11, color: c.s, ls: .08)),
                ]),
              ),
            ),
            const SizedBox(width: 14),
            SquareBtn(icon: NIcons.play, label: '播放每日推荐', iconSize: 18, onTap: () => mix.isEmpty ? null : playList(ref, mix, 0)),
            const SizedBox(width: 14),
            Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Text(two(now.month), style: NText.mono(46, w: FontWeight.w600, height: .9, ls: -.05, color: c.p)),
              Text(two(now.day), style: NText.mono(46, w: FontWeight.w600, height: .9, ls: -.05, color: c.p)),
            ]),
          ]),
        ),
        if (mix.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 22),
            child: Text('NO TRACKS · 导入本地音乐，或在「音源插件」里启用在线音源后显示推荐', style: NText.mono(12, color: c.s, ls: .06, height: 1.7)),
          ),
        for (var i = 0; i < mix.length; i++)
          TrackRow(
            track: mix[i],
            current: player.current?.id == mix[i].id,
            onTap: () => playList(ref, mix, i),
            onLong: () => showTrackActions(context, ref, mix[i]),
          ),
        if (rec.length > mix.length)
          Align(
            alignment: Alignment.centerRight,
            child: LinkBtn('查看全部 ${rec.length} 首', onTap: () => nav.openList(const ListRef(ListKind.daily))),
          ),

        // ---- RECOMMENDED COLLECTIONS
        const SizedBox(height: 32),
        const TagRow('RECOMMENDED COLLECTIONS'),
        const SizedBox(height: 2),
        Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
          Expanded(child: Text('推荐合集', style: NText.sans(24, w: FontWeight.w800, height: 1.4, color: c.k))),
          LinkBtn('查看全部', onTap: () => nav.go(Pg.library)),
        ]),
        const SizedBox(height: 12),
        SizedBox(
          height: 132 + 8 + 20 + 18 + 4,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            clipBehavior: Clip.none,
            itemCount: cards.length,
            separatorBuilder: (_, _) => const SizedBox(width: 14),
            itemBuilder: (_, i) {
              final k = cards[i];
              return Tap(
                label: '打开合集 ${k.title}',
                onTap: () => nav.openList(k.ref),
                child: SizedBox(
                  width: 132,
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    k.icon != null ? IconCover(icon: k.icon!, size: 132, filled: k.filled, iconSize: 40) : Cover(k.cover, size: 132),
                    const SizedBox(height: 8),
                    Text(k.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(15, w: FontWeight.w700, height: 1.35, color: c.k)),
                    Text(k.meta, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.mono(12, color: c.s, ls: .04)),
                  ]),
                ),
              );
            },
          ),
        ),

        // ---- 统计
        const SizedBox(height: 14),
        Container(
          decoration: BoxDecoration(border: Border(top: BorderSide(color: c.k, width: 3))),
          child: Column(children: [
            Row(children: [
              _stat(c, '曲库', '${two(all.length)} 首', false, false),
              _stat(c, '总时长', fmtMs(totalMs(all)), true, false),
            ]),
            Row(children: [
              _stat(c, '艺人', '${two(artists)} 位', false, true),
              _stat(c, '无损占比', '$lossless%', true, true),
            ]),
          ]),
        ),
        if (albums.isEmpty) const SizedBox.shrink(),
      ],
    );
  }

  Widget _stat(NekoColors c, String k, String v, bool left, bool top) => Expanded(
        child: Container(
          padding: EdgeInsets.fromLTRB(left ? 16 : 0, 12, 0, 12),
          decoration: BoxDecoration(
            border: Border(
              left: left ? BorderSide(color: c.l2) : BorderSide.none,
              top: top ? BorderSide(color: c.l2) : BorderSide.none,
            ),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(k, style: NText.mono(11, color: c.s, ls: .08)),
            Text(v, style: NText.mono(24, color: c.k)),
          ]),
        ),
      );
}

class _Card {
  _Card(this.ref, this.title, this.meta, {this.icon, this.filled = false, this.cover});
  final ListRef ref;
  final String title, meta;
  final String? icon;
  final bool filled;
  final Track? cover;
}
