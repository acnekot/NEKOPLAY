import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../audio/engine.dart';
import '../data/library.dart';
import '../data/track.dart';
import '../plugins/plugin_manager.dart';
import '../state.dart';
import 'widgets.dart';

/// 导入进度（顶部细条）
final busyProvider = NotifierProvider<BusyNotifier, String?>(BusyNotifier.new);

class BusyNotifier extends Notifier<String?> {
  @override
  String? build() => null;
  void set(String? v) => state = v;
}

/// 播放一个列表：从 start 开始，队列 = 整个列表。
Future<void> playList(WidgetRef ref, List<Track> list, int start, {PlayMode? mode}) async {
  if (list.isEmpty) return;
  await ref.read(engineProvider).setQueue(list, start.clamp(0, list.length - 1), mode: mode);
}

Future<void> shuffleList(WidgetRef ref, List<Track> list) async {
  if (list.isEmpty) return;
  final i = DateTime.now().microsecondsSinceEpoch % list.length;
  await playList(ref, list, i, mode: PlayMode.shuffle);
}

/// 选择本地音频文件（可同时选同名 .lrc 歌词）并导入曲库。
Future<void> importMusic(BuildContext context, WidgetRef ref) async {
  final toast = ref.read(toastProvider.notifier);
  try {
    final files = await FilePicker.pickFiles(
      dialogTitle: '选择音乐文件（可同时选同名 .lrc 歌词）',
      type: FileType.custom,
      allowedExtensions: [...kAudioExts, 'lrc'],
    );
    if (files.isEmpty) return;
    final busy = ref.read(busyProvider.notifier);
    busy.set('正在导入 0 / ${files.length}');
    final r = await ref.read(libraryProvider.notifier).importFiles(files, onProgress: (d, t) {
      busy.set('正在导入 $d / $t');
    });
    busy.set(null);
    final parts = <String>[
      if (r.added > 0) '已导入 ${r.added} 首',
      if (r.skipped > 0) '${r.skipped} 首已存在',
      if (r.failed > 0) '${r.failed} 首失败',
    ];
    toast.show(parts.isEmpty ? '没有可导入的音频文件' : parts.join('，'));
  } catch (e) {
    ref.read(busyProvider.notifier).set(null);
    toast.show('导入失败：$e');
  }
}

Future<void> pickPlaylist(BuildContext context, WidgetRef ref, Track t) async {
  final lib = ref.read(libraryProvider);
  final store = ref.read(libraryProvider.notifier);
  final toast = ref.read(toastProvider.notifier);
  await showNekoSheet(
    context,
    title: '加入歌单',
    subtitle: t.title,
    items: [
      SheetItem('＋ 新建歌单', () async {
        final name = await promptDialog(context, title: '新建歌单', hint: '歌单名称');
        if (name == null) return;
        final id = store.createPlaylist(name);
        store.addToPlaylist(id, t);
        toast.show('已加入新歌单');
      }),
      for (final l in lib.playlists)
        SheetItem(l.name, () {
          store.addToPlaylist(l.id, t);
          toast.show('已加入「${l.name}」');
        }),
    ],
  );
}

/// 切换喜欢：本地记录 + 在线曲目同步到云端（失败只提示，不回滚本地）。
Future<void> toggleLikeSync(WidgetRef ref, Track t) async {
  HapticFeedback.lightImpact();
  final liked = ref.read(libraryProvider).likes.contains(t.id) || ref.read(cloudLikedIdsProvider).contains(t.id);
  final store = ref.read(libraryProvider.notifier);
  if (ref.read(libraryProvider).likes.contains(t.id) == liked) store.toggleLike(t);
  if (!t.isOnline) return;
  final pi = ref.read(likedPluginsProvider).where((p) => p.meta.id == t.source).firstOrNull;
  if (pi == null) return;
  final err = await ref.read(pluginsProvider.notifier).setCloudLike(t, !liked);
  if (err != null) {
    ref.read(toastProvider.notifier).show('云端同步失败：$err');
  } else {
    ref.invalidate(cloudLikedProvider(t.source));
  }
}

/// 曲目长按菜单
void showTrackActions(BuildContext context, WidgetRef ref, Track t, {String? playlistId}) {
  final store = ref.read(libraryProvider.notifier);
  final engine = ref.read(engineProvider);
  final toast = ref.read(toastProvider.notifier);
  final liked = ref.read(libraryProvider).likes.contains(t.id) || ref.read(cloudLikedIdsProvider).contains(t.id);
  showNekoSheet(
    context,
    title: t.title,
    subtitle: t.artist,
    items: [
      SheetItem('下一首播放', () {
        engine.enqueueNext(t);
        toast.show('已加入「下一首播放」');
      }),
      SheetItem('加入播放队列', () {
        engine.enqueue(t);
        toast.show('已加入播放队列');
      }),
      if (!t.demo) SheetItem('加入歌单…', () => pickPlaylist(context, ref, t)),
      if (!t.demo)
        SheetItem(liked ? '取消喜欢' : '加入「我喜欢」', () {
          toggleLikeSync(ref, t);
          toast.show(liked ? '已取消喜欢' : '已加入「我喜欢」');
        }),
      if (playlistId != null)
        SheetItem('从歌单移除', () => store.removeFromPlaylist(playlistId, t.id)),
      if (t.isLocal && !t.demo)
        SheetItem('从曲库删除', () async {
          final ok = await confirmDialog(context,
              title: '删除曲目', body: '会同时删除应用内保存的这份音频副本（不影响你原来的文件）。', ok: '删除', danger: true);
          if (ok) {
            await store.removeTrack(t.id);
            toast.show('已删除「${t.title}」');
          }
        }, danger: true),
    ],
  );
}
