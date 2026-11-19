import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'audio/engine.dart';
import 'data/library.dart';
import 'data/lyrics.dart';
import 'data/settings.dart';
import 'data/track.dart';
import 'plugins/plugin_manager.dart';

final engineProvider = Provider<PlaybackEngine>((ref) {
  final e = PlaybackEngine();
  e.resolver = (t) => ref.read(pluginsProvider.notifier).resolveStream(t);
  e.onTrackStarted = (t) => ref.read(libraryProvider.notifier).pushRecent(t);
  e.onTrackPatched = (t) => ref.read(libraryProvider.notifier).patchTrack(t);
  e.fadeEnabled = () => ref.read(settingsProvider).fade;
  Timer? t;
  e.onPersist = (s) {
    t?.cancel();
    t = Timer(const Duration(milliseconds: 600), () => _saveSession(ref, e.value));
  };
  ref.onDispose(() {
    t?.cancel();
    e.dispose();
  });
  return e;
});

final playerProvider = NotifierProvider<PlayerNotifier, PlayerSnapshot>(PlayerNotifier.new);

class PlayerNotifier extends Notifier<PlayerSnapshot> {
  @override
  PlayerSnapshot build() {
    final e = ref.watch(engineProvider);
    final sub = e.stream.listen((s) => state = s);
    ref.onDispose(sub.cancel);
    return e.value;
  }
}

/// 当前曲目的歌词（本地 .lrc / 内嵌 / 示例 / 来源插件）。
final lyricsProvider = FutureProvider.autoDispose.family<List<LyricLine>, String>((ref, trackId) async {
  final lib = ref.read(libraryProvider);
  final t = ref.read(playerProvider).current;
  final track = (t != null && t.id == trackId) ? t : lib.byId(trackId);
  if (track == null) return const [];
  final local = await loadLyrics(track);
  if (local.isNotEmpty) return local;
  return ref.read(pluginsProvider.notifier).lyricsFor(track);
});

// ---------- 会话恢复：上次的队列、曲目、位置 ----------
Future<void> _saveSession(Ref ref, PlayerSnapshot s) async {
  try {
    final prefs = ref.read(prefsProvider);
    if (s.queue.isEmpty) {
      await prefs.remove('session');
      return;
    }
    final q = s.queue.take(300).toList();
    final idx = s.index.clamp(0, q.length - 1);
    await prefs.setString(
      'session',
      jsonEncode({
        'ids': [for (final t in q) t.id],
        'online': {for (final t in q.where((t) => t.isOnline)) t.id: t.toJson()},
        'index': idx,
        'pos': s.positionAt(nowMs()),
        'mode': s.mode.index,
      }),
    );
  } catch (_) {}
}

Future<void> restoreSession(ProviderContainer c) async {
  try {
    final raw = c.read(prefsProvider).getString('session');
    if (raw == null) return;
    final j = jsonDecode(raw) as Map<String, dynamic>;
    final lib = c.read(libraryProvider);
    final online = (j['online'] as Map?) ?? const {};
    final q = <Track>[];
    for (final id in (j['ids'] as List)) {
      final t = lib.byId('$id') ??
          (online['$id'] != null ? Track.fromJson(Map<String, dynamic>.from(online['$id'] as Map)) : null);
      if (t != null) q.add(t);
    }
    if (q.isEmpty) return;
    final idx = ((j['index'] as num?)?.toInt() ?? 0).clamp(0, q.length - 1);
    await c.read(engineProvider).setQueue(
          q,
          idx,
          mode: PlayMode.values[((j['mode'] as num?)?.toInt() ?? 0).clamp(0, 2)],
          autoplay: false,
          startMs: (j['pos'] as num?)?.toInt() ?? 0,
        );
  } catch (_) {}
}
