import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/format.dart';
import '../../core/icons.dart';
import '../../core/theme.dart';
import '../../data/derived.dart';
import '../../data/library.dart';
import '../../data/settings.dart';
import '../../data/track.dart';
import '../../nav/nav.dart';
import '../../plugins/plugin_manager.dart';
import '../../state.dart';
import '../actions.dart';
import '../widgets.dart';

class LibraryPage extends ConsumerWidget {
  const LibraryPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = context.c;
    final lib = ref.watch(libraryProvider);
    final nav = ref.read(navProvider.notifier);
    final albums = albumsOf(lib);
    final all = lib.all;
    final liked = lib.resolve(lib.likes);
    final recent = lib.resolve(lib.recents);
    final le = ref.watch(likedEntryProvider);
    var cloudPls = 0;
    for (final p in ref.watch(playlistPluginsProvider)) {
      cloudPls += ref.watch(cloudPlaylistsProvider(p.meta.id)).value?.length ?? 0;
    }

    Widget sysRow(String icon, bool filled, String title, String meta, ListRef r) =>
        _ListRow(cover: IconCover(icon: icon, size: 56, filled: filled), title: title, meta: meta, onTap: () => nav.openList(r));

    return ListView(
      key: const PageStorageKey('library'),
      padding: const EdgeInsets.fromLTRB(18, 18, 18, 18),
      children: [
        const Align(alignment: Alignment.centerLeft, child: Tag('LIBRARY')),
        const SizedBox(height: 4),
        Row(crossAxisAlignment: CrossAxisAlignment.baseline, textBaseline: TextBaseline.alphabetic, children: [
          Expanded(child: Text('曲库', style: NText.sans(30, w: FontWeight.w800, height: 1.3, color: c.k))),
          Text('${two(all.length)} 首本地 · ${two(lib.playlists.length + cloudPls)} 个歌单', style: NText.mono(13, color: c.s, ls: .06)),
        ]),
        const SizedBox(height: 10),
        Container(
          decoration: BoxDecoration(border: Border(top: BorderSide(color: c.k, width: 3))),
          child: Column(children: [
            if (le == null)
              sysRow(NIcons.heart, true, '我喜欢', listMeta(liked.length, totalMs(liked)), const ListRef(ListKind.liked))
            else ...[
              sysRow(NIcons.heart, true, '我喜欢', le.count == null ? '网易云 · 加载中' : '网易云 · ${listMeta(le.count!, le.ms)}', le.ref),
              if (liked.isNotEmpty) sysRow(NIcons.heart, false, '本地喜欢', listMeta(liked.length, totalMs(liked)), const ListRef(ListKind.liked)),
            ],
            sysRow(NIcons.folder, false, '本地音乐', listMeta(all.length, totalMs(all)), const ListRef(ListKind.local)),
            sysRow(NIcons.clock, false, '最近播放', listMeta(recent.length, totalMs(recent)), const ListRef(ListKind.recent)),
            for (final p in ref.watch(playlistPluginsProvider))
              ...(ref.watch(cloudPlaylistsProvider(p.meta.id)).value ?? const <CloudPlaylist>[]).map((pl) => _ListRow(
                  cover: pl.cover != null ? NetCover(pl.cover!, size: 56) : IconCover(icon: NIcons.folder, size: 56, filled: false),
                  title: pl.name,
                  meta: '网易云 · ${pl.count} 首',
                  onTap: () => nav.openList(ListRef(ListKind.cloud, pl.key)))),
          ]),
        ),

        // ---- 专辑
        const SizedBox(height: 26),
        TagRow('ALBUMS · 专辑',
            lineEnd: 12, trailing: Text('${two(albums.length)} 张', style: NText.mono(13, color: c.s))),
        const SizedBox(height: 12),
        SizedBox(
          height: 120 + 8 + 21 + 18 + 4,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            clipBehavior: Clip.none,
            itemCount: albums.length,
            separatorBuilder: (_, _) => const SizedBox(width: 14),
            itemBuilder: (_, i) {
              final a = albums[i];
              return Tap(
                label: '打开专辑 ${a.title}',
                onTap: () => nav.openList(ListRef(ListKind.album, a.key)),
                child: SizedBox(
                  width: 120,
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Cover(a.cover, size: 120),
                    const SizedBox(height: 8),
                    Text(a.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(15, w: FontWeight.w700, height: 1.35, color: c.k)),
                    Text(a.artist, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(13, color: c.s)),
                  ]),
                ),
              );
            },
          ),
        ),

        // ---- 歌单
        const SizedBox(height: 26),
        Row(children: [
          const Expanded(child: TagRow('PLAYLISTS · 歌单', lineEnd: 12)),
          Tap(
            label: '新建歌单',
            onTap: () async {
              final name = await promptDialog(context, title: '新建歌单', hint: '歌单名称');
              if (name != null) ref.read(libraryProvider.notifier).createPlaylist(name);
            },
            child: Container(
              height: 36,
              padding: const EdgeInsets.symmetric(horizontal: 14),
              alignment: Alignment.center,
              decoration: BoxDecoration(border: Border.all(color: c.k)),
              child: Text('＋ 新建', style: NText.sans(14, color: c.k)),
            ),
          ),
        ]),
        const SizedBox(height: 8),
        Container(
          decoration: BoxDecoration(border: Border(top: BorderSide(color: c.k))),
          child: lib.playlists.isEmpty
              ? Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Text('NO PLAYLISTS · 还没有歌单', style: NText.mono(13, color: c.s, ls: .08)),
                )
              : Column(children: [
                  for (final p in lib.playlists)
                    Builder(builder: (_) {
                      final tr = lib.resolve(p.trackIds);
                      return _ListRow(
                        cover: Cover(tr.firstOrNull, size: 56),
                        title: p.name,
                        meta: listMeta(tr.length, totalMs(tr)),
                        onTap: () => nav.openList(ListRef(ListKind.playlist, p.id)),
                        onLong: () => _playlistMenu(context, ref, p.id, p.name),
                      );
                    }),
                ]),
        ),

        const SizedBox(height: 26),
        const TagRow('IMPORT · 导入'),
        const SizedBox(height: 12),
        Tap(
          label: '导入本地音乐文件',
          onTap: () => importMusic(context, ref),
          child: Container(
            height: 48,
            alignment: Alignment.center,
            decoration: BoxDecoration(border: Border.all(color: c.k)),
            child: Text('＋ 导入音乐文件', style: NText.sans(15, color: c.k)),
          ),
        ),
        const SizedBox(height: 8),
        Text('可同时选择同名 .lrc 歌词文件；导入的音频会复制一份保存在应用内。',
            style: NText.sans(12, color: c.s, height: 1.6)),
      ],
    );
  }
}

void _playlistMenu(BuildContext context, WidgetRef ref, String id, String name) {
  showNekoSheet(context, title: name, items: [
    SheetItem('重命名', () async {
      final n = await promptDialog(context, title: '重命名歌单', initial: name);
      if (n != null) ref.read(libraryProvider.notifier).renamePlaylist(id, n);
    }),
    SheetItem('删除歌单', () async {
      final ok = await confirmDialog(context, title: '删除歌单', body: '只删除歌单本身，不会删除里面的曲目。', ok: '删除', danger: true);
      if (ok) ref.read(libraryProvider.notifier).deletePlaylist(id);
    }, danger: true),
  ]);
}

class _ListRow extends StatelessWidget {
  const _ListRow({required this.cover, required this.title, required this.meta, required this.onTap, this.onLong});
  final Widget cover;
  final String title, meta;
  final VoidCallback onTap;
  final VoidCallback? onLong;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Tap(
      label: '打开 $title',
      onTap: onTap,
      onLongPress: onLong,
      child: Container(
        height: 76,
        decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.l1))),
        child: Row(children: [
          cover,
          const SizedBox(width: 14),
          Expanded(
            child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(16, w: FontWeight.w700, height: 1.35, color: c.k)),
              Text(meta, style: NText.mono(12, color: c.s, ls: .04)),
            ]),
          ),
          NIcon(NIcons.chevronRight, size: 16, color: c.k),
        ]),
      ),
    );
  }
}

/// 歌单 / 专辑 / 合集详情（共用）
class DetailPage extends ConsumerWidget {
  const DetailPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = context.c;
    final lib = ref.watch(libraryProvider);
    final ref0 = ref.watch(navProvider.select((s) => s.list));
    final cur = ref.watch(playerProvider.select((s) => s.current));
    final name = ref.watch(settingsProvider.select((s) => s.name));
    var v = resolveList(lib, ref0);
    final isUser = ref0.kind == ListKind.playlist;
    final isCloud = ref0.kind == ListKind.cloud;
    AsyncValue<List<Track>>? cloud;
    if (ref0.kind == ListKind.daily) {
      final rv = ref.watch(recommendProvider);
      cloud = rv;
      final rec = rv.value ?? const <Track>[];
      v = ListView0(ref: ref0, title: '每日推荐', tag: 'DAILY MIX', tracks: rec, cover: rec.firstOrNull);
    }
    if (isCloud) {
      final isPl = ref0.id.contains('#');
      final pid = isPl ? ref0.id.substring(0, ref0.id.indexOf('#')) : ref0.id;
      cloud = isPl ? ref.watch(cloudPlaylistTracksProvider(ref0.id)) : ref.watch(cloudLikedProvider(ref0.id));
      final pn = ref.watch(pluginsProvider).items.where((e) => e.meta.id == pid).firstOrNull?.meta.name ?? pid;
      final plName = isPl
          ? (ref.watch(cloudPlaylistsProvider(pid)).value ?? const <CloudPlaylist>[]).where((e) => e.key == ref0.id).firstOrNull?.name ?? '歌单'
          : '我喜欢';
      v = ListView0(ref: ref0, title: isPl ? plName : '我喜欢', tag: 'CLOUD', tracks: cloud!.value ?? const [], sysIcon: isPl ? 'playlist' : 'liked', sub: isPl ? pn : null);
    }

    Widget cover;
    String? netCover;
    if (isCloud && ref0.id.contains('#')) {
      final pid = ref0.id.substring(0, ref0.id.indexOf('#'));
      netCover = (ref.watch(cloudPlaylistsProvider(pid)).value ?? const <CloudPlaylist>[]).where((e) => e.key == ref0.id).firstOrNull?.cover;
    }
    if (netCover != null) {
      cover = NetCover(netCover, size: 112);
    } else if (v.sysIcon != null) {
      cover = IconCover(
          icon: v.sysIcon == 'liked' ? NIcons.heart : (v.sysIcon == 'local' || v.sysIcon == 'playlist') ? NIcons.folder : NIcons.clock,
          size: 112,
          filled: v.sysIcon == 'liked',
          iconSize: 44);
    } else {
      cover = Cover(v.cover, size: 112);
    }

    final header = <Widget>[
        Align(alignment: Alignment.centerLeft, child: Tag(v.tag)),
        const SizedBox(height: 12),
        SizedBox(
          height: 112,
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            cover,
            const SizedBox(width: 16),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(v.title, maxLines: 2, overflow: TextOverflow.ellipsis, style: NText.sans(24, w: FontWeight.w800, height: 1.25, color: c.k)),
                const SizedBox(height: 4),
                Text(v.meta, maxLines: 2, style: NText.mono(12, color: c.s, ls: .04)),
                const Spacer(),
                if (ref0.kind != ListKind.album)
                  Text(isCloud ? '网易云音乐' : ref0.kind == ListKind.daily ? '每日更新' : '$name · 本地歌单', maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(13, color: c.s)),
              ]),
            ),
          ]),
        ),
        const SizedBox(height: 16),
        Row(children: [
          Expanded(child: PrimaryBar(label: '播放全部', icon: NIcons.play, onTap: () => playList(ref, v.tracks, 0))),
          const SizedBox(width: 10),
          OutlineBtn(label: '随机播放', onTap: () => shuffleList(ref, v.tracks)),
        ]),
        if (isUser || ref0.kind == ListKind.local)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Row(children: [
              if (isUser) ...[
                LinkBtn('重命名', onTap: () async {
                  final n = await promptDialog(context, title: '重命名歌单', initial: v.title);
                  if (n != null) ref.read(libraryProvider.notifier).renamePlaylist(ref0.id, n);
                }),
                const SizedBox(width: 16),
                LinkBtn('删除歌单', color: c.bad, onTap: () async {
                  final ok = await confirmDialog(context, title: '删除歌单', body: '只删除歌单本身，不会删除里面的曲目。', ok: '删除', danger: true);
                  if (ok) {
                    ref.read(libraryProvider.notifier).deletePlaylist(ref0.id);
                    ref.read(navProvider.notifier).back();
                  }
                }),
              ],
              if (ref0.kind == ListKind.local) LinkBtn('＋ 导入音乐文件', onTap: () => importMusic(context, ref)),
            ]),
          ),
        const SizedBox(height: 12),
        Container(height: 3, color: c.k),
      ];

    final emptyText = cloud == null
        ? 'NO TRACKS · 这里还没有曲目'
        : cloud.isLoading
            ? 'LOADING · 正在从云端加载…'
            : cloud.hasError
                ? '加载失败：${cloud.error}'
                : 'NO TRACKS · 这里是空的';

    return CustomScrollView(
      key: PageStorageKey('detail-${ref0.kind}-${ref0.id}'),
      cacheExtent: 64 * 20, // 屏幕上下各预先排好约 20 行，封面提前开始加载
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(18, 20, 18, 0),
          sliver: SliverList(delegate: SliverChildListDelegate(header)),
        ),
        if (v.tracks.isEmpty)
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(18, 28, 18, 28),
            sliver: SliverToBoxAdapter(child: Text(emptyText, style: NText.mono(13, color: c.s, ls: .08))),
          )
        else
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(18, 0, 18, 18),
            sliver: SliverFixedExtentList(
              itemExtent: 64,
              delegate: SliverChildBuilderDelegate(
                (_, i) => TrackRow(
                  track: v.tracks[i],
                  current: cur?.id == v.tracks[i].id,
                  sourceLabel: v.tracks[i].isOnline ? v.tracks[i].source : null,
                  onTap: () => playList(ref, v.tracks, i),
                  onLong: () => showTrackActions(context, ref, v.tracks[i], playlistId: isUser ? ref0.id : null),
                ),
                childCount: v.tracks.length,
              ),
            ),
          ),
      ],
    );
  }
}
